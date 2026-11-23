# Android app (Kotlin, minSdk 26) — see docs/SPEC.md

Two Gradle projects:

| Project | What | Builds without an Android SDK? |
| --- | --- | --- |
| `:android:executor-core` (`android/executor-core`) | Plain Kotlin/JVM: protocol `Request`/`Response`, permission levels, control arbitration, argument parsing, UI-tree node ids, `DeviceControl` seam, settings deep-link constants. | Yes — `gradlew.bat :android:executor-core:test` |
| `:android:app` (`android/app`) | The APK: `BridgeAccessibilityService`, `AndroidDeviceControl`, `BridgeRuntime`, settings deep link, first-run screen. | No — needs an SDK; included only when one is found |

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
| `status` | Model, battery %, screen on, controller and queue from the arbiter. `shizuku` is always `false` for now. |
| `notifications` | Returns an empty list until the NotificationListenerService task lands. |

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
