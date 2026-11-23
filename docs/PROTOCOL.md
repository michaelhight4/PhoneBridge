# PhoneBridge Protocol (v1)

Status: implemented by `core/` (task 01) and by the relay (`relay/PROTOCOL-RELAY.md` refines the
relay-facing messages). This file is the normative description of the bytes on the wire; `core/` is the
reference implementation and its tests encode every rule below. Changes must be additive (new optional
fields, new message types) and must be noted in HANDOFF.md.

## 1. Layers

1. **Transport:** WebSocket over TLS (`wss://`) between each peer (device or bot) and the relay.
   LAN/dev mode may use `ws://` only on loopback or with an explicit `--insecure-dev` flag.
2. **Relay framing:** JSON text frames the relay can route but not read (`RelayEnvelope`).
3. **End-to-end session:** Noise `XX` (`Noise_XX_25519_ChaChaPoly_SHA256`, revision 34) between bot and
   device. The relay never sees plaintext.
4. **Application messages:** JSON requests/responses/events inside the encrypted payload.

Binary values (`pub`, `sig`, `nonce`, `relay_pub`, `payload`, `secret`, `d`, `data_b64`) are **base64url
without padding** (`-`/`_`, no `=`). Decoders accept standard `+`/`/`, padding and whitespace so values
pasted from other tools still work. JSON is UTF-8. Unknown object keys are always ignored.

## 2. Identities

- Every device and every bot has one long-term **X25519 key pair** (32-byte private, 32-byte public).
- `peer_id` = **lowercase Crockford base32** (alphabet `0123456789abcdefghjkmnpqrstvwxyz`, i.e. no
  `i`, `l`, `o`, `u`; no padding) of the **first 16 bytes of `SHA-256(public_key)`**. Always 26
  characters. The relay uses the same alphabet, so ids match everywhere.
- User-facing **fingerprint** = the first 8 characters of `peer_id`, uppercased and grouped, e.g.
  `CC6W-TAB6`. Show fingerprints when pairing and in the bot list; never show the raw key.
- Stored identity JSON (this is what `core` writes; the Android app should wrap it with the Keystore):
  ```json
  { "v": 1, "role": "device|bot", "private_key": "<b64url>", "public_key": "<b64url>",
    "name": "Pixel 9", "created_at": 1790000000000 }
  ```
  A stored public key that does not match its private key is rejected on load.

Test vector: public key `000102…1f` gives `peer_id` `cc6wtab6rgspd48jah4bqcjv9w` and fingerprint
`CC6W-TAB6`.

## 3. Relay envelopes

```json
{ "v": 1, "type": "challenge|hello|hello_ok|route|ping|pong|error|relay_moved|
                 pair_store|pair_stored|pair_lookup|pair_offer",
  "from": "<peer_id>", "to": "<peer_id>", "id": "<uuid>", "payload": "<b64url>" }
```

All fields are optional except `v` and `type`; each type uses its own subset. `v` is always written.

| type | direction | fields |
| --- | --- | --- |
| `challenge` | relay -> peer | `nonce`, `relay_pub`, `relay_id`, `proof`, `expires` |
| `hello` | peer -> relay | `from`, `pub`, `sig`, `role` (`device`/`bot`), `nonce` (echo), `move_token`? |
| `hello_ok` | relay -> peer | `to`, `relay_id`, `peer`, `server_time`, `limits` |
| `route` | relay -> peer | `from`, `to`, `id`, `payload` |
| `ping` / `pong` | either | `from` / `to`, `id` (echoed) |
| `error` | relay -> peer | `to`, `id` (echo), `error:{code,message}` |
| `relay_moved` | relay -> peer | `new_url`, `token`, `expires` (unix seconds) |
| `pair_store` | device -> relay | `from`, `code`, `offer` (offer JSON object) |
| `pair_stored` | relay -> device | `to`, `code`, `expires` |
| `pair_lookup` | bot -> relay | `from`, `code`, `id` |
| `pair_offer` | relay -> bot | `to`, `code`, `offer`, `expires`, `id` |

### 3.1 `challenge`, `hello` and key proof

The relay cannot sign and X25519 cannot sign, so proof of key ownership is a DH-MAC challenge:

1. Immediately after the WebSocket upgrade the relay sends
   ```json
   { "v":1, "type":"challenge", "nonce":"<b64url 32 random bytes>",
     "relay_pub":"<b64url fresh X25519 public key>", "relay_id":"<peer_id of relay>",
     "proof":"dh-hmac-sha256", "expires":1790000030 }
   ```
   A fresh `relay_pub` **per connection** is required.
2. The peer answers
   ```json
   { "v":1, "type":"hello", "from":"<peer_id>", "pub":"<b64url own key>",
     "role":"device|bot", "nonce":"<echo>", "sig":"<b64url proof>", "move_token":"mv1.…"? }
   ```
   with
   ```
   shared  = X25519(peer_private, relay_pub)
   context = "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>"
   sig     = HMAC-SHA256(key = shared, message = context)
   ```
   This proves the peer holds the private key for `pub`; it is bound to the relay's ephemeral key, its
   id and the fresh nonce, so a captured `hello` cannot be replayed on another connection.
3. The relay verifies with `X25519(relay_private, peer_pub)`, in constant time, and answers
   ```json
   { "v":1, "type":"hello_ok", "to":"<peer_id>", "relay_id":"<peer_id>",
     "peer":"<peer_id>", "server_time":1790000000,
     "limits":{ "max_envelope_bytes":16777216, "rate_messages":240, "rate_window_ms":1000 } }
   ```
   `from` must equal `peer_id(pub)`, otherwise the relay answers
   `error {code:"unauthorized"}` and closes. `move_token` (when present) is an admission ticket from a
   `relay_moved` handover and is redeemed once.

### 3.2 Routing and limits

- `route` is forwarded verbatim to `to` if that peer is connected; otherwise the relay answers
  `error {code:"peer_offline", id:<envelope id>}`.
- Relays MUST rate-limit per peer and MUST drop envelopes larger than **16 MiB** (`EnvelopeCodec.MAX_BYTES`,
  measured on the UTF-8 JSON text). Screenshots are chunked (section 8).
- Relay error codes (nested `error.code`): `unauthorized`, `token_used`, `bad_envelope`, `unknown_type`,
  `already_authenticated`, `peer_offline`, `rate_limited`, `mailbox_full`, `bad_args`, `code_not_found`,
  `code_expired`, `too_large`, `internal`.
- `ping`/`pong` keep the socket alive; `pong` echoes the `ping` id.
- `relay_moved` announces a new relay address. Peers reconnect to `new_url` and present `token` once.
- Unknown `type` values MUST be ignored by relays and peers, never treated as fatal (forward
  compatibility). `RelayEnvelope.isKnownType` reports whether this version understands one.

## 4. End-to-end session

### 4.1 Handshake

- Protocol name: `Noise_XX_25519_ChaChaPoly_SHA256`, handshake pattern `XX`:
  ```
  -> e
  <- e, ee, s, es
  -> s, se
  ```
- Prologue (both sides, ASCII): `PhoneBridge/1/noise-xx`. Both static public keys travel encrypted, so
  the relay sees only ephemeral-looking bytes.
- After the third message each side has the peer's static key. **Each side MUST compare it to the key of
  the bot/device it paired with** (`SecureChannel.requirePeer`) before trusting the channel: Noise `XX`
  alone does not tell you *who* the peer is.

### 4.2 Frames

Every end-to-end byte string that travels in a `route.payload` is a **frame**:

```
frame = type(1 byte) || body
 0x01 = Noise handshake message
 0x02 = Noise transport ciphertext
```

Handshake messages are at most 65535 bytes. With 32-byte keys they are 32, 96 and 64 bytes (plus the
1-byte frame tag), which matches the sizes in the Noise spec for `DHLEN = 32`.

### 4.3 Transport

- Two independent keys, one per direction, from Noise `Split()`; the initiator uses the first key to
  send and the second to receive, the responder the reverse.
- Nonce: Noise nonce, i.e. the 64-bit message counter **little-endian** followed by four zero bytes
  (96-bit ChaCha20-Poly1305 nonce). Counter starts at 0 and increments after every successful
  encryption/decryption.
- Associated data is empty for transport messages.
- Messages must be processed strictly in order. A message that arrives with the wrong counter does not
  authenticate and the counter does not advance, so **replays, reordering and tampering are rejected**.
  A session that needs parallel streams must use several sessions or sequence numbers inside the
  payload, not out-of-order Noise.
- Plaintext cap per transport message: **8 MiB** (`NoiseTransport.MAX_PLAINTEXT_BYTES`). Bigger
  application payloads are chunked (section 8).
- Channel binding: both sides can compare the handshake hash (`SecureChannel.handshakeHash`).

## 5. Pairing

### 5.1 Offer

Created on the device; one-time; expires 10 minutes after creation.

```json
{ "v": 1, "relay": "wss://host:8443", "device_id": "<peer_id>", "device_pub": "<b64url>",
  "secret": "<b64url 32 random bytes>", "code": "7K4-Q9B", "exp": 1790000600,
  "device_name": "Pixel 9", "relay_pin": "<b64url SHA-256 of the relay certificate>" }
```

- `exp` is **unix seconds**, creation time + 600. `device_name` is optional but recommended.
- `relay_pin` is optional and additive (see `relay/PROTOCOL-RELAY.md`): when the relay terminates TLS
  with a self-signed certificate it publishes the base64url SHA-256 of its DER certificate, and the bot
  pins it in its relay client. Without it a bot cannot reach a self-signed relay, because the system
  trust store does not know the certificate. A relay behind a normal CA certificate omits it.
- The receiver validates: `v == 1`, `relay` starts with `ws://` or `wss://`, `secret` is 32 bytes,
  `device_pub` is 32 bytes, `device_id == peer_id(device_pub)`, `code` passes the check symbol,
  `exp > 0`, and `relay_pin` (when present) decodes to 32 bytes.
- The device **burns** the secret on the first successful decrypt of a `pair_request`, or at `exp`,
  whichever comes first. A burned secret is dead even if the user never answered.

### 5.2 Transfer forms

- **QR:** the link below, verbatim.
- **Link:** `phonebridge://pair?d=<base64url(UTF-8 offer JSON)>`. Extra query parameters are ignored;
  percent-encoded payload bytes are decoded.
- **File:** `.pbpair`, UTF-8 offer JSON (indented by `core`); a UTF-8 BOM is tolerated.
- **Short code:** `7K4-Q9B`: six symbols, display form has a dash after the third. The device stores the
  offer with `pair_store {code, offer}` and the relay answers `pair_stored {expires}`. The bot sends
  `pair_lookup {code}` and the relay answers `pair_offer {code, offer, expires}` (or
  `error {code:"code_not_found"|"code_expired"}`). The relay holds an offer for a code for at most ten
  minutes and releases it **once**.
  - Alphabet: Crockford base32 `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (no `I`, `L`, `O`, `U`).
  - Input normalisation: case folded, `-`/space/`.`/`_` dropped, `O`→`0`, `I`→`1`, `L`→`1`. `U` is
    invalid.
  - Layout: five data symbols (25 bits) plus one **check symbol**.
  - Check symbol: CRC-5 over the 25 data bits, MSB first, polynomial `0x05` (x⁵ + x² + 1), initial
    value `0x1F`, no reflection, no final XOR; the resulting 5-bit value is the symbol. This catches
    any single wrong symbol.
  - Examples: `7K4Q9` → check `B` → `7K4-Q9B`; `ZZZZZ` → check `Q`; `00000` → check `T`.
  - **The device owns the check algorithm.** The relay treats the six symbols as an opaque mailbox key
    and must not reject a code whose check symbol it would compute differently
    (see `relay/PROTOCOL-RELAY.md`).

### 5.3 Request and result

Both travel as `route` envelopes between the bot and `device_id` (no Noise session yet), with
`payload = base64url(sealed)`.

```json
pair_request  { "v":1, "bot_name":"helper", "bot_pub":"<b64url>", "requested_level":"view|control|full",
                "bot_id":"<peer_id>" }
pair_result   { "v":1, "ok":true, "granted_level":"control", "device_name":"Pixel 9",
                "device_id":"<peer_id>" }
pair_result   { "v":1, "ok":false, "reason":"denied", "device_name":"Pixel 9", "device_id":"<peer_id>" }
```

`reason` is one of `expired`, `used`, `unknown_code`, `bad_secret`, `bad_request`, `denied`,
`already_paired`, `unknown_bot`.

### 5.4 Pairing payload crypto

- Key: `HKDF-SHA256(ikm = secret, salt = "PhoneBridge/1/pairing", info = "pair-frame", L = 32)`.
- Seal: ChaCha20-Poly1305 with a fresh random 12-byte nonce per message.
  `sealed = nonce(12) || ciphertext || tag(16)`.
- Associated data binds the direction and the device key:
  - request: `ASCII("PhoneBridge/1/pair|request|") || device_pub_bytes`
  - result:  `ASCII("PhoneBridge/1/pair|result|")  || device_pub_bytes`
- A `pair_result` that does not decrypt is `bad_secret`; a request from another device cannot be
  redirected because its AAD pins the device key.

### 5.5 Device-side flow

1. `PairingManager.createOffer(relay)` → offer (stored in memory, code and secret mapped).
2. Bot builds the sealed `pair_request`; the relay routes it to the device.
3. `PairingManager.accept(code|offer, sealed)` decrypts, validates, **burns the offer**, and returns a
   `PendingApproval` (name, fingerprint, requested level).
4. Device UI shows "Allow this bot?". `approve(level)` stores a `BotRecord` in the registry and returns
   the sealed `pair_result`; `deny(reason)` answers without storing anything.
5. After approval the bot opens Noise `XX` with the device (not the relay), and both sides pin the
   other's static key from the pairing record.

`revoke(botId)` (unpair) only affects that bot. Disabling a bot keeps its keys but refuses new sessions.

## 6. Application messages

Inside the encrypted transport, JSON objects with exactly one of `cmd` / `ok` / `event`:

```json
Request:  { "id":"<uuid>", "cmd":"tap", "args": { "x": 540, "y": 1200 } }
Response: { "id":"<uuid>", "ok": true,  "result": { } }
Response: { "id":"<uuid>", "ok": false, "error": { "code":"permission_denied", "message":"needs Control" } }
Event:    { "event":"queue_position", "data": { "position": 2 } }
```

`id` is echoed exactly. `args` defaults to `{}`; unknown command names produce
`error {code:"unknown_cmd"}`. `args` for the wrong command shape produce `bad_args`.

### 6.1 Commands

Permission levels are ordered **View < Control < Full**. `Commands.requiredLevel(cmd)` is the table.

| cmd | level | args | result |
| --- | --- | --- | --- |
| `status` | view | `{}` | `{device, battery, screen_on, controller, queue, shizuku}` |
| `screenshot` | view | `{format:"png"\|"jpeg", quality?, max_width?}` | `{mime, width, height, data_b64?}` or chunked (section 8) |
| `ui_tree` | view | `{max_depth?, visible_only?}` | `{root: Node}` |
| `notifications` | view | `{since?}` | `{items:[{pkg,title,text,time}]}` |
| `tap` | control | `{x,y}` or `{node_id}` | `{}` |
| `long_press` | control | `{x,y,ms?}` or `{node_id, ms?}` | `{}` |
| `swipe` | control | `{x1,y1,x2,y2,ms?}` | `{}` |
| `type` | control | `{text, node_id?}` | `{}` |
| `key` | control | `{key:"back\|home\|recents\|notifications\|quick_settings\|lock\|enter\|power_dialog"}` | `{}` |
| `open_app` | control | `{package}` or `{label}` | `{}` |
| `request_control` | control | `{}` | `{granted, position?}` or `queue_position` event |
| `release_control` | control | `{}` | `{}` |
| `install_apk` | full | `{url}` or `{data_b64, name}` | `{package}` |
| `shell` | full | `{command, timeout_ms?}` | `{exit, stdout, stderr}` |
| `files` | full | `{op:"list\|read\|write\|delete", path, data_b64?}` | see below |
| `settings` | full | `{namespace:"system\|secure\|global", key, value?}` | `{value}` |
| `relay_move` | full | `{to:"this"\|"<peer_id>"\|"<url>"}` | `{new_url}` |

Argument rules enforced by `core` (violations are `bad_args`):

- `screenshot.format` is `png` or `jpeg`; `quality` 1..100; `max_width` 16..16384.
- `ui_tree.max_depth` 1..1000; `visible_only` defaults to `true`.
- `tap` takes `x`+`y` **or** `node_id`, never both, never neither; `long_press` follows the same rule
  plus an optional `ms`.
- `long_press.ms` and `swipe.ms` 1..60000; `swipe` needs two different points.
- `open_app` takes exactly one of `package`/`label`; `install_apk` exactly one of `url`/`data_b64`
  (https URLs only, and an inline APK needs `name`).
- `files write` requires `data_b64`; other ops must not send it.
- `shell.timeout_ms` 1..3600000.

Results:

- `status`: `{device:"Pixel 9", battery:88, screen_on:true, controller:"<peer_id>|null",
  queue:["<peer_id>"], shizuku:true}` (`battery` percent, `shizuku` = the Shizuku service is alive).
- `screenshot` inline: `{mime:"image/png", width, height, data_b64:"<b64url of the image>"}`.
- `screenshot` chunked: `{mime, width, height, chunked:true, chunk_id, total, size, sha256}`; the pixels
  arrive as `chunk` events (section 8).
- `ui_tree`: `Node = {id, cls, text, desc, res_id, bounds:[l,t,r,b], visible?, clickable, focusable,
  editable, children:[]}`. `bounds` always has four integers; `visible` is optional.
- `notifications`: items carry `pkg`, `title`, `text`, `time` (epoch millis).
- `request_control`: `{granted:true}` or `{granted:false, position:n}`; a queued bot also gets a
  `queue_position` event when its position changes.
- `files list`: `{op:"list", entries:[{name, path, is_dir, size, mtime}]}`; `read`:
  `{op:"read", data_b64, bytes}`; `write`/`delete`: `{op:"write", ok:true, bytes}`.
- `install_apk`: `{package:"com.example"}`.
- `shell`: `{exit:0, stdout:"…", stderr:"…"}` (stdout/stderr truncated by the device if huge).
- `settings`: `{value:"1"}` (null when reading an unset key).
- `relay_move`: `{new_url:"wss://…"}`.

### 6.2 Error codes

`permission_denied`, `not_controller`, `busy`, `bad_args`, `unknown_cmd`, `peer_offline`,
`accessibility_disabled`, `projection_denied`, `shizuku_unavailable`, `timeout`, `internal`.

Codes arrive as strings; unknown codes decode without failing so newer devices can add codes.

### 6.3 Events (device -> bot, unsolicited)

| event | data |
| --- | --- |
| `notification` | `{item:{pkg,title,text,time}}` |
| `control_changed` | `{controller:"<peer_id>|null", name?}` |
| `queue_position` | `{position:n}` (1-based) |
| `kicked` | `{reason?}` — all sessions revoked, re-auth required |
| `relay_moved` | `{new_url, token?, expires?}` |
| `chunk` | `{chunk_id, index, total, data_b64, sha256}` (section 8) |

## 7. Permission and control model

- `view` may run `status`, `screenshot`, `ui_tree`, `notifications`, concurrently with others.
- `control` may additionally run commands that act on the device, and must hold control to do so.
  Full-level commands (`install_apk`, `shell`, `files`, `settings`, `relay_move`) act on the device as
  well, so they need control too: `CommandType.needsControl` is true for every Control and Full
  command, and only `request_control`/`release_control` are exempt.
- `full` may additionally run `install_apk`, `shell`, `files`, `settings`, `relay_move`.
- Exactly one bot drives at a time. `request_control` grants immediately when free, otherwise queues
  FIFO and reports `position`; a queued bot gets `busy` when it tries to act without control.
- Control auto-releases after **120 s** with no command activity; the next queued bot is promoted (and
  receives `control_changed`). Any accepted Control/Full command counts as activity.
- "Kick everyone" (`ControlArbiter.kickAll`) revokes the controller and empties the queue, sending
  `kicked` to each. Pairings (keys) survive; sessions do not.

## 8. Chunking large payloads

A payload larger than what fits comfortably in one message is split into `chunk` events:

```json
{ "event":"chunk", "data": { "chunk_id":"<b64url 8 random bytes>", "index":0, "total":12,
                             "data_b64":"<b64url of this slice>", "sha256":"<hex of the whole payload>" } }
```

- Chunk size: 256 KiB raw (`Chunker.CHUNK_SIZE`), hard ceiling 1 MiB per chunk. That keeps a chunk
  event and its envelope far below both the 8 MiB transport cap and the 16 MiB relay cap.
- `index` is 0-based and must appear exactly once; `total` must be identical on every chunk and at
  most 8192; the reassembled payload is capped at 64 MiB.
- `sha256` is lowercase hex of the **whole** payload and is repeated on every chunk; the receiver
  verifies it before handing the bytes to the caller and drops the payload if it does not match.
- Chunks may arrive in any order and relative to the response that announced them (`chunk_id` keys
  them). An empty payload is one chunk with empty `data_b64`.
- The announcing result (today only `screenshot`) carries `chunked:true` plus `chunk_id`, `total`,
  `size`, `sha256`, so a bot knows what to expect.

## 9. Versioning

- `v` is `1` everywhere. A peer that sees a higher version may still try to parse (unknown fields are
  ignored); an `unknown_cmd` failure is better than a crash.
- Never renumber or reuse a field name with different semantics. Add optional fields instead.
- New command names must be added to the permission table; unknown names are `unknown_cmd`.
- New envelope types must be ignorable (section 3.2) and documented here.
