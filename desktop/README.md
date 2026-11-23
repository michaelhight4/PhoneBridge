# Desktop agent (phase 2, Windows first)

A Kotlin/JVM Gradle module (`:desktop`) that lets a user's own paired bot see and control a Windows PC
through the same end-to-end-encrypted PhoneBridge protocol as the phone app. Nothing central: the bot
connects either to the relay ("the heart") running on this PC or to a relay the user picks.

```
gradlew.bat :desktop:test                                     # 92 JVM tests, no display needed
gradlew.bat :desktop:run                                      # the tray app
gradlew.bat :desktop:run --args="--smoke-ms 6000"             # start, prove it is healthy, quit
gradlew.bat :desktop:run --args="--data-dir D:\pb --no-window" # different data dir, tray only
```

`--help` lists every option. The app keeps its identity, bot list, settings, relay certificate and
activity log in `%APPDATA%\PhoneBridge` by default.

## What the user gets

- **Tray app** (Swing, no Compose dependency): the menu opens the window, pairing, the bot list, the
  activity log, toggles the indicator, kicks everyone and quits. Controller changes show as a tray
  balloon (Windows toast) and are always visible in the tray tooltip.
- **Always-visible indicator**: a small always-on-top strip that names the bot in control, with an X
  to hide it and Esc to dismiss; clicking it opens the window.
- **Pairing**, all three ways: a QR code, the short code (`7K4-Q9B`), the `phonebridge://pair?` link,
  and a saved `.pbpair` file. Offers are one-time and expire after ten minutes; the short code is
  published to the relay mailbox so a bot can look it up, and the offer carries the relay's
  certificate pin (`relay_pin`) so a self-signed heart is reachable.
- **"Allow this bot?"**: the request's name, fingerprint and requested level; the user can only lower
  the level. Approving stores the bot; denying burns the offer.
- **Bot list** like Bluetooth devices: name, fingerprint, permission level, on/off, last seen, unpair,
  and **Kick everyone** (revokes the controller, empties the queue and closes live sessions; pairings
  are kept).
- **Activity log** of every command per bot, newest first, persisted as `activity.v1.json`. It never
  stores typed text, shell command bodies or file contents - only sizes, paths and outcomes.

## What a bot can do

Permission levels are ordered **View < Control < Full** and every command is checked by the executor
before the PC is touched. Commands that change the PC also require the caller to hold control (one bot
drives at a time; a second bot queues FIFO and gets `busy`/`not_controller`), and control
auto-releases after 120 s without activity.

| Level | Core protocol commands (`docs/PROTOCOL.md`) | Desktop extensions |
| --- | --- | --- |
| View | `status`, `screenshot`, `notifications` (empty on the desktop) | `screens`, `screen_stream` |
| Control | `tap`, `long_press`, `swipe`, `type`, `key`, `open_app`, `request_control`, `release_control` | `move`, `drag`, `scroll`, `key_combo` |
| Full | `shell`, `files`, `relay_move` | `install` |

`ui_tree`, `settings` and `install_apk` are Android concepts and answer `unknown_cmd` with a message
saying so.

### Screens and screenshots

- `screens` returns `{screens:[{id,x,y,width,height,primary}]}` in virtual-screen coordinates, so a bot
  can target a second monitor.
- `screenshot` accepts the documented `format`, `quality`, `max_width` and an optional `screen_id`
  (unknown fields are ignored by older peers, so this is additive). Without `screen_id` the whole
  virtual desktop is captured, all monitors stitched together. Images up to 2 MiB come back inline;
  bigger ones are chunked into `chunk` events exactly as `docs/PROTOCOL.md` section 8 describes.
- `screen_stream` `{fps?, frames?, quality?, max_width?, format?, screen_id?}` captures frames and
  sends each one as a `frame` event:
  `{stream_id, index, total, mime, width, height, data_b64}`. `fps` is 1..30, `frames` 1..300,
  `quality` 1..100. The result summarises `{stream_id, frames, width, height, mime, stopped_early}`.
  Frames are only produced while the bot keeps the session open.

### Mouse and keyboard

- `tap {x,y}` clicks, `long_press {x,y,ms?}` holds the button (default 1000 ms), `swipe {x1,y1,x2,y2,ms?}`
  drags (default 300 ms). `move {x,y}` moves without clicking, `drag` is the same drag with an explicit
  `ms`, and `scroll {x?,y?,dx,dy}` uses wheel steps (-50..50; `dx` is Shift+wheel).
- `type {text}` pastes through the clipboard and restores the previous clipboard text afterwards, so
  any Unicode arrives intact.
- `key_combo {keys:["ctrl","shift","s"]}` presses named keys (1..8 names: modifiers plus exactly one
  key; letters, digits, F1-F24 and the usual named keys).
- The protocol's `key` values map to Windows shortcuts:

  | `key` | Desktop action |
  | --- | --- |
  | `enter` | Enter |
  | `back` | Alt+Left |
  | `home` | Win+D (show desktop) |
  | `recents` | Alt+Tab |
  | `notifications` | Win+N |
  | `quick_settings` | Win+A |
  | `lock` | Win+L |
  | `power_dialog` | Win+X |

### Shell, files and installs (Full)

- `shell {command, timeout_ms?, shell?}` runs **PowerShell** (`-NoProfile -NonInteractive`) by default;
  pass `"shell":"cmd"` for `cmd.exe`. Console output is forced to UTF-8; `timeout_ms` defaults to 60 s
  and is capped at one hour. The result is the documented `{exit, stdout, stderr}`; a command killed at
  the deadline answers `timeout`. stdout is capped at 8 MiB and stderr at 256 KiB.
- `files {op, path, data_b64?}` lists (max 2000 entries, non-recursive), reads (max 4 MiB), writes
  (max 8 MiB, creates parent folders) and deletes. Relative paths resolve against the user's home
  directory. The result shapes are `core`'s `FilesResult`.
- `install {id | path | url, name?, silent?}`:
  - `id`: `winget install --id <id> --exact --silent --accept-package-agreements --accept-source-agreements --disable-interactivity`
    (20 minute deadline),
  - `path`: a local `.msi` (`msiexec /i <path> /qn /norestart`) or `.exe` (launched for the user,
    because most installers need a window),
  - `url`: an **https** download (512 MiB cap) of an `.msi`/`.exe`, then treated like `path`.
  Answers `{ok, method, detail, exit?, started}`.

## Pairing and the heart

- The default is **this PC hosts the heart**: the embedded `:relay` listens on the LAN (port 8443, or an
  ephemeral port when 8443 is busy) with a self-signed certificate. Its fingerprint goes into every
  pairing offer, and the PC's own client dials the heart over loopback with the pin.
- **A custom relay** (`wss://...`, with an optional certificate pin) is the other choice; plain `ws://`
  is only allowed on loopback. The choice is saved in `desktop.v1.json`.
- `relay_move` works both ways:
  - `{to:"this"}` starts the heart here, answers `{new_url}` and sends `relay_moved` to live bots;
  - `{to:"wss://other:8443"}` hands the heart over: the local relay tells every connected peer to
    reconnect to the new URL with a one-time token (the new relay must share the move secret);
  - `{to:"<peer_id>"}` is not supported yet and answers an error.
- The move secret is kept in `move-secret.txt` in the data folder so a heart restarted on this PC keeps
  accepting hand-over tokens from a relay that shares it.

## How it is built

| Area | Where | Notes |
| --- | --- | --- |
| Command routing, permissions, control, extensions | `executor/` | Plain Kotlin, tested with `FakeDesktopDevice` |
| Screens, input, shell, files, installer | `device/` | AWT `Robot`, `ProcessBuilder`, `java.nio`, `HttpClient`; every touch behind a seam |
| Streaming | `device/ScreenStreamer.kt` | Capped fps, injected clock/sleep, tested without screens |
| Transport and pairing | `session/`, `app/` | `:core` Noise `XX`, `PairingManager`, short codes, `:relay` `RelayClient`/`Relay` |
| UI | `ui/` | Swing tray, main window, indicator, QR (ZXing core) |
| Wiring | `DesktopBridge`, `DesktopApp`, `Main.kt` | One object per surface; nothing in the executor knows about Swing |

The whole command path is covered by a full-stack test (`DesktopRelayIntegrationTest`) that pairs a bot
by short code through a real embedded relay, completes a real Noise handshake and gets a `status`
answer - no phone, display or network needed.

## Platform notes

- Windows first. Everything except `AwtScreenSource`, `RobotInput`, `ShellRunner`'s command wrapper and
  the installer commands is OS-neutral; macOS/Linux need those four seams re-implemented.
- Screen capture and input need an interactive desktop session (the app runs in the user's session;
  a Windows service or a locked screen cannot capture pixels).
- `images` are encoded with `javax.imageio` (PNG/JPEG), so no extra image library is shipped.
- Running as a different user or elevated process may need to match the interactive session for
  input injection to work; that is a Windows rule, not a PhoneBridge one.

## Safety

Only pair bots you trust, use the lowest level that works, keep "Kick everyone" handy, and watch the
activity log. Full level means the bot can run commands, read and write files and install software as
you - exactly like the phone's Full level, but with a desktop's reach.
