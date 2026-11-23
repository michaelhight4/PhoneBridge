# Protocol test vectors (v1)

`handshake-v1.json` is the byte-exact contract between this TypeScript client and `core/` (task 01).

## How it was produced

`mcp/scripts/gen-vectors.mjs` derives every value from fixed inputs (SHA-256 of label strings, so they
are reproducible) using the same code the client uses:

```sh
cd mcp
npm ci
npm run build
npm run vectors
```

Commit the regenerated JSON with the script after any protocol change, and note it in `HANDOFF.md` so
the `core/` lane can update its reference tests.

## What to check in Kotlin

1. **Identities** — `peer_id` = lowercase Crockford base32 of `SHA-256(publicKey)[0..16)`; the file
   includes the documented example (`publicKey = 00 01 … 1f → cc6wtab6rgspd48jah4bqcjv9w`).
2. **Short codes** — five data symbols plus a CRC-5 check symbol (poly `0x05`, init `0x1F`, MSB first):
   `7K4Q9 → 7K4-Q9B`, `ZZZZZ → ZZZ-ZZQ`, `00000 → 000-00T`.
3. **Relay hello proof** — `sig = HMAC-SHA256(X25519(peer_private, relay_pub), context)` with
   `context = "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>"`.
4. **Pairing crypto** — `HKDF-SHA256(secret, salt "PhoneBridge/1/pairing", info "pair-frame")`, then
   ChaCha20-Poly1305 with `sealed = nonce(12) || ciphertext || tag(16)`; the AAD strings are
   `"PhoneBridge/1/pair|request|"` and `"PhoneBridge/1/pair|result|"` followed by `device_pub`.
5. **Noise XX** — `Noise_XX_25519_ChaChaPoly_SHA256`, prologue `PhoneBridge/1/noise-xx`, frames tagged
   `0x01` (handshake) and `0x02` (transport). The file pins the three handshake frames (33, 97 and 65
   bytes including the tag), the handshake hash, the peer static keys and the first transport frames in
   both directions.
6. **Transport nonce** — 64-bit counter little-endian plus four zero bytes; empty AAD.
7. **Chunks** — deterministic slices with the whole-payload SHA-256 repeated on each chunk.

## Vector map

| Field | Meaning |
| --- | --- |
| `identities.bot` / `identities.device` | Long-term X25519 key pairs, `peerId`, `fingerprint` |
| `peerIdExample` | The `00..1f` public key → `cc6wtab6rgspd48jah4bqcjv9w` example |
| `shortCode` | CRC-5 examples and the vector's pairing code |
| `relayChallenge` | Relay ephemeral key, nonce, context string and HMAC proof |
| `pairing` | Derived key, fixed nonce, sealed request and sealed result |
| `noise` | Ephemeral keys, `hs1`/`hs2`/`hs3` (base64url and hex), handshake hash, pinned peer keys |
| `transport` | First request and event frames, both directions, plus nonce 0/1 |
| `chunks` | Deterministic chunk set with payload hash |
| `kdf` | RFC 5869 HKDF-SHA256 case 1 and an HMAC-SHA256 example |

All binary values are lowercase hex except the frame fields that are base64url exactly as they travel in
a relay `route.payload`.
