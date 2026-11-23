# mcp/ — MCP server, CLI and bot client library

The bot side of PhoneBridge: a TypeScript client library that pairs with an Android phone and drives it
over the end-to-end encrypted relay, plus an MCP server and a CLI that expose the same commands.

The wire format is owned by the Kotlin `core/` module; this package implements
[../docs/PROTOCOL.md](../docs/PROTOCOL.md) (Noise `XX`, ChaCha20-Poly1305, pairing crypto, chunk events,
relay `dh-hmac-sha256` hello proof) and is checked against `core/` by byte-exact test vectors.

Docs: [../docs/API.md](../docs/API.md) (command reference, error codes, MCP configs).

## Build and test

Requires Node 20+ (developed on Node 26, strict TypeScript).

```sh
npm ci
npm run build
npm test
npm run typecheck   # build config + tests
```

`npm test` runs offline against an in-process relay (`test/helpers/mockRelay.ts`) and a protocol-faithful
mock device (`test/helpers/mockDevice.ts`): pairing-input parsing, CRC-5 codes, the Noise handshake and
transport against `test/vectors/handshake-v1.json`, request/response correlation, timeouts, chunked
screenshots, events, permission levels, keystore behaviour, the CLI and the MCP tool listing.

## Use

```sh
# MCP over stdio (OpenCode, Claude Desktop/Code, any MCP client)
node dist/server/index.js

# MCP over streamable HTTP on loopback
node dist/server/index.js --http 8765

# CLI
node dist/cli/index.js pair 7K4-Q9B --relay wss://relay.example:8443
node dist/cli/index.js devices
node dist/cli/index.js screenshot -o shot.png
node dist/cli/index.js tap 540 1200
```

The keystore lives in `~/.phonebridge/keystore.json` (override with `$PHONEBRIDGE_HOME` or `--home`) and
holds the bot's long-term X25519 key plus its paired devices. It never stores the one-time pairing
secret.

## Layout

| Path | What |
| --- | --- |
| `src/client/` | Primitives, Noise XX, pairing crypto, relay transport, chunking, sessions, high-level client, keystore |
| `src/server/` | MCP tools, server factory, stdio/HTTP entry point |
| `src/cli/` | `phonebridge` CLI (argument parsing is exported for tests) |
| `src/index.ts` | public library exports |
| `test/vectors/` | cross-language protocol vectors for the Kotlin `core/` module |
| `scripts/gen-vectors.mjs` | regenerates the vectors |

## Protocol summary (as implemented)

- Identity: one long-term X25519 key pair; `peer_id` = lowercase Crockford base32 of
  `SHA-256(publicKey)[0..16)`; fingerprint = the first 8 symbols uppercased as `ABCD-EFGH`.
- Pairing codes: five Crockford data symbols plus a CRC-5 check symbol, written `XXX-XXX`.
- Pairing payloads: `HKDF-SHA256(secret, "PhoneBridge/1/pairing", "pair-frame")` and
  ChaCha20-Poly1305 with direction- and device-bound AAD. Pairing happens **before** Noise, over relay
  `route` envelopes.
- Session: Noise `XX` (`Noise_XX_25519_ChaChaPoly_SHA256`, prologue `PhoneBridge/1/noise-xx`) with
  `0x01`/`0x02` frame tags; per-direction keys, little-endian counters, empty AAD, 8 MiB plaintext cap.
- Relay: `challenge → hello (dh-hmac-sha256) → hello_ok`, verbatim `route` forwarding, short-code
  mailbox (`pair_store`/`pair_lookup`/`pair_offer`) and `relay_moved`.
- Large payloads: `chunk` events with `chunk_id`/`index`/`total`/`data_b64`/`sha256`; 256 KiB slices,
  64 MiB reassembly cap, SHA-256 verified before use.

Verified end to end against the real Kotlin relay (`relay/build/install/phonebridge-relay`) on loopback
for link pairing, short-code pairing, the Noise handshake, commands and events.

## Licence

MIT, like the rest of PhoneBridge. Dependency licences are recorded in
[../docs/THIRD_PARTY.md](../docs/THIRD_PARTY.md).
