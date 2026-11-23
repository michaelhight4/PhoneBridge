# PhoneBridge User Guide

This guide is for non-technical users. PhoneBridge lets **your own** AI bot see and control **your own** Android phone, with your approval.

## What PhoneBridge is

PhoneBridge connects your Android phone to a bot you choose. The bot can look at your screen, read the UI, and (if you allow it) tap, type, swipe, open apps, or do advanced actions.

PhoneBridge is designed so:

- **You choose the bot.** Every bot must be approved on your phone.
- **You choose the permission level.** You can give view-only access or more.
- **There is no central PhoneBridge server.** The connection goes through a relay ("the heart") running on a device you pick.
- **Your commands are end-to-end encrypted.** The relay passes messages along but cannot read them.

## Safety first

PhoneBridge can give a bot real control of your phone. Before pairing:

- Only pair bots you trust.
- Give the lowest permission level that works.
- Watch the persistent notification and activity log while a bot is connected.
- Use **Kick everyone** immediately if something looks wrong.
- Unpair bots you no longer use.

### Permission levels

| Level | What it can do | When to use it |
| --- | --- | --- |
| **View** | See screenshots, screen layout/UI tree, notifications, and device status. | Safest option. Good for "look, don't touch". |
| **Control** | Everything in View, plus tap, long-press, swipe, type, press keys, and open apps. | Use when you want the bot to operate the phone UI. |
| **Full** | Everything in Control, plus install APKs, run shell commands, file access, settings changes, and move the relay. | High risk. Only for trusted bots and advanced tasks. |

If you are unsure, start with **View**.

## What you need

- An Android phone (Android 8.0 / API 26 or newer).
- The PhoneBridge Android app installed.
- A bot or tool that can talk PhoneBridge (for example, an MCP-capable agent or the PhoneBridge CLI).
- A relay host ("heart"): your desktop, your phone, or a small server/VPS you control.

## Install the Android app

1. **Download PhoneBridge** from the project's GitHub Releases page, or from a trusted build artifact.
2. **Install the APK** on your phone. Android may ask you to allow installs from that source.
3. **Open PhoneBridge** and follow the setup screens.
4. **Enable Accessibility Service** when asked. PhoneBridge uses it to read the screen and perform taps/swipes/typing.
5. **Allow screen capture/screenshot permission** when asked.
6. **Optional: Shizuku for Full access.** If you want Full-level features (shell, install APK, some settings), install and start Shizuku. After a phone reboot, Shizuku usually needs to be started again.

If the app says a feature is unavailable, it usually means a permission/service is off.

## Pick where the "heart" runs

The relay is the "heart" that passes encrypted messages between your phone and your bot.

- **Desktop/PC host:** good when your computer is usually on.
- **Phone host:** useful before travel; remote access works while that phone can host and stay reachable.
- **Your own VPS:** useful if you want an always-on relay.

Important: if the relay host is off, remote access stops. That is normal.

## Pair a bot

Pairing gives a specific bot access. Pairing secrets expire after **10 minutes** and can be used once.

You can pair in three equivalent ways:

1. **QR code** — show the QR code on your phone and let the bot scan/import it.
2. **Short code** — a code like `7K4-Q9B`. Enter or paste it into the bot/CLI.
3. **Link or pairing file** — a `phonebridge://pair?...` link or a `.pbpair` file that you send to your bot.

After the bot uses the pairing secret, your phone will show:

> **Allow this bot?**

Check the bot name and fingerprint. If it looks right, choose a permission level and approve. If you did not expect it, deny it.

## Using PhoneBridge with a bot

Once paired, your bot can send commands through the PhoneBridge API/MCP server. Common commands include:

- **View:** check status, take a screenshot, read the screen layout, read notifications.
- **Control:** tap, swipe, type, press Back/Home/Recents, open an app.
- **Full:** install an APK, run a shell command, read/write files, change settings, move the relay.

The exact available commands depend on the permission level you granted and what your phone has enabled.

## Staying in control

- **Persistent notification:** shows when PhoneBridge is active and which bot is in control.
- **One bot at a time:** only one bot can control the device at once. Others may wait in a queue.
- **Activity log:** review what bots asked the device to do.
- **Kick everyone:** immediately ends live bot sessions.
- **Unpair:** removes a bot's access. Unpairing one bot does not affect others.

## Common problems

### The bot cannot pair

- The pairing code/link/file may have expired (they last 10 minutes). Create a new one.
- Make sure the bot reached the correct relay.
- Confirm you tapped **Allow** on the phone.

### The bot says `accessibility_disabled`

- Open Android Accessibility settings and enable the PhoneBridge Accessibility Service.

### Screenshots do not work

- Make sure you granted screen capture/MediaProjection permission.
- On older Android versions, screenshot behavior can be limited.

### Full commands fail with `shizuku_unavailable`

- Install/start Shizuku, grant it the needed permission, and try again.
- After reboot, start Shizuku again.

### The bot says `not_controller` or `busy`

- Another bot is currently in control. Wait for your turn, ask it to release control, or use Kick everyone if needed.

### Remote access stopped

- The relay host may be off or unreachable. Turn it back on or move the relay to another host.

## Privacy and security notes

- Screenshots, UI trees, and notifications can contain sensitive information.
- Do not post pairing codes, pairing files, or private logs publicly.
- There is no PhoneBridge cloud account that can recover your device access. Your device approvals and keys are what control access.

For security reporting and the full threat model, see [SECURITY.md](../SECURITY.md).