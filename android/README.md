# Android app (Kotlin, minSdk 26) — see docs/SPEC.md

Three Gradle projects:

| Project | What | Builds without an Android SDK? |
| --- | --- | --- |
| `:android:executor-core` (`android/executor-core`) | Plain Kotlin/JVM: protocol `Request`/`Response`, permission levels, control arbitration, argument parsing, UI-tree node ids, `DeviceControl` seam, settings deep-link constants. | Yes — `gradlew.bat :android:executor-core:test` |
| `:android:ui-core` (`android/ui-core`) | Plain Kotlin/JVM: pairing countdown/expiry, approve/deny flow, paired-bot list state, activity log (with a JSON-file store), relay host choice, onboarding checklist, notification text, `:core` wire ↔ executor conversion. | Yes — `gradlew.bat :android:ui-core:test` |
| `:android:app` (`android/app`) | The APK: `BridgeService` (foreground, relay client + embedded relay), `BridgeAccessibilityService`, `AndroidDeviceControl`, pairing and approval screens, bot list, activity log, relay screen, onboarding. | No — needs an SDK; included only when one is found |

`settings.gradle.kts` includes `:android:app` only when `ANDROID_HOME`, `ANDROID_SDK_ROOT` or
`sdk.dir` in `local.properties` is present; `:android:executor-core` and `:android:ui-core` are always
included.

## Screens

| Screen | What it does |
| --- | --- |
| `MainActivity` | Start/stop the bridge, accessibility state, who is in control, doors to the other screens, and a "test screen read" diagnostic. |
| `PairingActivity` | One live offer: QR (ZXing core, Apache-2.0), short code, Share link, Export `.pbpair` through the system share sheet, Regenerate, countdown to the 10-minute expiry. |
| `ApprovalActivity` | Full-screen "Allow this bot?" from the high-priority notification: name, fingerprint, requested level, a picker that can only lower it, Allow/Deny. |
| `BotListActivity` | Bluetooth-style list: name, fingerprint, last seen, connection/control state, on/off switch, level picker, unpair, "Kick everyone". |
| `ActivityLogActivity` | Per-bot command log with time and result, filterable per bot, clear. Typed text and shell commands are never copied into the log. |
| `RelayActivity` | Where the heart beats (this phone / my PC / custom URL, certificate pin) and "Move heart here" (`relay_move`). |
| `OnboardingActivity` | Accessibility enabled, notifications permission, battery-optimisation exemption. |

## Bridge service (this build)

`BridgeService` is a foreground service (`connectedDevice|specialUse`) that:

- loads this phone's X25519 identity (`identity.v1.json`, generated on first run), the paired-bot
  registry (`bots.v1.json`, `:core` `JsonFileBotRegistry`) and the activity log (`activity.v1.json`),
- starts the embedded relay when this phone is the chosen heart (`port = 0`, self-signed TLS,
  advertised on the LAN IPv4 address),
- keeps the outbound `:relay` `RelayClient` connected to the chosen relay over Wi-Fi or mobile data,
  following `relay_moved` and reconnecting with the client's exponential backoff,
- completes the Noise `XX` handshake as responder for each paired bot and pins the bot's public key,
- hands decrypted `AppRequest`s to `BridgeRuntime.handle` (the accessibility executor) and sends the
  sealed response back through the relay,
- posts the persistent "who is in control" notification with **Stop** and **Kick everyone**, and a
  high-priority full-screen "Allow this bot?" notification for pending pairings,
- serves `relay_move` (`{to:"this"}` or a `wss://` URL) from the Full-level command handler.

The relay and the app share `:core`'s envelope, identity and `HelloAuth` types, so there is no type
conversion at the service boundary. The relay client's `payload` stays opaque base64url ciphertext.

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
gradlew.bat :android:executor-core:test   # executor logic, no SDK needed
gradlew.bat :android:ui-core:test         # pairing/approval/bot-list/log/notification logic, no SDK needed
gradlew.bat :android:app:assembleDebug    # needs an Android SDK + accepted licences
```

The APK build is pending an Android SDK on this machine; do not install one on the user's behalf.
