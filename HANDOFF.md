# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

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
