# PhoneBridge Bot API

Status: draft v0.1 (pre-alpha). This is the **bot side** of PhoneBridge: a TypeScript client library, an
MCP server and a `phonebridge` CLI that drive a user's own Android phone over the end-to-end encrypted
relay described in [PROTOCOL.md](PROTOCOL.md).

- **MCP server**: any MCP-capable agent (OpenCode, Claude Desktop/Code, your own client) gets the phone
  as tools with no custom code.
- **CLI**: every command is available as a shell command, for bots that can only run commands.
- **Library**: `PhoneBridgeClient` for anything that can run Node 20+.

Nothing here talks to a central server. The bot connects to the relay the user's phone chose, and the
relay only forwards encrypted envelopes.

---

## 1. Install and build

Requires Node 20+ and npm.

```sh
cd mcp
npm ci
npm run build        # compiles src/ to dist/ (strict TypeScript)
npm test             # vitest: pairing, handshake vectors, request correlation/timeouts, MCP tools
```

The package ships three entry points:

| Path | What |
| --- | --- |
| `dist/cli/index.js` | `phonebridge` CLI (also installed as the `phonebridge` bin) |
| `dist/server/index.js` | MCP server (stdio by default, optional streamable HTTP) |
| `dist/index.js` | the client library (types + runtime) |

Keystore: the bot's long-term X25519 key and its paired devices live in `~/.phonebridge/keystore.json`
(mode 0600). Override with `$PHONEBRIDGE_HOME` or `--home`. The keystore never contains the one-time
pairing secret.

```json
{
  "version": 1,
  "identity": { "publicKey": "…", "secretKey": "…", "peerId": "mjnjsejn1vm7qyq47vzg120kmr", "createdAt": "…" },
  "devices": [
    {
      "peerId": "qkbwwr62xh74gvwkxwge77g370",
      "name": "Pixel 9",
      "publicKey": "…",
      "relayUrl": "wss://relay.example:8765",
      "relayFingerprint": "sha256:ab:cd:…",
      "level": "control",
      "pairedAt": "2026-11-23T17:00:00.000Z",
      "lastSeenAt": "2026-11-23T17:05:00.000Z"
    }
  ],
  "currentDevice": "qkbwwr62xh74gvwkxwge77g370"
}
```

---

## 2. Pairing

Pairing is always approved **on the phone** ("Allow this bot?"). The bot's key fingerprint and requested
level are shown there, and the user can lower the level or deny.

### 2.1 Pairing inputs

| Input | Example | Notes |
| --- | --- | --- |
| Short code + relay | `7K4-Q9Y` with `--relay wss://relay.example:8765` | Bot asks the relay for the pending offer (`pair_lookup`). One-time, 10 minutes. |
| Link | `phonebridge://pair?d=eyJ2IjoxLC…` | Base64url JSON offer in the `d` parameter. Showed as a QR code on the phone. |
| File | `~/Downloads/phone.pbpair` | The offer JSON as a file. |
| Offer JSON | `{"v":1,"relay":"wss://…",…}` | Pasted directly. A `{"offer":{…}}` wrapper is accepted too. |

Short codes are Crockford base32, six symbols, written `XXX-XXX`; the last symbol is a checksum. Codes
that fail the checksum are rejected with `bad_code`, so a mistyped code never reaches the relay.
`7K4-Q9Y` is a valid example; the shape is always `7K4-Q9M`.

The offer itself (owner: `:core`, see [PROTOCOL.md](PROTOCOL.md)):

```json
{
  "v": 1,
  "relay": "wss://relay.example:8765",
  "device_id": "qkbwwr62xh74gvwkxwge77g370",
  "device_pub": "4ktV9b1UpPPypTkixgYXbIZlxS92bU0INsJsg3zQHiQ",
  "secret": "ceeVtD0GPA4rY5sA4sxjNm5w8F1x9nY9Sn4NNTU1m8s",
  "code": "7K4-Q9Y",
  "exp": 1900000000,
  "relay_fp": "sha256:8f:2a:…",
  "device_name": "Pixel 9"
}
```

- `device_id` must equal `peer_id(device_pub)`; the client refuses offers where it does not.
- `secret` is a one-time 32-byte key. It is mixed into the handshake and burned on first use.
- `exp` is unix seconds; expired offers are refused with `expired`.
- `relay_fp` (optional extension) pins the relay's TLS certificate: SHA-256 of the DER certificate,
  written as hex with or without colons. A mismatch aborts the connection before any data is sent.
- Plain `ws://` relays are accepted only on loopback, or with `--insecure-dev`.

### 2.2 Pairing flow

1. Bot builds a pairing offer (link) or looks the code up on the relay.
2. Bot connects to the relay and performs the handshake with `p:1`, mixing the pairing secret into the
   transcript.
3. Bot sends `pair_request` with its name, public key and requested level.
4. The phone shows "Allow this bot?" and answers with `pair_result`.
5. The bot stores the device in the keystore (peer id, public key, relay, fingerprint pin, granted level)
   and keeps the session open.

CLI:

```sh
phonebridge pair 7K4-Q9Y --relay wss://relay.example:8765 --level control
phonebridge pair "phonebridge://pair?d=eyJ2IjoxLC…"
phonebridge pair ~/Downloads/phone.pbpair --name my-agent
```

MCP: `pb_pair` with `{"input": "<code|link|file|json>", "relay_url"?: "wss://…", "level"?: "full"}`.

Library:

```ts
import { PhoneBridgeClient } from "phonebridge";

const client = await PhoneBridgeClient.open({ botName: "my-agent" });
const paired = await client.pair("7K4-Q9Y", {
  relayUrl: "wss://relay.example:8765",
  requestedLevel: "control",
});
console.log(paired.device.peerId, paired.level, paired.fingerprint);
```

### 2.3 Permission levels

| Level | Allows |
| --- | --- |
| `view` | `status`, `screenshot`, `ui_tree`, `notifications` |
| `control` | view + `tap`, `long_press`, `swipe`, `type`, `key`, `open_app`, `request_control`, `release_control` |
| `full` | control + `install_apk`, `shell`, `files`, `settings`, `relay_move` |

The client checks the granted level **before** sending and fails locally with `permission_denied`.

---

## 3. Sessions, events and reliability

- Transport: WebSocket to the relay (`wss://`), JSON envelopes the relay cannot read.
- End-to-end: X25519 (hs1/hs2) → key schedule → XChaCha20-Poly1305 frames (hs3/hs4 confirmation, then
  JSON requests). Concretization detail and test vectors are in
  [mcp/test/vectors](../mcp/test/vectors/handshake-v1.json).
- Request correlation: every request carries a UUID `id`; responses are matched by id, so concurrent
  requests may complete in any order.
- Timeouts (per command, override with `--timeout` / `timeoutMs`): `status` 10 s, `ui_tree` 30 s,
  `notifications` 15 s, `screenshot` 90 s, `install_apk` 300 s, `shell` `timeout_ms + 10 s`, others 20 s.
  A timeout rejects with `timeout` and leaves the session usable.
- Large messages (screenshots, files, APK data) are split into encrypted 32 KiB chunks and reassembled
  before JSON parsing; the 16 MiB relay limit is never approached.
- Reconnect: `client.connect()` reuses a live session or performs a fresh handshake. `peer_offline`
  means the phone or the relay is not reachable right now.
- Relay move: a `relay_moved` announcement updates the stored relay URL and is surfaced as an event.

Events the device can push (library `session.onEvent`, MCP clients see them through the next call or
the tool result of a control request):

```json
{ "event": "notification", "data": { "pkg": "com.example", "title": "…", "text": "…", "time": 1790000000 } }
{ "event": "control_changed", "data": { "controller": "my-agent", "queue": ["other-bot"] } }
{ "event": "queue_position", "data": { "position": 2 } }
{ "event": "relay_moved", "data": { "new_url": "wss://…", "token": "…", "expires": 1790000123 } }
{ "event": "kicked", "data": { "reason": "kick everyone" } }
```

Command frames are exactly the protocol in [PROTOCOL.md](PROTOCOL.md):

```json
{ "id": "8a0c…", "cmd": "tap", "args": { "x": 540, "y": 1200 } }
{ "id": "8a0c…", "ok": true, "result": {} }
{ "id": "8a0c…", "ok": false, "error": { "code": "permission_denied", "message": "needs Control" } }
```

---

## 4. Commands

Every command exists in all three surfaces: the JSON API (library `client.request(cmd, args)`), the
MCP tool and the CLI. Unless stated otherwise, responses follow the request/response frames above.

### 4.1 `status` — View

```json
{ "cmd": "status", "args": {} }
{ "device": "Pixel 9", "battery": 87, "screen_on": true, "controller": null, "queue": [], "shizuku": false }
```

CLI: `phonebridge status`. MCP: `pb_status`.

### 4.2 `screenshot` — View

```json
{ "cmd": "screenshot", "args": { "format": "png", "quality": 80, "max_width": 720 } }
{
  "mime": "image/png",
  "width": 720,
  "height": 1600,
  "data_b64": "iVBORw0KGgo…"
}
```

`format` is `png` (default) or `jpeg`; `quality` applies to JPEG; `max_width` downscales. Chunked
automatically when large. CLI: `phonebridge screenshot -o shot.png` (or `--json` for the JSON body, or
raw base64 on stdout). MCP `pb_screenshot` returns an MCP `image` content block plus a JSON metadata
block.

### 4.3 `ui_tree` — View

```json
{ "cmd": "ui_tree", "args": { "max_depth": 20, "visible_only": true } }
{
  "root": {
    "id": "n0",
    "cls": "android.widget.FrameLayout",
    "text": "",
    "desc": "",
    "res_id": "",
    "bounds": [0, 0, 1080, 2400],
    "clickable": false,
    "focusable": false,
    "editable": false,
    "children": []
  }
}
```

`bounds` is `[left, top, right, bottom]` in screen pixels. `id` can be passed to `tap` and `type`.
CLI: `phonebridge ui-tree --max-depth 20 --visible-only`. MCP: `pb_ui_tree`.

### 4.4 `notifications` — View

```json
{ "cmd": "notifications", "args": { "since": 1790000000000 } }
{ "items": [ { "pkg": "com.example", "title": "Hi", "text": "…", "time": 1790000000 } ] }
```

CLI: `phonebridge notifications --since 1790000000000`. MCP: `pb_notifications`.

### 4.5 `tap` — Control

```json
{ "cmd": "tap", "args": { "x": 540, "y": 1200 } }
{ "cmd": "tap", "args": { "node_id": "n3" } }
```

CLI: `phonebridge tap 540 1200` or `phonebridge tap --node n3`. MCP: `pb_tap`.

### 4.6 `long_press` — Control

```json
{ "cmd": "long_press", "args": { "x": 540, "y": 1200, "ms": 800 } }
```

CLI: `phonebridge long-press 540 1200 --ms 800`. Library: `session.longPress(...)`.

### 4.7 `swipe` — Control

```json
{ "cmd": "swipe", "args": { "x1": 540, "y1": 1800, "x2": 540, "y2": 400, "ms": 300 } }
```

CLI: `phonebridge swipe 540 1800 540 400 --ms 300`. MCP: `pb_swipe`.

### 4.8 `type` — Control

```json
{ "cmd": "type", "args": { "text": "hello world" } }
{ "cmd": "type", "args": { "text": "hello", "node_id": "n7" } }
```

CLI: `phonebridge type hello world` (quote for spaces: `phonebridge type "hello world"`).
MCP: `pb_type`.

### 4.9 `key` — Control

```json
{ "cmd": "key", "args": { "key": "home" } }
```

Allowed: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock`, `enter`, `power_dialog`.
CLI: `phonebridge key home`. MCP: `pb_key`.

### 4.10 `open_app` — Control

```json
{ "cmd": "open_app", "args": { "package": "com.android.settings" } }
{ "cmd": "open_app", "args": { "label": "Settings" } }
```

CLI: `phonebridge open-app com.android.settings` or `phonebridge open-app --label Settings`.
MCP: `pb_open_app`.

### 4.11 `request_control` / `release_control` — Control

```json
{ "cmd": "request_control", "args": {} }
{ "granted": true }
{ "granted": false, "position": 2 }
```

Only one bot drives at a time. If another bot holds control, the device queues FIFO and may also emit a
`queue_position` event. Control auto-releases after 120 s idle. Call `release_control` when finished so
the next bot can take over.

CLI: `phonebridge request-control`, `phonebridge release-control`. MCP: `pb_request_control`,
`pb_release_control`.

### 4.12 `install_apk` — Full

```json
{ "cmd": "install_apk", "args": { "url": "https://example.com/app.apk" } }
{ "cmd": "install_apk", "args": { "data_b64": "UEsDBBQ…", "name": "app.apk" } }
{ "package": "com.example.app" }
```

Requires Full permission and Shizuku; without Shizuku the device answers `shizuku_unavailable`.
CLI: `phonebridge install-apk https://…` or `phonebridge install-apk --file app.apk`.
MCP: `pb_install_apk`.

### 4.13 `shell` — Full

```json
{ "cmd": "shell", "args": { "command": "pm list packages -3", "timeout_ms": 15000 } }
{ "exit": 0, "stdout": "package:com.example\n", "stderr": "" }
```

Runs as the shell user through Shizuku. CLI: `phonebridge shell -- "pm list packages -3"`.
MCP: `pb_shell`.

### 4.14 `files` — Full

```json
{ "cmd": "files", "args": { "op": "list", "path": "/sdcard/Download" } }
{ "entries": [ { "name": "a.txt", "size": 12, "dir": false } ] }

{ "cmd": "files", "args": { "op": "read", "path": "/sdcard/Download/a.txt" } }
{ "data_b64": "aGVsbG8=" }

{ "cmd": "files", "args": { "op": "write", "path": "/sdcard/Download/a.txt", "data_b64": "aGVsbG8=" } }
{ "bytes": 5 }

{ "cmd": "files", "args": { "op": "delete", "path": "/sdcard/Download/a.txt" } }
{ "deleted": true }
```

CLI: `phonebridge files list /sdcard/Download`. MCP: `pb_files`.

### 4.15 `settings` — Full

```json
{ "cmd": "settings", "args": { "namespace": "system", "key": "screen_brightness" } }
{ "value": "120" }
{ "cmd": "settings", "args": { "namespace": "system", "key": "screen_brightness", "value": "60" } }
{ "value": "60" }
```

CLI: `phonebridge settings system screen_brightness 60`. Library: `session.settings(...)`.

### 4.16 `relay_move` — Full

```json
{ "cmd": "relay_move", "args": { "to": "this" } }
{ "new_url": "wss://192.168.1.20:8765" }
```

`to` is `this` (the bot's host), a peer id, or an explicit relay URL. The relay hands over its routing
table and announces `relay_moved`; peers reconnect to `new_url`. CLI: `phonebridge relay-move this`.
MCP: `pb_relay_move`.

### 4.17 Pairing messages

`pair_request` (bot → device, inside the handshake derived from the one-time secret):

```json
{
  "id": "…",
  "cmd": "pair_request",
  "args": {
    "bot_name": "my-agent",
    "bot_pub": "X21rWcYvKWDyU2N3yJF7XEAClKbb0yG6TuoiOSQcNi4",
    "requested_level": "control"
  }
}
```

The device answers with the protocol's `pair_result`, either in the request/response shape or bare:

```json
{ "id": "…", "ok": true, "result": { "ok": true, "granted_level": "control", "device_name": "Pixel 9" } }
{ "ok": false, "reason": "denied" }
```

`reason` values: `denied`, `expired`, `already_paired` (implementation-defined; anything that is not
`ok` maps to `permission_denied` except `expired`).

---

## 5. Error codes

Responses use `{ "ok": false, "error": { "code": …, "message": … } }`. The CLI prints
`error [code]: message`; MCP tools return `isError: true` with `{"ok":false,"error":{…}}` as text.

| Code | Meaning |
| --- | --- |
| `permission_denied` | The bot's level is too low, or the user denied pairing. |
| `not_controller` | A Control/Full command was sent without holding control. |
| `busy` | The device is driven by another bot; queue and retry. |
| `bad_args` | Malformed arguments (missing coordinates, too many chunks, invalid JSON). |
| `unknown_cmd` | The device does not implement the command. |
| `peer_offline` | Phone or relay is unreachable, or the session was closed. |
| `accessibility_disabled` | The user turned off the PhoneBridge accessibility service. |
| `projection_denied` | MediaProjection screenshot consent was refused. |
| `shizuku_unavailable` | Full-level command needs Shizuku, which is not running. |
| `timeout` | No response within the command timeout. |
| `internal` | Device-side failure; details in `message`. |
| `bad_code` | Pairing code has an illegal symbol or fails the checksum. |
| `bad_offer` | Offer JSON is malformed or `device_id` does not match `device_pub`. |
| `expired` | Pairing offer or short code expired (10 minutes). |
| `bad_input` | CLI/MCP input is not a pairing link, file, offer or code; also bad relay URL. |
| `not_paired` | No paired device in the keystore. |
| `no_device` | The device reference matched nothing. |
| `ambiguous_device` | Several devices match; pass a peer id, prefix, name or fingerprint. |
| `handshake_failed` | Handshake, key pinning or decryption failed. |
| `relay_error` | The relay refused or dropped the connection. |
| `unsupported` | Reserved for protocol extensions the peer does not know. |

---

## 6. MCP server

### 6.1 Run it

```sh
node mcp/dist/server/index.js                     # stdio
node mcp/dist/server/index.js --http 8765         # streamable HTTP at http://127.0.0.1:8765/mcp
node mcp/dist/server/index.js --home ~/.phonebridge --bot-name my-agent --device "Pixel 9"
```

Flags: `--http <port>`, `--host`, `--home`, `--bot-name`, `--device`, `--insecure-dev`, `--version`,
`--help`. All diagnostics go to stderr; stdout is protocol traffic only.

### 6.2 Tools

| Tool | Arguments | Level | Returns |
| --- | --- | --- | --- |
| `pb_pair` | `input`, `relay_url?`, `level?`, `name?` | – | paired device + fingerprint |
| `pb_devices` | – | – | bot identity + paired devices |
| `pb_status` | `device?` | View | `{device,battery,screen_on,controller,queue,shizuku}` |
| `pb_screenshot` | `format?`, `quality?`, `max_width?`, `device?` | View | MCP image + metadata |
| `pb_ui_tree` | `max_depth?`, `visible_only?`, `device?` | View | `{root:Node}` |
| `pb_notifications` | `since?`, `device?` | View | `{items:[…]}` |
| `pb_tap` | `x?`, `y?`, `node_id?`, `device?` | Control | `{}` |
| `pb_swipe` | `x1`, `y1`, `x2`, `y2`, `ms?`, `device?` | Control | `{}` |
| `pb_type` | `text`, `node_id?`, `device?` | Control | `{}` |
| `pb_key` | `key`, `device?` | Control | `{}` |
| `pb_open_app` | `package?`, `label?`, `device?` | Control | `{}` |
| `pb_request_control` | `device?` | Control | `{granted}` |
| `pb_release_control` | `device?` | Control | `{}` |
| `pb_install_apk` | `url?`, `data_b64?`, `name?`, `device?` | Full | `{package}` |
| `pb_shell` | `command`, `timeout_ms?`, `device?` | Full | `{exit,stdout,stderr}` |
| `pb_files` | `op`, `path`, `data_b64?`, `device?` | Full | op-specific |
| `pb_relay_move` | `to`, `device?` | Full | `{new_url}` |

`device` accepts a peer id, a peer id prefix (6+ chars), a device name, or the fingerprint shown on the
phone (`ABCD-EFGH`). Omit it when only one device is paired or `--device` is set.

### 6.3 OpenCode (V2)

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "phonebridge": {
        "type": "local",
        "command": ["node", "C:/Users/me/PhoneBridge/mcp/dist/server/index.js"],
        "environment": {
          "PHONEBRIDGE_HOME": "C:/Users/me/.phonebridge"
        }
      }
    }
  }
}
```

Or from the CLI:

```sh
opencode mcp add phonebridge --env PHONEBRIDGE_HOME=C:/Users/me/.phonebridge -- \
  node C:/Users/me/PhoneBridge/mcp/dist/server/index.js
opencode mcp list
```

OpenCode exposes the tools as `phonebridge_pb_status`, `phonebridge_pb_tap`, … (or through Code Mode's
`tools.phonebridge.pb_status`). Keep `codemode` at its default so the phone tools do not eat context
until used.

### 6.4 Claude Desktop

`claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/`, Windows:
`%APPDATA%\Claude\`):

```json
{
  "mcpServers": {
    "phonebridge": {
      "command": "node",
      "args": ["C:/Users/me/PhoneBridge/mcp/dist/server/index.js"],
      "env": { "PHONEBRIDGE_HOME": "C:/Users/me/.phonebridge" }
    }
  }
}
```

### 6.5 Claude Code

```sh
claude mcp add phonebridge --env PHONEBRIDGE_HOME=C:/Users/me/.phonebridge -- \
  node C:/Users/me/PhoneBridge/mcp/dist/server/index.js
```

or a project `.mcp.json`:

```json
{
  "mcpServers": {
    "phonebridge": {
      "command": "node",
      "args": ["C:/Users/me/PhoneBridge/mcp/dist/server/index.js"],
      "env": { "PHONEBRIDGE_HOME": "C:/Users/me/.phonebridge" }
    }
  }
}
```

### 6.6 Generic MCP client

Any MCP client that speaks stdio JSON-RPC works:

```json
{
  "transport": "stdio",
  "command": "node",
  "args": ["/abs/path/PhoneBridge/mcp/dist/server/index.js"],
  "env": { "PHONEBRIDGE_HOME": "/home/me/.phonebridge" }
}
```

Clients that prefer streamable HTTP can start `--http 8765` and point at `http://127.0.0.1:8765/mcp`.
The HTTP transport has no authentication because it binds loopback only; do not expose it to a network.

Example tool call (JSON-RPC over stdio):

```json
{ "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": { "name": "pb_tap", "arguments": { "x": 540, "y": 1200 } } }
```

---

## 7. CLI reference

```text
phonebridge [options] <command> [args]
```

| Command | Example |
| --- | --- |
| `pair` | `phonebridge pair 7K4-Q9Y --relay wss://relay.example:8765 --level control` |
| `devices` | `phonebridge devices` |
| `status` | `phonebridge status` |
| `screenshot` | `phonebridge screenshot -o shot.png` (raw base64 without `-o`; `--json` for the body) |
| `ui-tree` | `phonebridge ui-tree --max-depth 20 --visible-only` |
| `notifications` | `phonebridge notifications --since 1790000000000` |
| `tap` | `phonebridge tap 540 1200` / `phonebridge tap --node n3` |
| `long-press` | `phonebridge long-press 540 1200 --ms 800` |
| `swipe` | `phonebridge swipe 540 1800 540 400 --ms 300` |
| `type` | `phonebridge type "hello world"` |
| `key` | `phonebridge key home` |
| `open-app` | `phonebridge open-app com.android.settings` |
| `install-apk` | `phonebridge install-apk https://example.com/app.apk` |
| `shell` | `phonebridge shell -- "pm list packages -3"` |
| `files` | `phonebridge files list /sdcard/Download` |
| `settings` | `phonebridge settings system screen_brightness 60` |
| `relay-move` | `phonebridge relay-move this` |
| `request-control` | `phonebridge request-control` |
| `release-control` | `phonebridge release-control` |

Options: `-d/--device <ref>`, `--home <dir>`, `-r/--relay <url>`, `--level`, `--name`,
`--timeout <ms>`, `--json`, `--insecure-dev`, `-h/--help`, `-v/--version`. Use `--` to pass a command
line through to `shell` unchanged.

Exit codes: `0` success, `1` runtime error (protocol/connection), `2` usage error.

---

## 8. Library API

```ts
import { PhoneBridgeClient, PhoneBridgeError } from "phonebridge";

const client = await PhoneBridgeClient.open({ botName: "my-agent" });

// Pairing and devices
const outcome = await client.pair(linkOrCode, { relayUrl, requestedLevel: "full" });
const devices = client.devices();
const record = client.resolveDevice("Pixel 9");

// One session per device, reused across calls
const session = await client.connect("Pixel 9");
session.onEvent(({ event, data }) => console.log(event, data));
const status = await session.status();
const shot = await session.screenshot({ format: "jpeg", quality: 80 });
await session.requestControl();
await session.tap({ x: 540, y: 1200 });
await session.releaseControl();

// Generic escape hatch for any protocol command
const raw = await session.request("ui_tree", { max_depth: 10 }, { timeoutMs: 30_000 });
```

`PhoneBridgeError` carries `code`, `message` and optional `details`; `isPhoneBridgeError()` narrows.
Advanced pieces (`SecureChannel`, `deriveHandshakeKeys`, `RelayConnection`, `Keystore`, pairing parsers)
are exported for tests and other transports.

---

## 9. Test vectors for `:core`

`mcp/test/vectors/handshake-v1.json` is the cross-language contract for the Kotlin `:core` module: fixed
keys and nonces, the exact `hs1`/`hs2` JSON, transcript hash, DH outputs, session keys, and byte-exact
ciphertexts for `hs3`, `hs4` and a `tap` request, plus the relay hello proof. See
[mcp/test/vectors/README.md](../mcp/test/vectors/README.md). Regenerate with `npm run build && npm run
vectors` after any protocol change and tell the `:core` lane.
