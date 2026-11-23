# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 11:45 — ds2 (deepseek/deepseek-flash) — task 02 relay: landed on `:core`

**Done** (branch `pb/relay`, folder `relay/`):
- Merged `main` (task 01) and removed the relay's temporary duplicates. The relay now uses the shared
  `:core` types end to end: `RelayEnvelope`/`EnvelopeCodec`, `HelloAuth` (challenge proof),
  `X25519`/`X25519KeyPair` (Bouncy Castle), `PeerIds`, `ShortCode`, `B64Url`, `Primitives`.
  Deleted `relay/.../crypto/{X25519,Base32,Codec,PeerIdentity}.kt`, `relay/.../envelope/RelayEnvelope.kt`
  and the Gson helper; the **Gson dependency is gone** (`:core` brings kotlinx-serialization +
  Bouncy Castle as `api()` dependencies, so the relay adds no JSON/crypto library of its own).
- `RelayClient` now takes a `:core` `Identity` (its role goes on the wire) and its callbacks hand out
  `:core` `RelayEnvelope` values; `payload` stays the opaque base64url end-to-end frame.
- Pairing codes are canonicalised with `ShortCode.normalise`/`format` (so O->0, I/L->1, separators and
  case match `:core`); the mailbox stores the offer as a kotlinx `JsonObject` and releases it once. The
  relay still does not enforce the CRC-5 check symbol (documented), so a mistyped code yields
  `code_not_found` rather than a protocol error.
- TLS: a client without a pin now verifies the **host name** as well as the chain (a pinned fingerprint
  replaces it); `relay_pin` documented in `relay/PROTOCOL-RELAY.md` and shown in the README.
- Fixed `docs/THIRD_PARTY.md`: `main` carried a committed merge conflict (`<<<<<<<` markers) in the
  relay section. The file is rewritten with the relay's real dependency set (no Gson) and keeps the
  core and Android sections.
- `:relay:test` green: **33 tests**. Twelve tests were deleted because they duplicated `:core`'s
  RFC 7748 / Crockford coverage; every relay acceptance test remains (two peers exchanging envelopes,
  forged/unauthorised hello, `peer_offline`, rate limit, oversize drop incl. the 16 MiB default,
  mailbox single-use/expiry, `relay_move` A->B with both peers following, token replay/expiry, TLS
  pinning, wrong pin, host name verification, health endpoint).
- `:relay:shadowJar` rebuilt and smoke-tested (wss, printed pin, `/healthz`, persisted cert + identity).

**Left**
- Nothing blocking. `relay/PROTOCOL-RELAY.md` is now descriptive: the message model and proof belong to
  `:core` (`docs/PROTOCOL.md`); the relay adds only `relay_pin` (offer) and `token` (`relay_moved`).
- Android wiring: the app should start the relay when the user picks "this phone" as the host and pass
  a `dataDir` in app storage. Still pending an Android SDK for the APK build.
- Not covered yet: presence events to other peers, sharing a routing table between relay instances, and
  `relay_moved` for peers that are offline at hand-over time.

**How to test**
```bash
gradlew.bat :relay:test                     # 33 in-process tests on loopback, no SDK needed
gradlew.bat :relay:run --args="--port 8443" # standalone, wss + self-signed cert
gradlew.bat :relay:shadowJar                # single-file jar for a VPS
```

**Blockers:** none. No `origin` remote exists, so there is nothing to push yet.

## 2026-11-23 12:41 -0600 - iceman (Iceman Contributor Free) - CI, security and release docs

- **Done:** Added `.github/workflows/ci.yml` with GitHub Actions jobs: JVM build+tests matrix on Temurin 17 and 25 running `./gradlew :core:test :relay:test :android:executor-core:test`; Android `assembleDebug` job using `actions/setup-java`, `gradle/actions/setup-gradle`, and `android-actions/setup-android@v3` with SDK license acceptance and `platforms;android-36` + `build-tools;36.0.0` install (CI has SDK; this PC does not); Node jobs for `mcp` (skips cleanly when `mcp/package.json` is absent) and `tests/conformance` (`npm ci`, `npm test`). No secrets required; workflow permissions set to `contents: read`. Added `SECURITY.md` (threat model: relay untrusted, pairing consent, key storage, kick/unpair, malicious-bot impact by View/Control/Full level, reporting placeholder). Added `CONTRIBUTING.md`. Added `docs/ITCH_PAGE.md` and `docs/USER_GUIDE.md` for non-technical users. Resolved stale merge-conflict markers in `docs/THIRD_PARTY.md` while touching docs.
- **Left:** MCP npm job will run real tests once `mcp/package.json` lands. Android APK build remains pending a locally installed SDK/licence acceptance; CI workflow covers it. Replace `security@phonebridge.dev` and `<GITHUB_URL>` placeholders before publishing.
- **How to test:** Validate workflow YAML: `npx --yes js-yaml .github/workflows/ci.yml`. JVM tests: `.\gradlew.bat :core:test :relay:test :android:executor-core:test`. Conformance: `cd tests/conformance && npm ci && npm test`. Docs reviewed for consistency with `docs/SPEC.md` and `docs/PROTOCOL.md`.
- **Blockers:** No GitHub `origin` remote configured; push skipped. No local Android SDK; `assembleDebug` not run locally.

## 2026-11-23 11:30 -0600 - iceman (Iceman Contributor Free) - JSON Schemas, conformance tests, mock device
- **Done:** Added `protocol/schema/*.schema.json` (draft 2020-12) for envelope, pairing-offer, pair-request, pair-result, request, response, event, and every command args/result in `docs/PROTOCOL.md`. Added `protocol/examples/` with one valid and at least one invalid example per message. Added `tests/conformance` (Node + vitest + ajv + ajv-formats) validating all examples; includes `src/protocol-samples.test.js` that auto-discovers captured JSON samples from `build/protocol-samples/*.json` (also `core|mcp|relay/build/protocol-samples/*.json`) and validates them when present. Added `tests/mock-device` (Node, `ws`) that connects to a relay as a device, completes hello auth (X25519 + HelloProof HMAC-SHA256), answers every command with realistic fake data (generated PNG screenshot, fake home-screen ui_tree, tap/type/key logging), honours permission levels and FIFO control queue with 120s idle auto-release. Added `docs/TESTING.md`.
- **Dev mode / crypto:** Mock device uses clearly marked **plaintext dev mode** by default (route payload is base64url UTF-8 JSON). Encrypted E2E path (Noise XX / X25519+XChaCha20-Poly1305 per PROTOCOL) is not implemented here because tasks 01/05 crypto are not merged; add encrypted path once main has them and keep `--plaintext` as explicit dev flag. Relay hello proof is implemented to interop with pb/relay.
- **Left:** When core/mcp emit `build/protocol-samples/*.json`, conformance will pick them up automatically. When task 01/05 crypto lands, wire encrypted payloads in mock device. Android APK build remains pending SDK.
- **How to test:** `cd tests/conformance && npm ci && npm test` (186 tests pass). `cd tests/mock-device && npm ci && node src/index.js --help` (starts; connect with `node src/index.js --relay ws://127.0.0.1:8787`).
- **Blockers:** No GitHub `origin` remote configured; push skipped. Core/mcp crypto not merged; plaintext dev mode used as documented.

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
- **Relay interop**: core now matches the relay lane's merged wire contract exactly — Crockford base32
  `peer_id`, the `dh-hmac-sha256` `challenge`/`hello` proof over
  `phonebridge/hello/v1|<relay_id>|<relay_pub>|<peer_id>|<nonce>`, `hello_ok` with limits,
  `pair_store`/`pair_stored`/`pair_lookup`/`pair_offer` carrying the offer as a JSON object, and errors
  as a nested `error:{code,message}` object. `HelloAuth` implements both sides of the proof.
- **Docs**: `docs/PROTOCOL.md` rewritten to the exact bytes/labels implemented; `docs/THIRD_PARTY.md`
  merged with the Android and relay lanes (kotlinx.serialization Apache-2.0, Bouncy Castle 1.87
  MIT-style, Gson via the relay, AGP build-only); short-code examples in README/SPEC corrected from
  `7K4-Q9M` to a code that passes its own check symbol (`7K4-Q9B`).
- 111 JVM tests covering every acceptance criterion (JSON round-trips for every message type,
  handshake + both encryption directions, tampered/replayed ciphertext, short-code check symbol and
  normalisation, link/file round-trip, 10-minute expiry + single use with an injected clock,
  permission table, arbiter queue/idle/kickAll, chunk/reassemble, relay proof vectors, app messages
  travelling through a live session).
- **Android API 26 audit**: `JsonFileBotRegistry` used `Files.readString`/`writeString` (Java 11, not
  on Android O); it now reads/writes UTF-8 bytes. Everything else is BC lightweight crypto,
  `java.util.Base64`, `java.nio.file` basics from API 26, or plain Kotlin.
- **Pairing lifecycle fix**: a pending "Allow this bot?" prompt is no longer dropped when the user
  creates another offer, and an unanswered prompt expires after ten minutes instead of lingering.
- **Convenience**: `E2ESession.sealRequest/sealResponse/sealEvent/openAppMessage` carry the typed
  application messages over a live session, so the transport lanes only move bytes.

**Left / for other lanes**

- Relay (02): the wire contract is now implemented in `core` as well. The relay can delete its local
  `crypto/Base32.kt`, `crypto/X25519.kt`, `envelope/RelayEnvelope.kt` and the `HelloProof` copy and call
  `PeerIds`, `HelloAuth`, `RelayEnvelope`/`EnvelopeCodec` from `:core` (the shapes are identical). The
  relay's BigInteger X25519 is not constant time; `core`'s is Bouncy Castle's.
- Android (03): `core` now models the protocol, so replace the local `Request`/`Response`/
  `PermissionLevel`/`ControlArbiter` copies in `android/executor-core` with the `core` types at the
  transport boundary (`Commands.decodeArgs`, `Commands.check`, `ControlArbiter`, `AppMessages`).
  Lane 03's additive extensions are now in core and PROTOCOL.md: `UiNode.visible`, `long_press` with
  `node_id`, `request_control` returning `{granted:false, position}`. Wire `PairingManager` +
  `JsonFileBotRegistry` into the pairing UI and `E2ESession` into the foreground service. The APK build
  is pending an Android SDK (not installed on this PC).
- Bot/MCP (04): drive the documented JSON on top of `AppMessages`; `core/README.md` has copy-paste
  snippets.
- Nice-to-have follow-ups: JSON schema files under `docs/`, negative tests for very large UI trees,
  desktop-side `SecureChannel` concurrency notes.

**How to test**: `gradlew.bat :core:test` (JDK 25 on PATH, no Android SDK needed).

**Blockers**: none. No `origin` remote exists yet, so nothing was pushed (commits only).

## 2026-11-23 11:14–11:35 — ds2 / deepseek/deepseek-flash — task 02 relay server (`:relay`)

**Done (acceptance criteria met, `:relay:test` green: 45 tests)**
- Embeddable relay API in `dev.phonebridge.relay`: `Relay.start(RelayConfig): RelayHandle`, `stop()`,
  `moveTo(newUrl)`, `stats()`, `peers()`; per-connection `RelayClient` for devices, bots and tests.
- Transport: hand-written RFC 6455 server + client (text frames, ping/pong, close, masking), so the
  relay has no heavy dependency and runs on Android API 26+ (see `docs/THIRD_PARTY.md` for why a
  library was not used and what Gson is used for).
- TLS: PKCS#12 keystore or a self-signed P-256 certificate generated on first run and persisted
  (`relay-cert.p12`), fingerprint exposed as base64url + hex for pinning (`relay_pin` in a pairing
  offer, documented in `relay/PROTOCOL-RELAY.md`), clients pin the fingerprint; a client without a pin
  verifies the chain **and** the host name; `--insecure-dev` / `insecureDev` allows plain `ws://` off
  loopback; loopback `ws://` is allowed.
- Peer auth: relay `challenge` (nonce + per-connection X25519 ephemeral key) and `hello` with an
  `dh-hmac-sha256` proof of key ownership; `peer_id` must match the public key; replayed nonce, forged
  proof, all-zero key and spoofed `from` are all rejected.
- Routing: `route` forwarded verbatim by `to` (payload never parsed), `peer_offline` errors,
  `ping`/`pong`, per-peer token bucket rate limit (dropped messages are reported once per second),
  16 MiB envelope cap enforced before buffering (close code 1009), connection cap (512), 30 s hello
  timeout, idle keep-alive pings.
- Short-code mailbox: `pair_store` / `pair_lookup` with canonical Crockford code normalization,
  10 minute TTL, single release, 16 KiB offer cap.
- `relay_move`: old relay emits `relay_moved {new_url, token, expires}` with per-peer one-time HMAC
  tokens (shared `moveSecret`); the new relay accepts each token once (`token_used` on replay) and
  `RelayClient` follows automatically and keeps exchanging messages.
- Standalone: `gradlew.bat :relay:run --args="--port 8443"`, `gradlew.bat :relay:shadowJar` →
  `relay/build/libs/phonebridge-relay-all.jar` (verified: serves `/healthz`, generated cert + pin,
  persisted identity). Both were smoke-tested from the command line.
- Docs: `relay/README.md` (embedding, standalone, config table, security notes),
  `relay/PROTOCOL-RELAY.md` (the concrete relay wire contract), `docs/THIRD_PARTY.md` (Gson
  Apache-2.0; RFC 6455 / RFC 7748 / X.509 implemented from specification).

**Left / for the next lane**
- **Resolved in the 11:45 entry above:** the relay now uses the `:core` envelope/identity types, so the
  old placeholder types in `relay/.../envelope/` are gone.
- **Identity details core must align with:** relay `peer_id` = Crockford base32 (lowercase, 26 chars)
  of the first 16 bytes of SHA-256(public key); fingerprint `ABCD-EFGH`. Relay `hello` proof is
  `HMAC-SHA256(X25519(peer_priv, relay_ephemeral_pub), "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>")`.
- Protocol additions are documented in `relay/PROTOCOL-RELAY.md` and are additive only; `docs/PROTOCOL.md`
  was deliberately not edited (task 01 owns it). Core should adopt `challenge`, `hello_ok`,
  `pair_store`, `pair_lookup`, `pair_offer` and the extra error codes; if the proof format changes, use
  the `proof` field in `challenge` to negotiate.
- The relay's X25519 (`relay/.../crypto/X25519.kt`) is a BigInteger reference implementation: correct
  (RFC 7748 vectors in tests) but **not constant time**. It only touches per-connection ephemeral keys
  and handshake nonces. When `:core` lands a constant-time X25519/Noise implementation, switch the
  relay handshake over to it and delete this copy.
- Android app wiring is not done: `android/` should start the relay when the user picks "this phone"
  as the host, and pass a `dataDir` inside app storage. The APK build is still pending an Android SDK
  on this machine (no `ANDROID_HOME`); the relay itself is plain JVM Kotlin and is covered by tests.
- Not covered yet: presence events (`peer_online`/`peer_offline` notifications to other peers),
  multiple relay instances sharing a routing table, and `relay_moved` for peers that are offline at
  hand-over time (they reconnect to the URL they already know).

**How to test**
```bash
gradlew.bat :relay:test                                        # 45 in-process tests
gradlew.bat :relay:run --args="--port 8443"                    # standalone, wss + self-signed cert
gradlew.bat :relay:shadowJar                                   # single-file jar for a VPS
java -jar relay/build/libs/phonebridge-relay-all.jar --help
```
Tests are on loopback with port 0, so they do not need the SDK, a network, or free fixed ports.

**Blockers:** none. Merged to `main` with `--ff-only` after `:relay:test` and the repo-wide `test`
task were green. No `origin` remote exists, so there is nothing to push yet.

## 2026-11-23 11:30 AM — ds3 — deepseek/deepseek-flash (DeepSeek V4.1 Flash)
Task: Android AccessibilityService + command executor (`android/` only), branch
`pb/android-accessibility`.

**Done**
- `android/executor-core` — new plain Kotlin/JVM module, always included in `settings.gradle.kts`
  (no `android.*` imports, builds and tests on this SDK-less PC):
  - `Request`/`Response`/`ProtocolError`/`ErrorCode` (exact `docs/PROTOCOL.md` codes and shapes) and
    `PermissionLevel` + `BotSession`. `:core` still only has `Version.kt`, so these are small local
    types, not `:core` types; when `:core` lands its protocol classes, convert once at the transport
    boundary (`Request`/`Response` are plain data classes).
  - `Command` table (level, needs-control, needs-accessibility per command), `KeyCommand` mapping to
    the `GLOBAL_ACTION_*` codes, `Args` typed reader (`bad_args` instead of crashes).
  - `CommandExecutor` (order: unknown_cmd → permission_denied → accessibility_disabled →
    not_controller → bad_args → device call; device refusal = `internal`).
  - `ControlArbiter` (one controller, FIFO queue, 120 s idle auto-release, promotion on release).
  - `UiTreeSnapshot` (protocol `Node` export, ids = child-index paths `0`/`0.1`/`0.3.0`, `max_depth`,
    `visible_only`), `OpenAppResolver` (package/label with ambiguity reporting), `SettingsDeepLink`
    constants, `DeviceControl` seam + `FullCommandHandler` for the Full-level commands.
  - 74 JVM tests with a recording `FakeDevice`.
- `android/app` — AGP 9.0.1 application module (`dev.phonebridge.app`, minSdk 26, target/compileSdk
  36), manifest, resources, and:
  - `BridgeAccessibilityService` + `res/xml/accessibility_service_config.xml`
    (`canRetrieveWindowContent`, `flagReportViewIds`, `canPerformGestures`, `canTakeScreenshot`, no
    `flagRequestFilterKeyEvents`); UI tree → `UiNode`; tap/long_press/swipe via `dispatchGesture`;
    `type` via `ACTION_SET_TEXT` + clipboard-paste fallback; `key` via `performGlobalAction`;
    `open_app` via PackageManager launch intents; screenshot via `takeScreenshot` on API 30+.
  - `AndroidDeviceControl` (the `DeviceControl` implementation), `BridgeRuntime` (single
    `CommandExecutor` + arbiter wired in `BridgeApp`), `AccessibilityBridge` (live service ref),
    `SettingsLinks` (accessibility settings deep link + "is it enabled" check), `MainActivity`
    (on/off state, deep-link button, "test screen read" diagnostic).
  - Build wiring: `pluginManagement { google(); mavenCentral(); gradlePluginPortal() }` and the AGP
    version in `settings.gradle.kts`; AGP declared `apply false` in the root build.

**Left / next**
- **`gradlew.bat :android:app:assembleDebug` is pending an Android SDK** (and licence acceptance)
  on this PC. Not installed on purpose. `:android:app:tasks` configures fine, and `assembleDebug`
  stops only at "accept the SDK licences / install build-tools 36 + platform 36". Expect the usual
  first-build fixes for the app module (AGP 9 DSL details, any lint NewApi hits) once an SDK exists.
- `key: enter` is not implemented: the accessibility APIs cannot inject a key or IME-enter, so it
  returns `internal` from the device. Needs the Full-level Shizuku path (`input keyevent 66`).
- `notifications` returns an empty list until a NotificationListenerService task lands.
- `screenshot` below API 30 needs MediaProjection (answers `projection_denied` today).
- Full-level commands (`install_apk`, `shell`, `files`, `settings`) answer `shizuku_unavailable`,
  `relay_move` answers `internal`. `CommandExecutor` accepts a `FullCommandHandler` so the Shizuku
  and relay lanes can plug in without touching the dispatcher.
- `open_app` launches the package but does not wait for it to be foreground; a follow-up may wait for
  the window state event before answering.

**Protocol notes (additive only, `docs/PROTOCOL.md` untouched because the core lane owns it)**
- `Node` includes an extra `visible` boolean.
- `long_press` also accepts `node_id` (like `tap`); node-centred long-press.
- `request_control` queued → `{granted:false, position:<n>}` in the result, in addition to the
  `queue_position` event.
- `screenshot` result shape unchanged (`mime`, `width`, `height`, `data_b64`).

**How to test**
- `gradlew.bat :android:executor-core:test` (74 tests: permission denials, not_controller,
  accessibility_disabled, bad_args, each command routed to the fake device, node-id → centre mapping,
  control queue/idle timeout, app-label resolution).
- `gradlew.bat :core:test` must stay green (it does).
- `gradlew.bat :android:app:tasks` configures the Android module without an SDK.

**Blockers**
- Android SDK + accepted licences to build the APK (user decision, not installed by this bot).
- Transport/relay lane: `BridgeRuntime.handle(session, request)` is the seam to call.

## 2026-11-23 — setup (Grok Bot)
- Repo scaffolded: docs/SPEC.md, docs/PROTOCOL.md, docs/BOT_WORKFLOW.md, Gradle wrapper (9.2.1) with
  `core` and `relay` JVM modules (empty), folders android/, mcp/, desktop/.
- Toolchain on this PC: JDK 25 (Temurin), Node, Git. **No Android SDK** (no ANDROID_HOME, no
  %LOCALAPPDATA%\Android\Sdk), no global Gradle (use `gradlew.bat`). Android modules are included in
  `settings.gradle.kts` only when an SDK is found; until then write the Android code and keep
  JVM-testable logic in plain Kotlin classes with JVM unit tests.
- No GitHub remote yet (gh not logged in). Push steps are skipped until `origin` exists.
