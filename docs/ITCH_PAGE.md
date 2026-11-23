# PhoneBridge — itch.io page text

> Draft copy for the itch.io project page. Replace placeholders like `<GITHUB_URL>` before publishing.

## Short description

Control your own Android phone from your own AI bot — end-to-end encrypted, no central server, with on-device consent for every bot.

## About

**PhoneBridge** is a free, open-source (MIT) project that lets your own AI bot see and control your own Android phone from anywhere. It is built for people who want agent automation without handing their device to a central service.

PhoneBridge is **bot-agnostic**: it exposes a documented JSON API and an MCP server, so any capable agent (OpenCode, Claude-style tool agents, scripts, Home Assistant, and other MCP-capable tools) can use it.

## How it stays in your control

- **No central server, no master key.** The relay ("the heart") runs on a device you choose: your PC, your phone, or an optional small VPS relay you control. If that host is off, remote access stops.
- **End-to-end encrypted.** The relay forwards encrypted envelopes; it does not read your commands or screenshots.
- **Explicit pairing.** Every bot must be approved on-device with an "Allow this bot?" prompt. Pair using a QR code, a short code, a `phonebridge://pair?...` link, or a `.pbpair` file.
- **Per-bot permissions.** Give each bot View, Control, or Full access. You can see who is connected, review activity, unpair a bot, or hit **Kick everyone**.
- **One bot drives at a time.** The device shows who is in control with a persistent notification.

## Safety warning

PhoneBridge gives a bot real control of a device. **Only pair bots you trust.**

- Use the lowest permission level that works.
- **View** can see screenshots, UI trees, and notifications.
- **Control** can tap, swipe, type, and open apps.
- **Full** can install APKs, run shell commands, manage files/settings, and move the relay. Treat Full access as high-risk.
- Watch the activity log and use **Kick everyone** if anything looks wrong.

There is no backdoor and no shared server. You are responsible for the bots you pair and the host running the relay.

## Install (Android v1, pre-alpha)

> PhoneBridge is pre-alpha. Expect rough edges and verify builds before installing.

1. **Get the APK**
   - Download the latest Android debug/release APK from the GitHub Releases page: `<GITHUB_URL>/releases`
   - Or download the `assembleDebug` artifact from GitHub Actions if you are testing a development build.
2. **Install on Android**
   - Open the APK on your phone and allow installation from the source if Android asks.
   - You may need to confirm installing an app from outside the Play Store.
3. **Enable required access**
   - Open PhoneBridge and enable the Accessibility Service when prompted.
   - Grant screenshot/screen capture permission when requested.
   - For Full-level features, install and start Shizuku if you want shell/APK/settings capabilities. Shizuku must be restarted after reboot.
4. **Choose where the relay runs**
   - Pick the device that will host the relay ("heart"). It can be your desktop or your phone.
   - Keep that host online when you want remote access.
5. **Pair your bot**
   - In PhoneBridge, create a pairing QR/code/link/file.
   - Give it to your bot through the MCP server or CLI.
   - Confirm the on-device "Allow this bot?" prompt and choose the permission level.

## Links

- GitHub: `<GITHUB_URL>`
- Spec: `<GITHUB_URL>/blob/main/docs/SPEC.md`
- Protocol: `<GITHUB_URL>/blob/main/docs/PROTOCOL.md`
- User guide: `<GITHUB_URL>/blob/main/docs/USER_GUIDE.md`
- Security: `<GITHUB_URL>/blob/main/SECURITY.md`
- License: MIT (`<GITHUB_URL>/blob/main/LICENSE`)

## Project status

Current v1 target:

- JVM `core` + `relay` build and pass tests.
- Android app pairs through the relay and executes core commands with permission checks, notifications, and activity log.
- MCP server + CLI can pair and drive the phone end to end.