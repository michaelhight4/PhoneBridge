# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 11:26 — ds1 (deepseek-flash / DeepSeek V4.1 Flash) — task 01: protocol, crypto, pairing (`core/`)

**Done** (branch `pb/protocol-pairing`, all in `core/` plus `docs/PROTOCOL.md`, `docs/THIRD_PARTY.md`):

- **Message model** for everything in PROTOCOL.md: `RelayEnvelope` (+hello/route/ping/pong/error/
  relay_moved/pair_lookup/pair_offer), `PairingOffer`/`PairRequest`/`PairResult`, typed app
  `AppRequest`/`AppResponse`/`AppEvent`, all 17 commands with typed args, typed results, error codes,
  `PermissionLevel` (VIEW < CONTROL < FULL) and the `CommandType`/`Commands.requiredLevel` table.
  kotlinx.serialization plugin added to `core/build.gradle.kts`; one shared `PbJson` config.
- **Identities**: X25519 key pairs, `peer_id` = lowercase base32(SHA-256(pub)[0..16)) = 26 chars,
  fingerprint = first 8 chars as `ABCD-EFGH`; stored identity JSON with key/public mismatch rejection.
- **E2E**: real Noise `XX` (`Noise_XX_25519_ChaChaPoly_SHA256`, rev 34) implemented on Bouncy Castle
  primitives so it runs on Android API 26+: per-direction keys, little-endian 64-bit counters, strict
  in-order processing (replays/reordering/tampering rejected without advancing the counter), prologue
  `PhoneBridge/1/noise-xx`, 1-byte frame tags (0x01 handshake / 0x02 transport), key pinning helper.
- **Pairing**: 32-byte one-time secret, 10-minute expiry, burn-on-use/burn-on-deny, `PairingManager`
  (`createOffer`/`accept` -> `PendingApproval`/`approve(level)`/`deny`), sealed request/result with
  HKDF-SHA256 + ChaCha20-Poly1305, direction + device-key AAD, bot-side `PairingClient`, short code
  `7K4-Q9B` (Crockford base32, 5 data + CRC-5 check symbol, O→0 I/L→1 normalisation),
  `phonebridge://pair?d=` links, `.pbpair` files, QR payload = link, `BotRegistry` with in-memory and
  atomic JSON-file implementations.
- **Control arbiter**: one controller, FIFO queue with positions, 120 s idle auto-release with
  promotion, `touch`, `kickAll`, state callback.
- **Chunking**: 256 KiB chunks (1 MiB ceiling), 64 MiB payload cap, `chunk` events with whole-payload
  SHA-256, out-of-order reassembly, duplicate/gap/oversize rejection; sizes proven well under the
  8 MiB transport and 16 MiB relay caps.
- **Relay hello proof**: documented DH-MAC challenge (`relay_pub` + `nonce`) so a peer proves ownership
  of its X25519 key without signatures; `HelloAuth` implements both sides.
- **Docs**: `docs/PROTOCOL.md` rewritten to the exact bytes/labels implemented; `docs/THIRD_PARTY.md`
  added (kotlinx.serialization Apache-2.0, Bouncy Castle 1.87 MIT-style); short-code examples in
  README/SPEC corrected from `7K4-Q9M` to a code that passes its own check symbol (`7K4-Q9B`).
- 104 JVM tests covering every acceptance criterion (JSON round-trips for every message type,
  handshake + both encryption directions, tampered/replayed ciphertext, short-code check symbol and
  normalisation, link/file round-trip, 10-minute expiry + single use with an injected clock,
  permission table, arbiter queue/idle/kickAll, chunk/reassemble).

**Left / for other lanes**

- Relay (02): implement `hello` with `relay_pub` + `nonce` and verify `HelloAuth.proof`, then route
  `pair_lookup`/`pair_offer` and the sealed `pair_request`/`pair_result` route envelopes.
- Android (03): wire `PairingManager` + `JsonFileBotRegistry` into the pairing UI, `E2ESession` into the
  foreground service, `ControlArbiter` + `Commands.check` into the command executor. The APK build is
  pending an Android SDK (not installed on this PC); no Android code was written by this task.
- Bot/MCP (04): drive the documented JSON on top of `AppMessages`; `core/README.md` has copy-paste
  snippets.
- Nice-to-have follow-ups: JSON schema files under `docs/`, negative tests for very large UI trees,
  desktop-side `SecureChannel` concurrency notes.

**How to test**: `gradlew.bat :core:test` (JDK 25 on PATH, no Android SDK needed).

**Blockers**: none. No `origin` remote exists yet, so nothing was pushed (commits only).
