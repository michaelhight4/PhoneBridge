# Android app (Kotlin, minSdk 26) — see docs/SPEC.md

Two Gradle projects:

| Project | What | Builds without an Android SDK? |
| --- | --- | --- |
| `:android:executor-core` (`android/executor-core`) | Plain Kotlin/JVM: protocol `Request`/`Response`, permission levels, control arbitration, argument parsing, UI-tree node ids, `DeviceControl` seam, the Shizuku command layer (`ShizukuShell`, `ShellRunner`, `ShellQuote`, `ShellFiles`, `ShellApkInstaller`, `ShizukuFullCommandHandler`), settings deep-link constants. | Yes — `gradlew.bat :android:executor-core:test` |
| `:android:app` (`android/app`) | The APK: `BridgeAccessibilityService`, `AndroidDeviceControl`, `BridgeRuntime`, `ShizukuBridge`, `ShizukuPermissionActivity`, settings deep link, first-run screen. | No — needs an SDK; included only when one is found |

`settings.gradle.kts` includes `:android:app` only when `ANDROID_HOME`, `ANDROID_SDK_ROOT` or
`sdk.dir` in `local.properties` is present; `:android:executor-core` is always included.

## Device control (this build)

| Command | Implementation |
| --- | --- |
| `ui_tree` | Walks `rootInActiveWindow` into `UiNode`, ids are child-index paths (`0`, `0.1`, `0.3.0`) so they survive `visible_only` / `max_depth` filters. |
| `tap`, `long_press`, `swipe` | `dispatchGesture` with a one-point path (press) or a line (swipe). `tap`/`long_press` accept `node_id` and tap the node centre. |
| `type` | `ACTION_SET_TEXT` on the given editable node or the focused input, clipboard + `ACTION_PASTE` fallback. |
| `key` | `performGlobalAction` for back/home/recents/notifications/quick_settings/power_dialog and lock (API 28+). `enter` is not available from the accessibility APIs — it needs the Full-level Shizuku `input keyevent 66` path (later task). |
| `open_app` | `{package}` or `{label}`; labels resolve against launcher activities via `PackageManager`, and an ambiguous label is reported instead of guessed. |
| `screenshot` | Accessibility `takeScreenshot` on API 30+ (PNG/JPEG, `quality`, `max_width`). Below API 30 the app answers `projection_denied` until MediaProjection is wired in. |
| `status` | Model, battery %, screen on, controller and queue from the arbiter. `shizuku` is true only while the Shizuku helper is running and allowed. |
| `notifications` | Returns an empty list until the NotificationListenerService task lands. |
| `install_apk`, `shell`, `settings`, `files` | Full level **and** holding control, through Shizuku as the shell user (see below). `shizuku_unavailable` when Shizuku is not running, with the restart hint. |

## Shizuku (Full level)

Shizuku runs a small helper with the ADB/root identity; PhoneBridge asks it to run one-shot shell
commands. `android/executor-core` holds all the logic that can be tested without a device:

| Piece | What |
| --- | --- |
| `ShizukuState` | `ready` / `not_running` / `not_installed` / `no_permission` / `unsupported`, each with the user-facing hint the bot's `shizuku_unavailable` error carries. |
| `ShizukuShell` | Seam: `exec("sh -c script", timeoutMs, stdin)` and the current state. The app implements it with `Shizuku.newProcess`; tests use `FakeShizukuShell`. |
| `ShellRunner` | Reads stdout/stderr (8 MiB / 256 KiB caps), writes stdin, kills the process at the deadline, reports `timedOut`. |
| `ShellQuote` | Single-quote escaping plus validation for paths, settings keys/values, shell commands and APK split names. |
| `ShellFiles` | `files list/read/write/delete` as `stat` / `head -c` / `cat >` / `rm -rf`; `mtime` is epoch milliseconds and `list` caps at 2000 entries. |
| `ShellApkInstaller` | `pm install-create -S <size>` → `pm install-write -S <size> <session> base.apk` (APK on stdin) → `pm install-commit`, abandoning the session on failure. The package name comes from the APK manifest before the install starts. |
| `ShizukuFullCommandHandler` | Argument validation, `settings get/put`, `shell`, `files`, `install_apk`, error mapping. |

`ShizukuBridge` (app) reports the live state, runs the permission request and opens the Shizuku app;
`ShizukuPermissionActivity` is the screen that says what is wrong (not installed / not running /
not allowed) and fixes it. Android stops Shizuku at every reboot, so the "start it again" path is the
common one: every Full command answers `shizuku_unavailable` with that hint while it is down.

`install_apk` accepts an `https://` URL (redirects only to other HTTPS URLs, 256 MiB cap) or inline
`data_b64` + `name`. APKs are streamed into a PackageInstaller session through `pm`; nothing is
written to shared storage.

Protocol notes for this build (additions only, nothing renamed): the `Node` object carries an extra
`visible` boolean, `long_press` also accepts `node_id`, and `request_control` answered "queued" returns
`{granted:false, position:<n>}` so the bot does not need the separate `queue_position` event to know
where it is.

## Build

```
gradlew.bat :android:executor-core:test   # logic tests, no SDK needed
gradlew.bat :android:app:assembleDebug    # needs an Android SDK + accepted licences
```

The APK build is pending an Android SDK on this machine; do not install one on the user's behalf.
