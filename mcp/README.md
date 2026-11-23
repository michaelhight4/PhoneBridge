# mcp/ — MCP server, CLI and bot client library

The bot side of PhoneBridge: a TypeScript client library that pairs with an Android phone and drives it
over the end-to-end encrypted relay, plus an MCP server and a CLI that expose the same commands.

Docs: [../docs/API.md](../docs/API.md) (command reference, error codes, MCP configs),
[../docs/PROTOCOL.md](../docs/PROTOCOL.md) (wire protocol).

## Build and test

Requires Node 20+ (developed on Node 26, strict TypeScript).

```sh
npm ci
npm run build
npm test
npm run typecheck   # build config + tests
```

`npm test` runs everything offline against an in-process relay (`test/helpers/mockRelay.ts`) and a mock
device (`test/helpers/mockDevice.ts`): pairing-input parsing, the handshake against
`test/vectors/handshake-v1.json`, request/response correlation, timeouts, chunked screenshots, events,
permission levels, keystore behaviour, the CLI and the MCP tool listing.

## Use

```sh
# MCP over stdio (OpenCode, Claude Desktop/Code, any MCP client)
node dist/server/index.js

# MCP over streamable HTTP on loopback
node dist/server/index.js --http 8765

# CLI
node dist/cli/index.js pair 7K4-Q9Y --relay wss://relay.example:8765
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
| `src/client/` | Pairing input parsing, keystore, crypto, handshake, relay transport, encrypted frames, sessions, high-level client |
| `src/server/` | MCP tools, server factory, stdio/HTTP entry point |
| `src/cli/` | `phonebridge` CLI (argument parsing is exported for tests) |
| `src/index.ts` | public library exports |
| `test/vectors/` | cross-language handshake vectors for the Kotlin `:core` module |
| `scripts/gen-vectors.mjs` | regenerates the vectors |

## Protocol concretization

`docs/PROTOCOL.md` is owned by the `:core` lane and leaves some details open. This module picks the
simplest option and pins it in tests:

- `peer_id` uses Crockford base32 (lowercase, unpadded) of `SHA-256(publicKey)[0..16)`.
- Short codes are 5 Crockford symbols plus a checksum symbol, written `XXX-XXX`.
- The E2E handshake is X25519 `hs1`/`hs2`, then `hs3`/`hs4` AEAD confirmations; the key schedule is
  BLAKE2b-512 keyed with the transcript hash over four DH outputs. The one-time pairing secret is mixed
  into the transcript.
- Frames are XChaCha20-Poly1305 with per-direction 64-bit counters, and messages over 32 KiB are chunked
  inside the encryption.
- The relay hello proof is a one-pass X25519 DH between the peer's long-term key and the relay's
  challenge point.

All of this is documented in `test/vectors/README.md` with byte-exact vectors, so `:core` can be checked
against it.

## Licence

MIT, like the rest of PhoneBridge. Dependency licences are recorded in
[../docs/THIRD_PARTY.md](../docs/THIRD_PARTY.md).
