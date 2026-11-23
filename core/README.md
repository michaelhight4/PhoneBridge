# core: protocol, crypto and pairing library (Kotlin/JVM)

Shared by the Android app, the relay, the desktop agent and the MCP/CLI bot tools. JVM 17 bytecode,
runs on Android API 26+ (Bouncy Castle lightweight crypto, no JDK-only APIs). Build with
`gradlew.bat :core:test`.

## What is here

| Package | Contents |
| --- | --- |
| `dev.phonebridge.core` | exceptions, `Version` |
| `.model` | every wire message: `RelayEnvelope`, pairing offer/request/result, command table with typed args and results, app request/response/event, error codes, permission levels |
| `.crypto` | X25519, ChaCha20-Poly1305/HKDF primitives, Noise `XX` handshake, `E2ESession`/`SecureChannel`, relay `hello` proof |
| `.identity` | `Identity` (X25519 key pair, stored JSON) and `PeerIds` (`peer_id`, fingerprint) |
| `.pairing` | short codes, links/`.pbpair`/QR payloads, sealed pairing payloads, `PairingManager`, `BotRegistry` (memory + JSON file) |
| `.control` | `ControlArbiter`: one controller, FIFO queue, 120 s idle release, `kickAll` |
| `.chunk` | `Chunker`/`ChunkAssembler` for payloads bigger than one envelope |

## Typical use

Relay:
```kotlin
val envelope = EnvelopeCodec.decode(text)          // 16 MiB cap enforced
if (!envelope.isKnownType) return                  // ignore, do not crash
val proofOk = HelloAuth.verify(
    relayPrivateKey = relay.keys.privateKey,
    relayPublicKey = relay.keys.publicKey,          // the per-connection key sent in `challenge`
    relayId = relay.peerId,
    peerId = hello.from!!,
    peerPublicKey = B64Url.decode(hello.pub!!),
    nonce = challengeNonce,
    proof = B64Url.decode(hello.sig!!),
)
```

Device side pairing:
```kotlin
val manager = PairingManager(deviceIdentity, JsonFileBotRegistry(path), clock = { System.currentTimeMillis() })
val offer = manager.createOffer("wss://relay.example:8443")   // show QR / link / code
"phonebridge://pair?d=" + ...                                  // or PairingLinks.link(offer)
val pending = manager.accept(code, sealedRequestBytes)          // decrypt + burn
val answer = manager.approve(pending)                           // or manager.deny(pending)
relay.send(RelayEnvelope.route(deviceId, pending.botId, answer.sealed))
```

Bot side:
```kotlin
val sealed = PairingClient.createRequest(offer, botIdentity, PermissionLevel.CONTROL)
val result = PairingClient.openResult(offer, sealedResultFromDevice)
```

End-to-end session (bot = initiator, device = responder):
```kotlin
val session = E2ESession(NoiseRole.INITIATOR, botIdentity.keys)
relay.send(RelayEnvelope.route(botId, deviceId, session.writeHandshakeFrame()))
session.readHandshakeFrame(inbound.payloadBytes())
session.writeHandshakeFrame()
val channel = session.channel!!
channel.requirePeer(pairedDeviceKey)                   // pin the key from the pairing record
val request = AppMessages.request(uuid(), CommandType.TAP, TapArgs(x = 540, y = 1200))
relay.send(RelayEnvelope.route(botId, deviceId, session.seal(AppMessages.encodeRequest(request).toByteArray())))
val response = AppMessages.parse(session.openText(inbound.payloadBytes())) as AppMessage.Response
```

Device side control arbitration, before executing a Control/Full command:
```kotlin
Commands.check(botLevel, request.cmd)                  // throws PermissionDeniedException
if (Commands.fromWire(request.cmd)!!.needsControl && !arbiter.canAct(botId)) return busy(...)
arbiter.touch(botId)
```

## Notes

- JSON uses one configuration: `PbJson` (`encodeDefaults = true`, `explicitNulls = false`,
  `ignoreUnknownKeys = true`).
- All binary fields are base64url without padding.
- `core` is the normative implementation of `docs/PROTOCOL.md`; change the doc with the code.
- Tests cover JSON round-trips of every message type, both handshake directions, tampering/replay,
  the short-code check symbol, link/file round-trips, offer expiry and single use with an injected
  clock, the permission table, arbiter queue/idle/kick and chunk reassembly.
