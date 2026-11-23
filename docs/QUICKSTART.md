# PhoneBridge Quickstart

Get from a fresh clone to a bot driving a phone, in ten minutes. For the product tour and safety
notes read [USER_GUIDE.md](USER_GUIDE.md); for the wire format read [PROTOCOL.md](PROTOCOL.md).

Status: **pre-alpha.** The JVM side (protocol, relay, executor, end-to-end harness) is tested here;
the Android APK still needs an Android SDK to build (see step 2).

---

## 0. Prove the whole v1 path on this machine first

One command, no phone, no SDK:

```powershell
.\gradlew.bat :e2e:test
```

That is the acceptance run. It starts real relays, pairs a bot by **short code**, **link** and
**`.pbpair` file**, opens the Noise `XX` session, and drives a fake phone through `status`,
`screenshot`, `ui_tree`, `tap`, `swipe`, `type`, `key`, `open_app`, a View-level permission denial,
control queueing between two bots, Kick everyone and `relay_move` between two relays. All of it uses
the real `:core`, `:relay` and `:android:executor-core` code; only the Android device is faked.

To watch it by hand, start a relay plus a fake phone and pair with it from anything:

```powershell
.\gradlew.bat :e2e:runFakePhone
```

It prints a relay URL, a certificate pin, a short code, a pairing link and a `.pbpair` file, and
auto-approves pairing requests at **Control** (development harness only). Press Enter to stop.

---

## 1. What you need

- An Android phone, Android 8.0 (API 26) or newer.
- The PhoneBridge app (`android/app`).
- A **heart**: the relay that forwards encrypted messages. Your PC, your phone or a small VPS.
- A bot that speaks PhoneBridge: an MCP-capable agent, the PhoneBridge CLI, or your own script.

Nothing here needs a PhoneBridge account: there is no central server, and no key is shared with the
authors.

## 2. Build and install the Android app

The APK build needs an Android SDK (JDK 17+ and the SDK; this repo has **no** SDK committed and no
bot will accept SDK licences for you).

```powershell
# One-time, on a machine with the Android SDK installed:
$env:ANDROID_HOME = "C:\Users\<you>\AppData\Local\Android\Sdk"
echo "sdk.dir=$env:ANDROID_HOME" > local.properties

.\gradlew.bat :android:app:assembleDebug
# APK: android\app\build\outputs\apk\debug\app-debug.apk
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

`android/app` is only part of the Gradle build when an SDK is found (`settings.gradle.kts`), so
`:core`, `:relay`, `:android:executor-core`, `:android:ui-core` and `:e2e` build and test on any
machine.

No SDK handy? The GitHub Actions workflow (`.github/workflows/ci.yml`) builds `assembleDebug` in CI
and uploads the APK as a build artifact.

## 3. Turn the phone on

Open **PhoneBridge** and work through the onboarding checklist:

1. **Accessibility service** - Settings > Accessibility > PhoneBridge > On. The bot can read the UI
   tree and tap/swipe/type through this. Without it every screen command answers
   `accessibility_disabled`.
2. **Notifications** - allow them. The ongoing notification always names the bot in control and has
   **Stop** and **Kick everyone**.
3. **Battery optimisation** - exempt PhoneBridge, or Android may freeze the connection in Doze.
4. **Screen capture** (optional, Android 11+) - only needed for pixel screenshots on devices where
   the accessibility screenshot API is restricted.
5. **Shizuku** (optional, Full level only) - install and start Shizuku for `install_apk`, `shell`,
   `files` and `settings`. Shizuku must be restarted by you after a reboot; until then those
   commands answer `shizuku_unavailable`.
6. **Start the bridge** on the main screen. The notification appears.

## 4. Choose where the heart beats

The relay only forwards encrypted envelopes; it can never read commands or screenshots. Pick one:

**a. My PC (recommended while you are at your desk).**

```powershell
# self-signed TLS certificate generated on first run; the cert pin is printed
.\gradlew.bat :relay:run --args="--port 8443 --bind 0.0.0.0 --data-dir $env:USERPROFILE\.phonebridge"

# or the standalone jar, e.g. on a VPS:
.\gradlew.bat :relay:shadowJar
java -jar relay\build\libs\phonebridge-relay-all.jar --port 8443 --data-dir /var/lib/phonebridge
```

Copy the printed `url` (`wss://<host>:8443`) and `cert pin` into **PhoneBridge > Relay > Custom URL**
(or **My PC**), then tap **Save**.

**b. This phone.** **PhoneBridge > Relay > This phone > Move heart here.** The phone starts its own
relay and advertises its LAN address. Works on the same Wi-Fi; across mobile data or NAT use (a) or
(c). The pairing offer then carries the relay certificate pin automatically.

**c. Your own VPS.** Same standalone jar as (a), always on. Remember that turning the host off
stops remote access, which is the point.

Moving the heart later is a command: a Full-level bot can send
`relay_move {"to":"wss://new-host:8443"}` and every peer follows (`relay/PROTOCOL-RELAY.md` section 4).

## 5. Pair a bot

On the phone: **Pair a device**. One offer is live at a time and dies after **10 minutes** or after
the first use. Use any of the three forms; they are equivalent:

| Form | What you do | What the bot does |
| --- | --- | --- |
| **Short code** | Read `7K4-Q9B` off the screen and type it to the bot | `pair_lookup` for the code, then `pair_request` |
| **Link / QR** | Show the QR, or send the `phonebridge://pair?d=...` link | parse the offer out of the link |
| **File** | Share the `.pbpair` file to the bot's chat | parse the offer JSON |

The bot then connects to the relay in the offer (pinning `relay_pin` when present), sends the sealed
`pair_request`, and your phone shows **Allow this bot?** with its name, fingerprint and the level it
asked for.

- Pick the **lowest level** that works (View < Control < Full), then **Allow**.
- **Deny** if you did not expect it; that offer is burned either way.

The bot is now in the bot list with a switch, a level picker and **Unpair**. One bot drives at a
time; others queue FIFO and get `busy`/`not_controller` until their turn.

## 6. Point your bot at it

### MCP server / CLI (task 05 - not in `main` yet)

The MCP server, CLI and bot client live in `mcp/` on the `pb/mcp-server-cli` branch. They were written
against the pre-`core` draft handshake, so they cannot pair with the merged `:core` implementation
yet; see HANDOFF.md. Once that branch is rebased onto `:core`, the configuration is:

```json
{
  "mcpServers": {
    "phonebridge": {
      "command": "node",
      "args": ["C:\\path\\to\\PhoneBridge\\mcp\\dist\\server\\index.js"],
      "env": { "PHONEBRIDGE_HOME": "C:\\Users\\you\\.phonebridge" }
    }
  }
}
```

and the CLI usage:

```powershell
node mcp\dist\cli\index.js pair 7K4-Q9B --relay wss://192.168.1.20:8443 --pin <cert pin>
node mcp\dist\cli\index.js status
node mcp\dist\cli\index.js screenshot -o shot.png
node mcp\dist\cli\index.js tap 540 1200
```

### Any other bot: the JSON API

Inside the Noise session every message is one JSON object
([PROTOCOL.md](PROTOCOL.md) section 6):

```json
{ "id": "1", "cmd": "status" }
{ "id": "1", "ok": true, "result": { "device": "Pixel 9", "battery": 88, "screen_on": true,
  "controller": "<peer_id>|null", "queue": [], "shizuku": false } }

{ "id": "2", "cmd": "tap", "args": { "x": 540, "y": 1200 } }
{ "id": "3", "cmd": "tap", "args": { "node_id": "0.1" } }
{ "id": "4", "cmd": "screenshot", "args": { "format": "jpeg", "quality": 70, "max_width": 720 } }
```

Argument rules and every error code are in [PROTOCOL.md](PROTOCOL.md) 6.1 and 6.2. A first command
from a Control-level bot must be `request_control`; otherwise you get `not_controller`.

## 7. Staying in control

- The ongoing notification names the bot in control, or says **No bot in control**.
- **Kick everyone** revokes every live session at once (paired keys survive; bots must reconnect).
- **Activity log** shows every command per bot. Typed text, shell commands and file contents are
  never copied into it.
- **Unpair** removes one bot's keys and leaves the others alone.

## 8. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `code_not_found` / `code_expired` | The offer was never published, was already used, or its ten minutes ran out. Make a new offer. |
| Bot cannot reach the relay (TLS error) | A self-signed relay needs its `cert pin` in the offer or the bot config. Regenerate the offer after fixing the relay URL. |
| `accessibility_disabled` | Turn the service on in Settings > Accessibility. |
| `permission_denied` | The bot's level is too low. Raise it in the bot list (or re-pair at a higher level). |
| `not_controller` / `busy` | Another bot holds control. Wait, ask it to `release_control`, or use Kick everyone. |
| `shizuku_unavailable` | Start Shizuku again (it does not survive a reboot). |
| `projection_denied` | Grant screen capture in the app, or use a phone with Android 11+ accessibility screenshots. |
| `peer_offline` | The other side is not connected to the relay; check the heart is running. |
| Remote access stopped | The relay host is off. That is the design: no heart, no access. |

## 9. Where the tests live

| Command | Covers |
| --- | --- |
| `.\gradlew.bat :e2e:test` | The v1 acceptance run described in step 0. |
| `.\gradlew.bat :core:test` | Protocol, crypto, pairing, control arbiter, chunking. |
| `.\gradlew.bat :relay:test` | Relay auth, rate limits, mailbox, TLS, `relay_move`. |
| `.\gradlew.bat :android:executor-core:test` | Command dispatch, permissions, args, UI-tree ids. |
| `.\gradlew.bat :android:ui-core:test` | Pairing/approval/list view models, session handler. |
| `cd tests\conformance; npm ci; npm test` | JSON Schemas and protocol examples. |
