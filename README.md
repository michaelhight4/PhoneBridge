# PhoneBridge

Let **your own** AI bot see and control **your own** Android phone (v1) and, later, your desktop
(Windows first, then Mac/Linux) from anywhere, over an end-to-end encrypted link.

- **Bot-agnostic:** a documented JSON API plus an MCP server, so any agent bot (OpenCode, Claude,
  ChatGPT-style bots with tools, Home Assistant, a plain script) can drive it.
- **No central server, no master key:** the relay ("the heart") runs on a device *you* pick:
  your PC, your phone, or an optional tiny VPS relay. Turn that host off and remote access stops.
- **You stay in charge:** every bot is paired explicitly (QR, short code like `7K4-Q9M`, or a
  `phonebridge://pair?...` link/file), gets its own key and permission level (View / Control / Full),
  shows up in a Bluetooth-style list, and a persistent notification always names the bot in control.
  One tap kicks everyone.

Status: **pre-alpha, under active construction.** See [docs/SPEC.md](docs/SPEC.md) and
[docs/PROTOCOL.md](docs/PROTOCOL.md).

## Layout

| Folder | What |
| --- | --- |
| `core/` | Shared Kotlin/JVM library: protocol messages, crypto, pairing codes/links |
| `relay/` | Embeddable relay ("heart") + standalone relay jar for a VPS |
| `android/` | Android app (Kotlin, minSdk 26) |
| `mcp/` | MCP server (TypeScript) + CLI test client |
| `desktop/` | Desktop agent (phase 2) |
| `docs/` | Spec, protocol, API, bot workflow |

## Safety

PhoneBridge gives a bot real control of a device. Only pair bots you trust, use the lowest permission
level that works, and watch the activity log. Each install pairs only with bots its owner approves on
the device itself; there is no backdoor and no shared server.

## License

MIT, see [LICENSE](LICENSE).
