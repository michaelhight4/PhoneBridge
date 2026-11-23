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
