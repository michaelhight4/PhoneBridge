# Relay wire contract (extension of docs/PROTOCOL.md)

`docs/PROTOCOL.md` is owned by the core task. This file records the concrete relay-level messages this
module implements. Everything here is **additive**: envelopes, identities, `hello`, `route`,
`relay_moved` fields, pairing offers and the 16 MiB / rate-limit rules all come from the base protocol
unchanged. The additions are the `challenge` message (the nonce a peer proves against), the `hello_ok`
confirmation, the two explicit short-code mailbox messages, the `relay_pin` field of a pairing offer and
the error codes the relay can return.

## 0. Pinning the relay in a pairing offer

A relay that terminates TLS with a self-signed certificate exposes the base64url SHA-256 of its DER
certificate (`RelayHandle.fingerprint`, printed by the standalone jar as `cert pin`). Add it to the
pairing offer next to the relay URL:

```json
{ "v": 1, "relay": "wss://192.168.1.20:8443",
  "relay_pin": "<base64url SHA-256 of the relay certificate>",
  "device_id": "<peer_id>", "device_pub": "<b64>", "secret": "<b64 32 bytes>",
  "code": "7K4-Q9M", "exp": 1790000000 }
```

The bot passes `relay_pin` as `RelayClientConfig.tlsPin`, so the WebSocket handshake only succeeds
against that exact certificate. A relay behind a normal CA certificate omits `relay_pin` and the client
uses the system trust store. This field is optional and additive; `docs/PROTOCOL.md` keeps owning the
offer definition.

## 1. Connection and authentication

```
peer                                relay
 | -------- WebSocket upgrade -------> |
 | <------------- challenge ---------- |   nonce, relay ephemeral key, relay id
 | ---------------- hello -----------> |   pub, nonce echo, proof
 | <------------- hello_ok ----------- |
 | <============= route =============> |   from here on: forwarding only
```

### challenge (relay -> peer, the first frame)

```json
{ "v": 1, "type": "challenge",
  "nonce": "<b64url, 32 random bytes>",
  "relay_pub": "<b64url, 32 bytes X25519, per-connection ephemeral>",
  "relay_id": "<peer_id of the relay>",
  "proof": "dh-hmac-sha256",
  "expires": 1790000030 }
```

The relay sends this immediately after the upgrade. An unauthenticated connection that sends anything
else receives `error {code:"unauthorized"}` and is closed. Connections must authenticate within
`helloTimeoutMs` (default 30 s).

A peer that sends `hello` before it reads the challenge (the literal reading of "the relay challenges
with a nonce in the `hello` reply") is not disconnected: the relay answers with the challenge again,
up to three times, and the peer then sends `hello` with that nonce.

### hello (peer -> relay)

```json
{ "v": 1, "type": "hello",
  "from": "<peer_id>",
  "pub": "<b64url, 32 bytes X25519 long-term public key>",
  "nonce": "<the challenge nonce, verbatim>",
  "sig": "<b64url, 32 byte proof>",
  "role": "device|bot",
  "move_token": "mv1.<expires>.<nonce>.<hmac>" }
```

Proof of key ownership (`sig`) for `proof: dh-hmac-sha256`:

```
shared   = X25519(peer_private, relay_pub)
context  = "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>"
sig      = HMAC-SHA256(key = shared, message = context)
```

Only the holder of the peer private key can compute it. It is bound to the relay's per-connection
ephemeral key and to the fresh nonce, so a captured `hello` cannot be replayed on another connection,
and a man in the middle cannot splice keys. The relay verifies with
`shared = X25519(relay_ephemeral_private, peer_pub)` and compares in constant time.

The relay rejects the connection when:

| Reason | Error code |
| --- | --- |
| `from` is not `peer_id(pub)` | `unauthorized` |
| the public key is not 32 bytes or produces an all-zero shared secret | `unauthorized` |
| `nonce` is missing or does not match the challenge | `unauthorized` |
| `sig` is missing, malformed or wrong | `unauthorized` |
| a supplied `move_token` is invalid or expired | `unauthorized` |
| a supplied `move_token` was already redeemed | `token_used` |

`peer_id = Crockford base32 (lowercase, no padding) of the first 16 bytes of SHA-256(public key)`,
26 characters, as in `docs/PROTOCOL.md`. The user-visible fingerprint stays `ABCD-EFGH`.

### hello_ok (relay -> peer)

```json
{ "v": 1, "type": "hello_ok", "to": "<peer_id>", "relay_id": "<peer_id>",
  "peer": "<peer_id>", "server_time": 1790000000,
  "limits": { "max_envelope_bytes": 16777216, "rate_messages": 240, "rate_window_ms": 1000 } }
```

A second connection for a `peer_id` that is already connected **replaces** the old one: the old
connection is closed with WebSocket code `4001` so the peer knows to stop reconnecting from it.

## 2. Forwarding

### route

Unchanged from `docs/PROTOCOL.md`:

```json
{ "v": 1, "type": "route", "from": "<peer_id>", "to": "<peer_id>", "id": "<uuid>",
  "payload": "<base64url ciphertext>" }
```

- The relay forwards the **received frame verbatim**. It does not reorder, rewrite or inspect
  `payload`, and it never stores it.
- `from`, when present, must equal the authenticated peer id. Anything else is `bad_envelope` and the
  envelope is dropped (a peer cannot spoof another peer).
- A peer cannot route to itself (`bad_envelope`).
- If `to` is not connected: `error {code:"peer_offline", id:<envelope id>}` goes back to the sender.
- If the receiver's socket fails mid-send, the receiver is unregistered and the sender gets
  `peer_offline`.

### ping / pong

```json
{ "v": 1, "type": "ping", "from": "<peer_id>", "id": "<uuid>" }
{ "v": 1, "type": "pong", "to": "<peer_id>", "id": "<uuid>" }
```

WebSocket-level ping/pong is handled by the transport as well: the relay pings an idle connection and
drops it after three unanswered pings.

## 3. Short-code pairing mailbox

The device stores its pairing offer under the short code; a bot looks the code up and gets it once.

```json
{ "v": 1, "type": "pair_store", "from": "<peer_id>", "code": "7K4-Q9M", "offer": { ... } }
{ "v": 1, "type": "pair_stored", "to": "<peer_id>", "code": "7K4-Q9M", "expires": 1790000600 }

{ "v": 1, "type": "pair_lookup", "from": "<peer_id>", "code": "7K4-Q9M" }
{ "v": 1, "type": "pair_offer", "to": "<peer_id>", "code": "7K4-Q9M",
  "offer": { ... }, "expires": 1790000600 }
```

- Both messages require an authenticated `hello`. The offer itself is the JSON from
  `docs/PROTOCOL.md` and is stored opaquely.
- Codes are normalized: case, separators, and the Crockford confusables `O` -> `0`, `I`/`L` -> `1`.
  The mailbox key is the canonical six symbol form (`7K4-Q9M`). A wrong check symbol is tolerated by
  the relay (the device's own check algorithm stays authoritative).
- The offer is released **once**: the entry is removed by the first successful lookup.
- The offer expires after `pairMailboxTtlMs` (10 minutes per the protocol). A lookup after expiry is
  `code_expired`; an unknown code is `code_not_found`.
- Offers are capped at 16 KiB and the mailbox at 512 entries (`mailbox_full`).

## 4. relay_move / relay_moved

The relay that is handing over sends every connected peer:

```json
{ "v": 1, "type": "relay_moved", "to": "<peer_id>", "id": "<uuid>",
  "new_url": "wss://new-host:8443", "token": "mv1.1790000060.<nonce>.<hmac>",
  "expires": 1790000060 }
```

- `new_url` and `expires` (unix seconds) follow `docs/PROTOCOL.md`; `token` is the additive part.
- Each token is bound to one `peer_id` and expires:
  `hmac = HMAC-SHA256(moveSecret, "mv1|<expiresEpochMillis>|<nonce>|<peer_id>")`,
  token = `mv1.<expiresEpochMillis>.<nonce>.<hmac>`.
- The new relay has the same `moveSecret` (shared out of band, e.g. when the user starts the new relay)
  and accepts the token in `hello.move_token` **once**: a redeemed `(peer_id, nonce)` is remembered
  until it expires, so a replay is `token_used`.
- A peer that never receives `relay_moved` is simply unable to route until it reconnects; the old
  relay keeps running until its owner stops it (the app decides when to call `stop()`).
- Peers that reconnect without a token are still authenticated normally. The token is an admission
  ticket, not a replacement for the key ownership proof.

## 5. Errors

```json
{ "v": 1, "type": "error", "to": "<peer_id>", "id": "<offending envelope id or null>",
  "error": { "code": "peer_offline", "message": "peer <id> is not connected" } }
```

| code | meaning |
| --- | --- |
| `unauthorized` | not authenticated, bad proof/nonce/key, or a rejected move token |
| `token_used` | the move token was already redeemed |
| `bad_envelope` | not a JSON object, no type, missing/incorrect fields, spoofed `from`, self route |
| `unknown_type` | a message type this relay does not implement |
| `already_authenticated` | a second `hello` on an authenticated connection |
| `peer_offline` | the destination peer is not connected |
| `rate_limited` | the per-peer token bucket is empty; the message was dropped |
| `mailbox_full` | the short-code mailbox is full or the offer is too large |
| `bad_args` | malformed short code or missing offer |
| `code_not_found` | no offer waits under that code |
| `code_expired` | the offer existed but its ten minutes ran out |
| `too_large` | reported by the client when the relay closes with WebSocket code `1009` |

## 6. Limits and transport rules

- Text frames only; the relay never sends or accepts binary envelopes.
- Oversize frames: the relay rejects any frame over `maxEnvelopeBytes` (16 MiB by default) *before*
  buffering it, closes with WebSocket code `1009` and forwards nothing.
- Rate limiting: a token bucket per authenticated peer, `maxMessages` per `windowMs` with a `burst`
  capacity (default 240/1000 ms). Excess messages are dropped and answered with at most one
  `rate_limited` error per second; more than 500 violations closes the connection (1008).
- Unauthenticated connections may send at most ten messages before being closed.
- Clients must mask frames, servers must not (RFC 6455); violations close the connection (1002).
- Idle connections are pinged after `idleTimeoutMs` and dropped after three unanswered pings.
- A hard cap of `maxConnections` (512 by default) keeps a relay from exhausting its host; extra
  connections receive HTTP 503.

## 7. Compatibility

All additions use new `type` values and optional fields, so a peer that implements only
`docs/PROTOCOL.md` still connects, authenticates and routes. If the core task changes the `hello`
proof format, keep the `proof` string in `challenge` as the negotiation point; the relay currently
answers `dh-hmac-sha256` and nothing else.
