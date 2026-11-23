# PhoneBridge Protocol (draft v0.1)

Status: draft. Task 01 (core) owns this file and may refine it; any change must keep the other
tasks unblocked (add, don't break) and be noted in HANDOFF.md.

## Layers
1. **Transport:** WebSocket over TLS (`wss://`) between each peer (device or bot) and the relay.
   LAN/dev mode may use `ws://` only on loopback or with an explicit `--insecure-dev` flag.
2. **Relay framing:** JSON text frames the relay can route but not read (`Envelope`).
3. **End-to-end session:** Noise `XX` handshake (X25519, ChaChaPoly, BLAKE2s/SHA-256) between bot and
   device, or equivalent libsodium construction (X25519 key agreement + XChaCha20-Poly1305 with
   per-direction keys and counters). The relay never sees plaintext.
4. **Application messages:** JSON requests/responses/events inside the encrypted payload.

## Identities
- Every device and every bot has a long-term X25519 key pair (plus Ed25519 for signatures if needed).
- `peer_id` = base32(first 16 bytes of SHA-256(public key)), lowercase, no padding.
- Fingerprint shown to users: first 8 base32 chars grouped `ABCD-EFGH`.

## Relay envelope (plaintext to relay)
```json
{ "v": 1, "type": "hello|route|ping|pong|error|relay_moved",
  "from": "<peer_id>", "to": "<peer_id>", "id": "<uuid>",
  "payload": "<base64url ciphertext>" }
```
- `hello` (peer -> relay): `{ "v":1,"type":"hello","from":"<peer_id>","pub":"<b64 pubkey>","sig":"<b64 proof>","role":"device|bot" }`.
  The relay challenges with a nonce in `hello` reply; the peer signs/MACs it to prove key ownership.
- `route`: forwarded verbatim to `to` if connected, else relay returns `error {code:"peer_offline"}`.
- `relay_moved` (relay -> peers): `{ "new_url": "wss://...", "token": "<one-time>", "expires": <unix> }`.
- Relays MUST rate-limit per peer and MUST drop envelopes larger than 16 MiB (screenshots are chunked).

## Pairing
Pairing offer (created on the device; one-time; expires 10 minutes after creation):
```json
{ "v":1, "relay":"wss://host:port", "device_id":"<peer_id>", "device_pub":"<b64>",
  "secret":"<b64 32 bytes>", "code":"7K4-Q9M", "exp": 1790000000 }
```
- **QR:** the link below encoded as a QR code.
- **Link:** `phonebridge://pair?d=<base64url(JSON offer)>`
- **File:** `.pbpair` = the JSON offer as a file.
- **Short code:** `7K4-Q9M` (Crockford base32, 6 symbols, last symbol is a check). The bot sends
  `pair_lookup {code}` to a relay URL the user also gives it (or the default LAN relay); the relay holds
  the offer for the code for at most 10 minutes and releases it once.
- Bot -> device (through relay, encrypted with a key derived from `secret`):
  `pair_request { bot_name, bot_pub, requested_level }`
- Device shows **"Allow this bot?"** (name + fingerprint + requested level, user may lower it).
- Device -> bot: `pair_result { ok, granted_level, device_name }` or `{ ok:false, reason }`.
- The secret is burned on first use or expiry, whichever comes first.

## Application messages (inside E2E)
Request:
```json
{ "id":"<uuid>", "cmd":"tap", "args": { "x": 540, "y": 1200 } }
```
Response:
```json
{ "id":"<uuid>", "ok": true, "result": { } }
{ "id":"<uuid>", "ok": false, "error": { "code":"permission_denied", "message":"needs Control" } }
```
Event (device -> bot, unsolicited):
```json
{ "event":"notification|control_changed|queue_position|kicked|relay_moved", "data": { } }
```

### Commands
| cmd | level | args | result |
| --- | --- | --- | --- |
| `status` | View | – | `{device, battery, screen_on, controller, queue, shizuku}` |
| `screenshot` | View | `{format:"png|jpeg", quality?, max_width?}` | `{mime, width, height, data_b64}` (chunked if large) |
| `ui_tree` | View | `{max_depth?, visible_only?}` | `{root: Node}`; Node = `{id, cls, text, desc, res_id, bounds:[l,t,r,b], clickable, focusable, editable, children:[]}` |
| `notifications` | View | `{since?}` | `{items:[{pkg,title,text,time}]}` |
| `tap` | Control | `{x,y}` or `{node_id}` | `{}` |
| `long_press` | Control | `{x,y,ms?}` | `{}` |
| `swipe` | Control | `{x1,y1,x2,y2,ms?}` | `{}` |
| `type` | Control | `{text, node_id?}` | `{}` |
| `key` | Control | `{key:"back|home|recents|notifications|quick_settings|lock|enter|power_dialog"}` | `{}` |
| `open_app` | Control | `{package}` or `{label}` | `{}` |
| `request_control` | Control | – | `{granted}` or event `queue_position` |
| `release_control` | Control | – | `{}` |
| `install_apk` | Full | `{url}` or `{data_b64, name}` | `{package}` |
| `shell` | Full | `{command, timeout_ms?}` | `{exit, stdout, stderr}` |
| `files` | Full | `{op:"list|read|write|delete", path, data_b64?}` | op-specific |
| `settings` | Full | `{namespace:"system|secure|global", key, value?}` | `{value}` |
| `relay_move` | Full | `{to:"this|<peer_id>|<url>"}` | `{new_url}` |

### Error codes
`permission_denied`, `not_controller`, `busy`, `bad_args`, `unknown_cmd`, `peer_offline`,
`accessibility_disabled`, `projection_denied`, `shizuku_unavailable`, `timeout`, `internal`.

### Control arbitration
- Commands at Control/Full level require holding control. `request_control` grants it if free,
  else queues FIFO and emits `queue_position`. Control auto-releases after 120 s idle.
- The device's persistent notification shows the controller's name. "Kick everyone" revokes all
  sessions and requires re-auth (keys stay paired unless the user unpairs).
