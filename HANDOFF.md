# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 11:46 — bigpickle lane (deepseek-v4.1-flash) — task 05: MCP server, CLI, bot client, API docs

- Branch `pb/mcp-server-cli`, folders `mcp/` (+ `docs/API.md`, `docs/THIRD_PARTY.md`, CLAIMS/HANDOFF).
  Everything else was left alone.
- **Done**
  - `mcp/` TypeScript package (strict, Node 20+, ESM): client library, MCP server, `phonebridge` CLI,
    54 vitest tests, `package-lock.json` for `npm ci`. `node_modules/` and `dist/` are gitignored.
  - Client (`mcp/src/client`): pairing-input parser (short code, `phonebridge://` link, `.pbpair` file,
    raw/wrapped offer JSON, expiry + `device_id` hash check), JSON keystore in `~/.phonebridge`
    (`$PHONEBRIDGE_HOME`/`--home` override), X25519 handshake, XChaCha20-Poly1305 frames with per
    direction counters and 32 KiB chunking, relay WebSocket with cert fingerprint pinning, request/
    response correlation by UUID, per-command timeouts, events, permission-level checks, relay_moved.
  - MCP server: stdio by default, optional streamable HTTP (`--http <port>`, loopback only). Tools:
    `pb_pair, pb_devices, pb_status, pb_screenshot (MCP image, chunk-safe), pb_ui_tree, pb_notifications,
    pb_tap, pb_swipe, pb_type, pb_key, pb_open_app, pb_install_apk, pb_shell, pb_files, pb_relay_move,
    pb_request_control, pb_release_control` (17; `pb_notifications` is the one extra beyond the task list
    because it is a documented View command).
  - CLI: every command (`pair, devices, status, screenshot -o, ui-tree, notifications, tap, long-press,
    swipe, type, key, open-app, install-apk, shell --, files, settings, relay-move, request-control,
    release-control`), global `--device/--home/--relay/--level/--name/--timeout/--json/--insecure-dev`,
    exit codes 0/1/2. Shebang added so the npm bin works on Unix too.
  - `docs/API.md`: JSON API with examples for every command, pairing forms, levels, events, error-code
    table, MCP config snippets for OpenCode V2 (`mcp.servers`), Claude Desktop, Claude Code, generic
    client, CLI reference, library usage. `mcp/README.md` covers build/architecture.
- **Protocol concretization (for :core and relay lanes — please align)**
  - `peer_id` = Crockford base32 (lowercase, unpadded) of `SHA-256(publicKey)[0..16)`; fingerprint =
    first 8 symbols uppercased as `ABCD-EFGH`.
  - Pairing codes: 5 Crockford data symbols + checksum symbol `(v0+…+v4) mod 32`, written `XXX-XXX`
    (a valid example is `7K4-Q9Y`; the docs' `7K4-Q9M` is shape only and fails the checksum by design).
  - Handshake `hs1`/`hs2`/`hs3`/`hs4` exactly as in `mcp/test/vectors/handshake-v1.json`:
    dh1..dh4, `th = SHA-256(domain||flags||e_b||n_b||s_b||e_d||n_d||s_d||SHA-256(secret))`,
    `okm = BLAKE2b-512(key=th) over dh1..dh4`, keys split 0..32/32..64. AEAD = XChaCha20-Poly1305 IETF,
    AAD `PhoneBridge/v1/data`, nonce = 16 zero bytes + BE64 counter; hs3/hs4 use counter 0, data 1+.
  - Relay auth: `hello` → `hello_challenge` (a 32-byte X25519 **public key**) → `hello_proof` =
    `X25519(peer secret, challenge)` → `hello_ok`. The relay verifies with
    `X25519(challenge secret, peer pub)`. The relay may also answer `hello` directly with `hello_ok`.
  - `pair_lookup {code}` → `pair_offer {payload: b64url(offer JSON)}` or `error {code:"bad_code"}`;
    `pair_request` rides the normal request/response frame with `requested_level`; `pair_result` may be
    bare (`{ok,granted_level,device_name}`) or a normal `{id,ok,result}` response.
  - Offer extension: optional `relay_fp` (SHA-256 of the relay cert DER, hex) pins TLS.
  - Messages over 32 KiB are chunked inside the encryption as
    `{t:"chunk",cid,i,n,d}` (base64url slices); reassembly capped at 24 MiB.
- **Left / next**
  - Real-device testing needs the Android app (`pb/android-accessibility`) and the relay
    (`pb/relay`). Against the in-process mock relay+device everything passes, but nothing has been run
    against a real APK or a real relay yet, so `peer_offline`/`bad_proof` interop is unverified.
  - No live TLS relay was available, so cert pinning is only unit-covered via `normalizeFingerprint`
    and code review; add an integration test once the relay lane serves `wss://`.
  - Not done on purpose: auto-reconnect with backoff (the client reconnects on the next call instead),
    streaming `screenshot` frames to disk, `notifications` push into MCP (events are exposed through the
    library and the next tool call).
- **How to test**
  ```sh
  cd mcp && npm ci && npm run build && npm test && npm run typecheck
  node dist/server/index.js --home /tmp/pb      # stdio MCP, 17 tools
  node dist/server/index.js --http 8765         # streamable HTTP at /mcp
  node dist/cli/index.js --help
  ```
  Verified here: fresh `npm ci && npm run build && npm test` green (54 tests), real stdio and HTTP MCP
  clients list 17 tools, CLI devices/status/help behave.
- **Blockers**: none for merging. Cross-lane follow-ups are the relay hello-proof shape and the Android
  device implementation; both are documented above and in `docs/API.md`.

## 2026-11-23 — setup (Grok Bot)
- Repo scaffolded: docs/SPEC.md, docs/PROTOCOL.md, docs/BOT_WORKFLOW.md, Gradle wrapper (9.2.1) with
  `core` and `relay` JVM modules (empty), folders android/, mcp/, desktop/.
- Toolchain on this PC: JDK 25 (Temurin), Node, Git. **No Android SDK** (no ANDROID_HOME, no
  %LOCALAPPDATA%\Android\Sdk), no global Gradle (use `gradlew.bat`). Android modules are included in
  `settings.gradle.kts` only when an SDK is found; until then write the Android code and keep
  JVM-testable logic in plain Kotlin classes with JVM unit tests.
- No GitHub remote yet (gh not logged in). Push steps are skipped until `origin` exists.
