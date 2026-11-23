# PhoneBridge Product Spec (v1 draft)

## Goal
An MIT-licensed, open-source project that lets any user's AI bot see and control **their own**
Android phone (v1) and later **their own** desktop (Windows first, then Mac/Linux), from anywhere.
Published on GitHub and itch.io.

## Non-goals / hard rules
- No shared central server, no master key, no telemetry. Nobody (including the authors) can reach
  someone else's device.
- Nothing happens without on-device consent: pairing always needs an on-device "Allow this bot?" tap.
- The device always shows who is in control (persistent notification) and has an off switch.

## Components

### 1. Android app (v1) — Kotlin, minSdk 26, Gradle (`android/`)
- **AccessibilityService**: read the screen as a UI tree, tap, long-press, swipe, type text,
  global actions (back, home, recents, notifications, quick settings, lock screen, screenshot on API 30+).
- **MediaProjection screenshots** (PNG/JPEG, scaled) for devices/APIs where accessibility screenshots
  are unavailable or a bot needs a pixel image.
- **Shizuku integration** (Full level only): install/uninstall APKs, change settings
  (`settings put ...`), run shell commands as the shell user. Shizuku must be restarted by the user
  after a reboot; the app detects this and tells the bot `shizuku_unavailable`.
- **Foreground service** keeping an outbound connection: WebSocket over TLS to the relay, with
  end-to-end encryption on top (Noise `XX`/`IK` pattern, or libsodium-style X25519 + XChaCha20-Poly1305).
  Works over mobile data or Wi-Fi, reconnects with backoff, survives Doze with a foreground notification.
- **Command executor** mapping API commands to Accessibility/MediaProjection/Shizuku calls, enforcing
  per-bot permissions.
- **UI**: pairing screen (QR, short code, share link/file), bot list, per-bot permission picker,
  activity log, relay host choice, "kick everyone" button.

### 2. Relay, "the heart" (`relay/`)
- A small relay server **built into both the desktop app and the phone app**. It beats only on the
  one device the user (or a bot, with Full permission) chooses.
- Moving it is a command (`relay_move`), e.g. before a business trip: "run the heart on my phone".
  The relay hands over its routing table and tells peers the new address; devices reconnect.
- If its host is off, remote access stops. That is a feature.
- Optional tiny standalone relay (single jar / binary) for a VPS for users who want always-on.
- The relay only forwards encrypted envelopes between paired peers; it never sees plaintext commands
  or screenshots. It authenticates peers by public key and rate-limits.

### 3. Pairing
- Three equivalent ways:
  - **QR code** shown on the device.
  - **Short code** like `7K4-Q9M` (Crockford base32, 6 chars + check), entered into / pasted to the bot.
  - **Link** `phonebridge://pair?...` or an exported **pairing file** (`.pbpair` JSON) the user sends
    to their bot in chat.
- Pairing secrets are **one-time** and **expire after 10 minutes**.
- After the bot connects with the secret, the device shows **"Allow this bot?"** with the bot's
  self-reported name and key fingerprint. Only then are long-term per-bot keys stored.

### 4. Multiple bots
- A list like Bluetooth devices: name, key fingerprint, last seen, on/off switch, unpair.
- Per-bot keys; unpairing one bot never affects the others.
- Permission levels per bot:
  - **View**: screenshots, UI tree, notifications.
  - **Control**: View + tap, swipe, type, key, open apps.
  - **Full**: Control + install APKs, settings, shell, files, relay_move.
- **One bot drives at a time**; others queue (FIFO) and get `busy` with their queue position.
  View-level reads may be allowed concurrently.
- Persistent notification names the bot in control. Activity log of every command per bot.
  **"Kick everyone"** button revokes all live sessions instantly.

### 5. Bot-agnostic API + MCP server (`mcp/`)
- Documented JSON API (see PROTOCOL.md): `pair`, `screenshot`, `ui_tree`, `tap`, `swipe`, `type`,
  `key`, `open_app`, `install_apk`, `shell`, `files`, `relay_move` (+ `notifications`, `status`).
- MCP server (TypeScript) wrapping it, so any MCP-capable agent can use PhoneBridge with no custom code.
- CLI test client for scripts and for bots that can only run shell commands.
- Bots that can only chat (no tools, no HTTP) cannot drive it.

### 6. Desktop agent (phase 2, `desktop/`)
- Same protocol, pairing, bot list, permissions, notifications, log and embedded relay.
- Screen streaming and screenshots, mouse and keyboard.
- At Full level: run commands, manage files, install programs.
- Windows first, then Mac and Linux.

## Bot failover rule (development process)
A bot that hasn't responded in **2 hours** is assumed to be out of usage. Its unfinished task is
rerouted to another bot, or the work moves to other unblocked areas; work stops only if nothing can
progress. Details in [BOT_WORKFLOW.md](BOT_WORKFLOW.md).

## v1 definition of done
- `core` + `relay` build and pass tests on the JVM.
- Android app builds an APK (requires Android SDK), pairs with a bot through the relay via all three
  pairing methods, and executes screenshot / ui_tree / tap / swipe / type / key / open_app with
  permission checks, notifications and activity log.
- MCP server + CLI can pair and drive the phone end to end.
