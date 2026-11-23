# PhoneBridge Bot API

Status: draft v0.1 (pre-alpha). This is the **bot side** of PhoneBridge: a TypeScript client library, an
MCP server and a `phonebridge` CLI that drive a user's own Android phone over the end-to-end encrypted
relay described in [PROTOCOL.md](PROTOCOL.md). The Kotlin `core/` module is the reference implementation
of the wire format; this module is checked against it by `mcp/test/vectors/handshake-v1.json`.

- **MCP server**: any MCP-capable agent (OpenCode, Claude Desktop/Code, your own client) gets the phone
  as tools with no custom code.
- **CLI**: every command is available as a shell command, for bots that can only run commands.
- **Library**: `PhoneBridgeClient` for anything that can run Node 20+.

Nothing here talks to a central server. The bot connects to the relay the user's phone chose, and the
relay only forwards opaque, end-to-end encrypted frames.

---

## 1. Install and build

Requires Node 20+ and npm.

```sh
cd mcp
npm ci
npm run build        # compiles src/ to dist/ (strict TypeScript)
npm test             # vitest: pairing, Noise handshake vectors, correlation/timeouts, chunks, MCP tools
npm run typecheck    # build config + tests, no emit
```

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
      "relayUrl": "wss://relay.example:8443",
      "relayPin": "gAE-I7hHRx-RlZGgs9lOc5R2XOwxzK1O07ckH1ocqlI",
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

Pairing is always approved **on the phone** ("Allow this bot?"). The bot's name, key fingerprint and
requested level are shown there; the user can lower the level or deny.

### 2.1 Pairing inputs

| Input | Example | Notes |
| --- | --- | --- |
| Short code + relay | `7K4-Q9B` with `--relay wss://relay.example:8443` | The bot asks the relay for the offer (`pair_lookup`, released once). |
| Link | `phonebridge://pair?d=eyJ2IjoxLC…` | Base64url UTF-8 offer JSON in `d`. Shown as a QR code on the phone. |
| File | `~/Downloads/phone.pbpair` | The offer JSON as a file; a UTF-8 BOM is tolerated. |
| Offer JSON | `{"v":1,"relay":"wss://…",…}` | Pasted directly (a `{"offer":{…}}` wrapper is accepted too). |

Short codes are Crockford base32 (no `I`, `L`, `O`, `U`), six symbols written `XXX-XXX`: five data
symbols plus a **CRC-5 check symbol** (polynomial `0x05`, initial `0x1F`, MSB first). Input is
case-folded and `O→0`, `I/L→1`, separators dropped. Codes that fail the check are rejected with
`bad_code` before anything reaches the relay. Examples: `7K4Q9 → 7K4-Q9B`, `ZZZZZ → ZZZ-ZZQ`,
`00000 → 000-00T`.

The offer (owned by `core/`, documented in [PROTOCOL.md](PROTOCOL.md)):

```json
{
  "v": 1,
  "relay": "wss://192.168.1.20:8443",
  "relay_pin": "gAE-I7hHRx-RlZGgs9lOc5R2XOwxzK1O07ckH1ocqlI",
  "device_id": "qkbwwr62xh74gvwkxwge77g370",
  "device_pub": "4ktV9b1UpPPypTkixgYXbIZlxS92bU0INsJsg3zQHiQ",
  "secret": "ceeVtD0GPA4rY5sA4sxjNm5w8F1x9nY9Sn4NNTU1m8s",
  "code": "7K4-Q9B",
  "exp": 1900000600,
  "device_name": "Pixel 9"
}
```

- `device_id` must equal `peer_id(device_pub)`; the client refuses offers where it does not.
- `secret` is a one-time 32-byte key; it is burned on first use or expiry.
- `exp` is unix seconds, creation + 600. Expired offers are refused with `expired`.
- `relay_pin` is optional: the base64url SHA-256 of the relay's DER certificate. When present, the TLS
  handshake only succeeds against that exact certificate. A relay behind a normal CA certificate omits
  it and the system trust store plus host-name verification is used.
- Plain `ws://` relays are accepted only on loopback, or with `--insecure-dev`.

### 2.2 Pairing flow

1. The bot builds the offer (link/file/JSON) or looks the code up on the relay.
2. The bot connects and authenticates to the relay (`challenge` / `hello` / `hello_ok`, section 3).
3. The bot seals a `pair_request` with the one-time secret and sends it as a `route` envelope:
   ```json
   { "v": 1, "bot_name": "my-agent", "bot_pub": "<b64url>", "requested_level": "control",
     "bot_id": "<peer_id>" }
   ```
   Key: `HKDF-SHA256(secret, salt "PhoneBridge/1/pairing", info "pair-frame")`; ChaCha20-Poly1305 with a
   random 12-byte nonce, `sealed = nonce || ciphertext || tag`; AAD
   `"PhoneBridge/1/pair|request|" || device_pub`. The result uses
   `"PhoneBridge/1/pair|result|" || device_pub`, so directions cannot be swapped.
4. The phone shows "Allow this bot?" and answers with a sealed
   `{v:1, ok:true, granted_level, device_name, device_id}` or `{v:1, ok:false, reason}`.
5. The bot opens Noise `XX` with the device and pins the device's static key from the offer.
6. The bot stores the device in the keystore and keeps the session open.

CLI:

```sh
phonebridge pair 7K4-Q9B --relay wss://relay.example:8443 --level control
phonebridge pair "phonebridge://pair?d=eyJ2IjoxLC…"
phonebridge pair ~/Downloads/phone.pbpair --name my-agent
```

MCP: `pb_pair` with `{"input": "<code|link|file|json>", "relay_url"?: "wss://…", "level"?: "full"}`.

Library:

```ts
import { PhoneBridgeClient } from "phonebridge";

const client = await PhoneBridgeClient.open({ botName: "my-agent" });
const paired = await client.pair("7K4-Q9B", {
  relayUrl: "wss://relay.example:8443",
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

## 3. Relay connection

The relay sends a challenge the moment the WebSocket opens; the peer proves key ownership with a
one-pass DH-MAC:

```
shared  = X25519(peer_private, relay_pub)              // relay_pub is fresh per connection
context = "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>"
sig     = HMAC-SHA256(key = shared, message = context)
```

The peer answers `hello {from, pub, nonce, sig, role}` and the relay replies
`hello_ok {to, relay_id, peer, server_time, limits}`. Errors come back as
`{type:"error", error:{code,message}}`; unknown envelope types are ignored. `ping`/`pong` keep the
socket alive, and `relay_moved {new_url,token,expires}` announces a handover (the client records the new
URL and emits a `relay_moved` event).

Relay error codes: `unauthorized`, `token_used`, `bad_envelope`, `unknown_type`, `already_authenticated`,
`peer_offline`, `rate_limited`, `mailbox_full`, `bad_args`, `code_not_found`, `code_expired`,
`too_large`, `internal`.

---

## 4. Sessions, events and reliability

- End-to-end: **Noise `XX`** (`Noise_XX_25519_ChaChaPoly_SHA256`), prologue `PhoneBridge/1/noise-xx`,
  pattern `-> e`, `<- e, ee, s, es`, `-> s, se`. Both static keys travel encrypted; each side pins the
  other's key from the pairing record before trusting the channel.
- Frames in `route.payload` are `0x01 || handshake message` or `0x02 || transport ciphertext`,
  base64url-encoded.
- Transport: one key per direction, nonce = 64-bit counter little-endian + four zero bytes, empty AAD,
  8 MiB plaintext cap. Replays, reordering and tampering fail authentication; the counter only advances
  on success.
- Request correlation: every request carries a UUID `id`; responses are matched by id, so concurrent
  requests may complete in any order.
- Timeouts (per command, override with `--timeout` / `timeoutMs`): `status` 10 s, `ui_tree` 30 s,
  `notifications` 15 s, `screenshot` 90 s, `install_apk` 300 s, `shell` `timeout_ms + 10 s`, others 20 s.
  A timeout rejects with `timeout` and leaves the session usable.
- Large payloads are protocol `chunk` events (section 9).
- Reconnect: `client.connect()` reuses a live session or performs a fresh Noise handshake.
  `peer_offline` means the phone or the relay is not reachable right now.
- Events: `notification` `{item:{pkg,title,text,time}}`, `control_changed` `{controller,name?}`,
  `queue_position` `{position}`, `kicked` `{reason?}`, `relay_moved` `{new_url,token?,expires?}`,
  `chunk` `{chunk_id,index,total,data_b64,sha256}`. Use `session.onEvent` in the library.

Command frames are exactly the protocol in [PROTOCOL.md](PROTOCOL.md):

```json
{ "id": "8a0c…", "cmd": "tap", "args": { "x": 540, "y": 1200 } }
{ "id": "8a0c…", "ok": true, "result": {} }
{ "id": "8a0c…", "ok": false, "error": { "code": "permission_denied", "message": "needs Control" } }
```

---

## 5. Commands

Every command exists in all three surfaces: the JSON API (library `client.request(cmd, args)`), the MCP
tool and the CLI. Argument rules are enforced by `core/` and mirrored here; violations are `bad_args`.

### 5.1 `status` — View

```json
{ "cmd": "status", "args": {} }
{ "device": "Pixel 9", "battery": 87, "screen_on": true, "controller": null, "queue": [], "shizuku": false }
```

CLI: `phonebridge status`. MCP: `pb_status`.

### 5.2 `screenshot` — View

```json
{ "cmd": "screenshot", "args": { "format": "png", "quality": 80, "max_width": 720 } }
{ "mime": "image/png", "width": 720, "height": 1600, "data_b64": "iVBORw0KGgo…" }
```

`format` is `png` (default) or `jpeg`; `quality` 1–100; `max_width` 16–16384. Inline results cap at the
8 MiB transport limit; larger screenshots are chunked:

```json
{ "mime": "image/png", "width": 1080, "height": 2400,
  "chunked": true, "chunk_id": "AAECAwQFBgc", "total": 12, "size": 2983423, "sha256": "…" }
```

CLI: `phonebridge screenshot -o shot.png` (raw base64url on stdout, `--json` for the JSON body).
MCP `pb_screenshot` returns an MCP `image` content block (standard base64) plus a JSON metadata block.

### 5.3 `ui_tree` — View

```json
{ "cmd": "ui_tree", "args": { "max_depth": 20, "visible_only": true } }
{ "root": { "id": "n0", "cls": "android.widget.FrameLayout", "text": "", "desc": "", "res_id": "",
             "bounds": [0, 0, 1080, 2400], "visible": true, "clickable": false, "focusable": false,
             "editable": false, "children": [] } }
```

`bounds` is `[left, top, right, bottom]` in screen pixels. `id` can be passed to `tap`, `long_press` and
`type`. `max_depth` 1–1000, `visible_only` defaults to true. CLI: `phonebridge ui-tree --max-depth 20`.
MCP: `pb_ui_tree`.

### 5.4 `notifications` — View

```json
{ "cmd": "notifications", "args": { "since": 1790000000000 } }
{ "items": [ { "pkg": "com.example", "title": "Hi", "text": "…", "time": 1790000000000 } ] }
```

CLI: `phonebridge notifications --since 1790000000000`. MCP: `pb_notifications`.

### 5.5 `tap` — Control

```json
{ "cmd": "tap", "args": { "x": 540, "y": 1200 } }
{ "cmd": "tap", "args": { "node_id": "n3" } }
```

Exactly one of the two forms; never both, never neither. CLI: `phonebridge tap 540 1200` or
`phonebridge tap --node n3`. MCP: `pb_tap`.

### 5.6 `long_press` — Control

```json
{ "cmd": "long_press", "args": { "x": 540, "y": 1200, "ms": 800 } }
{ "cmd": "long_press", "args": { "node_id": "n3", "ms": 800 } }
```

`ms` 1–60000. CLI: `phonebridge long-press 540 1200 --ms 800`. Library: `session.longPress(...)`.

### 5.7 `swipe` — Control

```json
{ "cmd": "swipe", "args": { "x1": 540, "y1": 1800, "x2": 540, "y2": 400, "ms": 300 } }
```

Both points must differ; `ms` 1–60000. CLI: `phonebridge swipe 540 1800 540 400 --ms 300`. MCP:
`pb_swipe`.

### 5.8 `type` — Control

```json
{ "cmd": "type", "args": { "text": "hello world" } }
{ "cmd": "type", "args": { "text": "hello", "node_id": "n7" } }
```

CLI: `phonebridge type "hello world"`. MCP: `pb_type`.

### 5.9 `key` — Control

```json
{ "cmd": "key", "args": { "key": "home" } }
```

Allowed: `back`, `home`, `recents`, `notifications`, `quick_settings`, `lock`, `enter`, `power_dialog`.
CLI: `phonebridge key home`. MCP: `pb_key`.

### 5.10 `open_app` — Control

```json
{ "cmd": "open_app", "args": { "package": "com.android.settings" } }
{ "cmd": "open_app", "args": { "label": "Settings" } }
```

Exactly one of `package`/`label`. CLI: `phonebridge open-app com.android.settings` or
`phonebridge open-app --label Settings`. MCP: `pb_open_app`.

### 5.11 `request_control` / `release_control` — Control

```json
{ "cmd": "request_control", "args": {} }
{ "granted": true }
{ "granted": false, "position": 2 }
```

Exactly one bot drives at a time. A queued bot also receives `queue_position` events. Control
auto-releases after 120 s idle and the next bot is promoted. Call `release_control` when finished.

CLI: `phonebridge request-control`, `phonebridge release-control`. MCP: `pb_request_control`,
`pb_release_control`.

### 5.12 `install_apk` — Full

```json
{ "cmd": "install_apk", "args": { "url": "https://example.com/app.apk" } }
{ "cmd": "install_apk", "args": { "data_b64": "UEsDBBQ…", "name": "app.apk" } }
{ "package": "com.example.app" }
```

Exactly one of `url`/`data_b64`; an inline APK needs `name`, and only https URLs are accepted. Inline
data must fit the 8 MiB transport limit — use a URL for bigger APKs. Requires Full permission and
Shizuku; without it the device answers `shizuku_unavailable`. CLI: `phonebridge install-apk https://…`
or `phonebridge install-apk --file app.apk`. MCP: `pb_install_apk`.

### 5.13 `shell` — Full

```json
{ "cmd": "shell", "args": { "command": "pm list packages -3", "timeout_ms": 15000 } }
{ "exit": 0, "stdout": "package:com.example\n", "stderr": "" }
```

Runs as the shell user through Shizuku; `timeout_ms` 1–3600000. CLI:
`phonebridge shell -- "pm list packages -3"`. MCP: `pb_shell`.

### 5.14 `files` — Full

```json
{ "cmd": "files", "args": { "op": "list", "path": "/sdcard/Download" } }
{ "op": "list", "entries": [ { "name": "a.txt", "path": "/sdcard/Download/a.txt",
                               "is_dir": false, "size": 12, "mtime": 1790000000000 } ] }

{ "cmd": "files", "args": { "op": "read", "path": "/sdcard/Download/a.txt" } }
{ "op": "read", "data_b64": "aGVsbG8=", "bytes": 5 }

{ "cmd": "files", "args": { "op": "write", "path": "/sdcard/Download/a.txt", "data_b64": "aGVsbG8=" } }
{ "op": "write", "ok": true, "bytes": 5 }

{ "cmd": "files", "args": { "op": "delete", "path": "/sdcard/Download/a.txt" } }
{ "op": "delete", "ok": true, "bytes": 0 }
```

`write` requires `data_b64`; other ops must not send it. CLI: `phonebridge files list /sdcard/Download`.
MCP: `pb_files`.

### 5.15 `settings` — Full

```json
{ "cmd": "settings", "args": { "namespace": "system", "key": "screen_brightness" } }
{ "value": "120" }
{ "cmd": "settings", "args": { "namespace": "system", "key": "screen_brightness", "value": "60" } }
{ "value": "60" }
```

CLI: `phonebridge settings system screen_brightness 60`. Library: `session.settings(...)`.

### 5.16 `relay_move` — Full

```json
{ "cmd": "relay_move", "args": { "to": "this" } }
{ "new_url": "wss://192.168.1.20:8443" }
```

`to` is `this` (the bot's host), a peer id, or a relay URL. The relay hands over and announces
`relay_moved`; peers reconnect to `new_url`. CLI: `phonebridge relay-move this`. MCP: `pb_relay_move`.

### 5.17 Pairing messages

`pair_request` and `pair_result` travel as sealed `route` payloads (section 2.2), not inside a Noise
session. `reason` values: `expired`, `used`, `unknown_code`, `bad_secret`, `bad_request`, `denied`,
`already_paired`, `unknown_bot`. The client maps `expired`/`used`/`unknown_code` to `expired`,
`bad_secret`/`bad_request` to `handshake_failed`, and everything else to `permission_denied`.

---

## 6. Error codes

Responses use `{ "ok": false, "error": { "code": …, "message": … } }`. The CLI prints
`error [code]: message`; MCP tools return `isError: true` with `{"ok":false,"error":{…}}` as text.
Unknown device codes are surfaced as `internal` with the original code in `details.remoteCode`.

| Code | Meaning |
| --- | --- |
| `permission_denied` | The bot's level is too low, or the user denied pairing. |
| `not_controller` | A Control/Full command was sent without holding control. |
| `busy` | The device is driven by another bot; queue and retry. |
| `bad_args` | Malformed arguments (missing coordinates, oversized message, bad chunk). |
| `unknown_cmd` | The device does not implement the command. |
| `peer_offline` | Phone or relay is unreachable, or the session was closed. |
| `accessibility_disabled` | The user turned off the PhoneBridge accessibility service. |
| `projection_denied` | MediaProjection screenshot consent was refused. |
| `shizuku_unavailable` | A Full-level command needs Shizuku, which is not running. |
| `timeout` | No response within the command timeout. |
| `internal` | Device-side failure; details in `message`. |
| `bad_code` | Pairing code has an illegal symbol or fails the CRC-5 check. |
| `bad_offer` | Offer JSON is malformed or `device_id` does not match `device_pub`. |
| `expired` | Pairing offer or short code expired, or was already used. |
| `bad_input` | CLI/MCP input is not a link, file, offer or code; also bad relay URL or pin. |
| `not_paired` | No paired device in the keystore. |
| `no_device` | The device reference matched nothing. |
| `ambiguous_device` | Several devices match; pass a peer id, prefix, name or fingerprint. |
| `handshake_failed` | Noise handshake, key pinning or decryption failed (also bad pairing secret). |
| `relay_error` | The relay refused or dropped the connection. |
| `unsupported` | Reserved for protocol extensions the peer does not know. |

---

## 7. MCP server

### 7.1 Run it

```sh
node mcp/dist/server/index.js                     # stdio
node mcp/dist/server/index.js --http 8765         # streamable HTTP at http://127.0.0.1:8765/mcp
node mcp/dist/server/index.js --home ~/.phonebridge --bot-name my-agent --device "Pixel 9"
```

Flags: `--http <port>`, `--host`, `--home`, `--bot-name`, `--device`, `--insecure-dev`, `--version`,
`--help`. All diagnostics go to stderr; stdout is protocol traffic only.

### 7.2 Tools

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
| `pb_request_control` | `device?` | Control | `{granted,position?}` |
| `pb_release_control` | `device?` | Control | `{}` |
| `pb_install_apk` | `url?`, `data_b64?`, `name?`, `device?` | Full | `{package}` |
| `pb_shell` | `command`, `timeout_ms?`, `device?` | Full | `{exit,stdout,stderr}` |
| `pb_files` | `op`, `path`, `data_b64?`, `device?` | Full | op-specific |
| `pb_relay_move` | `to`, `device?` | Full | `{new_url}` |

`device` accepts a peer id, a peer id prefix (6+ chars), a device name, or the fingerprint shown on the
phone (`ABCD-EFGH`). Omit it when only one device is paired or `--device` is set.

### 7.3 OpenCode (V2)

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

### 7.4 Claude Desktop

`claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/`, Windows: `%APPDATA%\Claude\`):

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

### 7.5 Claude Code

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

### 7.6 Generic MCP client

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

## 8. CLI reference

```text
phonebridge [options] <command> [args]
```

| Command | Example |
| --- | --- |
| `pair` | `phonebridge pair 7K4-Q9B --relay wss://relay.example:8443 --level control` |
| `devices` | `phonebridge devices` |
| `status` | `phonebridge status` |
| `screenshot` | `phonebridge screenshot -o shot.png` (raw base64url without `-o`; `--json` for the body) |
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

## 9. Chunking

Payloads larger than one transport message are split into `chunk` events:

```json
{ "event": "chunk", "data": { "chunk_id": "AAECAwQFBgc", "index": 0, "total": 12,
                              "data_b64": "…", "sha256": "<hex of the whole payload>" } }
```

- 256 KiB raw slices by default (hard ceiling 1 MiB), at most 8192 chunks, at most 64 MiB reassembled.
- `index` is 0-based and may arrive in any order; duplicates, gaps and a changing `total` are rejected.
- The receiver verifies the SHA-256 before handing the bytes to the caller.
- The announcing result (today only `screenshot`) carries `chunked:true`, `chunk_id`, `total`, `size`
  and `sha256`. The client waits for the chunks and adds `data_b64` to the result before returning it.

---

## 10. Library API

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
Advanced pieces (`E2ESession`, `NoiseXXHandshake`, `RelayConnection`, `Keystore`, pairing and chunk
helpers) are exported for tests and other transports.

---

## 11. Test vectors for `:core`

`mcp/test/vectors/handshake-v1.json` is the cross-language contract: fixed keys, the exact Noise
`hs1`/`hs2`/`hs3` frames, handshake hash, transport frames, the pairing key and sealed request/result,
the relay hello proof, chunk bytes and the RFC 5869 HKDF vector. See
[mcp/test/vectors/README.md](../mcp/test/vectors/README.md). Regenerate with
`npm run build && npm run vectors` after any protocol change and tell the `:core` lane.

The implementation has been exercised end to end against the **real Kotlin relay** (`:relay`, plain
`ws://` on loopback) for link pairing, short-code pairing through the relay mailbox, the Noise
handshake, command round-trips and events.
