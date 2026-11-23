# Handshake test vectors (v1)

`handshake-v1.json` is the byte-exact contract between this TypeScript client and the Kotlin `:core`
module (task 01). If a feature works here but not in Kotlin, this file says which side is wrong.

## How it was produced

`mcp/scripts/gen-vectors.mjs` derives every value from fixed inputs (SHA-256 of label strings, so they
are reproducible) using the same code the client uses:

```sh
cd mcp
npm ci
npm run build
npm run vectors
```

Commit the regenerated JSON together with the script whenever the protocol changes, and note it in
`HANDOFF.md` so the `:core` lane can update.

## What to check in Kotlin

1. **Identities** — `peer_id` = Crockford base32 (lowercase, no padding) of the first 16 bytes of
   `SHA-256(publicKey)`; `fingerprint` = first 8 symbols uppercased as `ABCD-EFGH`.
2. **Handshake messages** — `hs1`/`hs2` must serialise to exactly the JSON objects in the file
   (base64url, no padding, fields in any order but the same values).
3. **Key schedule** — compute `dh1..dh4`, the transcript hash, `ikm`, `okm` and the two direction keys
   and compare with hex. `okm = BLAKE2b-512(key = th, data = dh1||dh2||dh3||dh4)` via libsodium
   `crypto_generichash`, so Kotlin needs the same libsodium (or a BLAKE2b implementation).
4. **AEAD frames** — XChaCha20-Poly1305 IETF, AAD `PhoneBridge/v1/data`, 24-byte nonce = 16 zero bytes
   followed by a big-endian 64-bit counter. `hs3` is sealed by the bot at counter 0, `hs4` by the
   device at counter 0, application data continues at counter 1.
5. **Relay hello proof** — `proof = X25519(bot secret, challenge public key)`; the relay checks it with
   `X25519(challenge secret, bot public key)` and gets the same value.

## Vector map

| Field | Meaning |
| --- | --- |
| `identities.bot` / `identities.device` | Long-term X25519 key pairs, `peerId`, `fingerprint` |
| `ephemeral.bot` / `ephemeral.device` | Handshake ephemeral key pairs and the 32-byte nonces |
| `pairing.secret`, `pairing.code`, `pairing.offer` | One-time pairing inputs and the offer JSON |
| `handshake.hs1`, `handshake.hs2` | The exact plaintext handshake messages |
| `handshake.dh.dh1..dh4` | X25519 outputs in bot-centric order |
| `handshake.transcriptHash`, `ikm`, `okm` | KDF intermediates |
| `handshake.keys.botToDevice` / `deviceToBot` | Per-direction AEAD keys |
| `handshake.hs3`, `handshake.hs4` | Finish messages: plaintext JSON, nonce, ciphertext |
| `frames.tapRequest` | A `tap` request sealed at counter 1 |
| `frames.direct` | One AEAD call with an explicit key/nonce/AAD |
| `relay.challenge`, `relay.proof` | One-pass DH hello proof (both sides) |

All binary values are lowercase hex except the handshake JSON strings, which are base64url exactly as
they go over the wire.
