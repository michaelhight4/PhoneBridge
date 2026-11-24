# PhoneBridge: itch.io page

**Title:** PhoneBridge

**Tagline (short description):** Let your own AI bot see and control your own phone and PC. End-to-end encrypted, no central server, and you approve every bot.

**Classification:** Tool · **Platforms:** Android, Windows · **Price:** Free (no payments) · **License:** MIT, open source

---

## About

PhoneBridge is a free, open-source app that gives **your own** AI bot eyes and hands on **your own**
Android phone and Windows PC from anywhere. It can take screenshots, read the screen, tap, swipe, type and
open apps. At the Full level it can also install apps and run commands.

It works with any bot that has tools. A documented JSON API plus an MCP server means OpenCode, Claude-style
agents, scripts and other MCP-capable bots can all use it without custom code.

### Your device, your rules
- **No central server, no master key.** The relay, called "the heart", runs on a device *you* pick: your PC,
  your phone, or your own small VPS. Turn that host off and remote access stops.
- **End-to-end encrypted** (Noise XX). The relay only forwards sealed envelopes and never sees your
  screen or commands.
- **You approve every bot.** Each pairing ends with an on-device **"Allow this bot?"** prompt.
- **Per-bot permissions:** View (screenshots, screen contents, notifications), Control (tap, type, open
  apps) or Full (install, settings, shell, files).
- **One bot drives at a time.** The others wait in line, and a notification always names who is in control.
  There's an activity log, an on/off switch per bot and a **Kick everyone** button.

## Pairing in 30 seconds
1. Open PhoneBridge and tap **Pair a bot** (on the PC: tray icon > Pair a bot).
2. Give your bot **one** of these:
   - the **QR code**,
   - the **short code** like `7K4-Q9B` (plus your relay address),
   - the **`phonebridge://pair?...` link**, or
   - the exported **`.pbpair` file**. You can just send it to your bot in chat.
3. Your device asks **"Allow this bot?"** and shows the bot's name and fingerprint. Pick a permission level
   (you can only lower what it asked for) and tap Allow.

Pairing codes work **once** and expire after **10 minutes**. Each bot gets its own key, so unpairing one
never affects the others.

## Install: Android (8.0 or newer)
1. Download **PhoneBridge-android-v0.1.0.apk** below, on your phone.
2. Open it. If Android asks, allow installs from your browser or file manager. It's not from the Play Store.
3. Open PhoneBridge and follow the checklist:
   - turn on the **PhoneBridge Accessibility service**,
   - allow notifications,
   - exempt it from battery optimisation.
4. Optional: grant **screen capture** for screenshots on older phones. For Full-level features (installing
   apps, settings, shell), install and start **Shizuku** (you need to restart it after every reboot).
5. Choose where the heart runs (this phone, your PC or a custom relay), then **Pair a bot**.

## Install: Windows 10/11 (64-bit)
1. Download **PhoneBridge-windows-v0.1.0.zip** and extract it anywhere, for example `Documents\PhoneBridge`.
2. Run **PhoneBridge.exe**. Java is bundled, so you don't need to install it. It starts in the system tray
   (bottom-right, maybe under the ^ arrow).
3. If Windows SmartScreen warns about an unknown publisher, click **More info > Run anyway**. If the
   firewall asks, allow **private networks**, so bots on your network can reach the heart on port 8443.
4. Tray menu > **Pair a bot**. Data is stored in `%APPDATA%\PhoneBridge`. To uninstall, quit from the tray and
   delete the folder.

## Connect your bot
Point your bot at the PhoneBridge MCP server (`mcp/` in the source) or the `phonebridge` CLI, then give it
your pairing code, link or file. Setup snippets for OpenCode, Claude Desktop/Code and generic MCP clients
are in `docs/API.md`.

## Safety
PhoneBridge gives a bot **real control** of your device. Only pair bots you trust, use the lowest level
that works, check the activity log, and use **Kick everyone** if anything looks wrong. There is no backdoor
and no shared server. This is a **pre-alpha** release, so expect rough edges.

## Open source
MIT License © 2026 Gary Hight. The source, spec, protocol and security notes are on GitHub (link coming soon:
github.com/michaelhight4/PhoneBridge).
