# Relay ("the heart")

The relay forwards **encrypted** envelopes between paired peers. It never sees a command, a
screenshot or a key: it reads only the routing fields (`type`, `from`, `to`, `id`, `code`) and passes
the rest through byte for byte.

It runs in three places from the same code:

| Use | How |
| --- | --- |
| Inside the Android app (minSdk 26) | embed `Relay.start(RelayConfig(...))` |
| Inside the desktop app | same embedding API |
| A little VPS / a spare machine | `gradlew.bat :relay:run --args="--port 8443"` or the standalone jar |

The relay is built on the shared `:core` library — `RelayEnvelope`/`EnvelopeCodec` for messages,
`HelloAuth` for the challenge proof, `Identity`/`PeerIds` for identities, `ShortCode` for pairing
codes — so it adds no protocol code of its own and no extra dependency beyond what `:core` already
brings (kotlinx-serialization and Bouncy Castle; see [../docs/THIRD_PARTY.md](../docs/THIRD_PARTY.md)).
The WebSocket transport (RFC 6455 server and client), the TLS material and the self-signed certificate
are part of this module.

## Quick start (standalone)

```bash
# 1. Development relay, plain ws on loopback only
gradlew.bat :relay:run --args="--port 8443 --bind 127.0.0.1 --no-tls --data-dir build/relay-data"

# 2. Normal relay: generates a self-signed certificate on first run and prints its pin
gradlew.bat :relay:run --args="--port 8443 --data-dir build/relay-data"

# 3. Single file jar for a VPS
gradlew.bat :relay:shadowJar
java -jar relay/build/libs/phonebridge-relay-all.jar --port 8443 --data-dir /var/lib/phonebridge
```

The relay prints its URL and its certificate fingerprint. Put that fingerprint in the pairing offer so
peers pin it on first contact:

```
PhoneBridge relay
  url             wss://192.168.1.20:8443
  relay id        bpbf55sx88nkgtnbjcks9jtasr
  cert pin        gAE-I7hHRx-RlZGgs9lOc5R2XOwxzK1O07ckH1ocqlI
  cert sha256     80:01:3E:23:B8:47:47:1F:91:95:91:A0:B3:D9:4E:73:94:76:5C:EC:31:CC:AD:4E:D3:B7:24:1F:5A:1C:AA:52
  health          https://127.0.0.1:8443/healthz
```

`GET /healthz` answers `{"ok":true,"relay_id":...,"peers":n,"uptime_ms":n}` for uptime checks (no
peer identities are exposed).

Use your own certificate instead of the generated one with
`--keystore relay.p12 --keystore-password ...` (or the `PHONEBRIDGE_KEYSTORE_PASSWORD` environment
variable; secrets are never printed). With a CA-issued certificate the client verifies both the chain
and the host name; with `relay_pin` it verifies the certificate fingerprint instead (the generated
certificate has no subject alternative name, which is why it is pinned rather than host-name checked).

If the data directory cannot be written (a read-only host, or an app directory the phone refuses), the
relay still starts and serves TLS, but the self-signed certificate cannot be kept: the printed
fingerprint changes after every restart and peers have to re-pin it. `RelayHandle` does not hide this:
the startup log says so, and a corrupt keystore is replaced rather than blocking the relay.

## Embedding

```kotlin
val relay = Relay.start(
    RelayConfig(
        port = 8443,
        bindAddress = "0.0.0.0",              // LAN reachable; TLS is required off loopback
        tls = TlsConfig.selfSigned(dataDir),  // or TlsConfig.keyStore(path, password)
        dataDir = dataDir,                    // relay identity + certificate
        moveSecret = sharedSecretWithNextRelay,
        log = { Log.i("phonebridge-relay", it) }
    )
)

val offer = mapOf("relay" to relay.url, "relay_pin" to relay.fingerprint) // add to the pairing offer
// ...
relay.stop()   // the heartbeat stops with the app: that is the point
```

`RelayHandle` also exposes `relayId`, `port`, `moveTo(newUrl)` and `stats()`.

### Settings

| `RelayConfig` | Default | Meaning |
| --- | --- | --- |
| `port` | `8443` | listen port, `0` picks a free one |
| `bindAddress` | `0.0.0.0` | interface to listen on |
| `advertisedHost` | bind address | host printed in `url` |
| `tls` | `null` | `TlsConfig.selfSigned(...)` or `TlsConfig.keyStore(...)`; `null` is plain `ws://` |
| `insecureDev` | `false` | allow plain `ws://` off loopback (development only) |
| `maxEnvelopeBytes` | 16 MiB | larger frames are dropped and the connection closed (1009) |
| `rateLimit` | 240 / 1000 ms | token bucket per peer (`maxMessages`, `windowMs`, `burst`) |
| `pairMailboxTtlMs` | 10 min | short-code mailbox lifetime |
| `helloTimeoutMs` | 30 s | how long a connection may stay unauthenticated |
| `idleTimeoutMs` | 120 s | idle time before a WebSocket keep-alive ping |
| `maxConnections` | 512 | hard cap; extra connections get HTTP 503 |
| `moveSecret` | `null` | shared with the next relay to allow `relay_move` |
| `dataDir` | `~/.phonebridge` | identity key + generated certificate (in-memory if unset) |

Validation is strict where it matters: plain `ws://` on a non-loopback address throws unless
`insecureDev` is set explicitly.

## Using the relay from a peer

`RelayClient` does the whole dance: connect, authenticate, send and receive envelopes, reconnect with
exponential backoff, and follow `relay_moved` to the new host.

```kotlin
val client = RelayClient(
    RelayClientConfig(
        url = "wss://192.168.1.20:8443",
        identity = myIdentity,            // :core Identity (its role is sent in hello)
        tlsPin = offer.pin,               // the offer's "relay_pin" value: pin the fingerprint
    )
)

client.onRoute = { envelope -> handlePayload(envelope.payload) }
client.onPairOffer = { envelope -> /* short code lookup result */ }
client.onRelayMoved = { moved -> /* the client reconnects by itself */ }

client.start()
client.awaitConnected(10_000)
client.sendRoute(to = devicePeerId, payload = encryptedBase64)
client.stop()
```

Payloads are opaque to the relay: they are the end-to-end encrypted frames built by `:core`.

## What the relay does and does not do

- **Does**: authenticate peers by public key (X25519 challenge/response, see
  [PROTOCOL-RELAY.md](PROTOCOL-RELAY.md)), route envelopes, hold a pairing offer under a short code
  for at most ten minutes and release it once, rate limit each peer, hand over to a new host, serve
  `/healthz`.
- **Does not**: see plaintext, hold keys, keep messages, know whether a peer is paired with another
  one, or trust any peer's claim about another peer's identity (`from` must match the authenticated
  key).
- **Metadata it necessarily sees**: which peer ids connect, when, from which IP, and how large the
  encrypted frames are. Do not use a relay you do not control.

## Tests

```bash
gradlew.bat :relay:test
```

The suite covers, in-process and on loopback: two peers authenticating and exchanging envelopes in
both directions; forged/unauthorised `hello` (bad proof, wrong nonce, peer id that does not match the
key, all-zero key, unauthenticated route); a second connection replacing the first; `peer_offline`;
ping/pong; rate limiting; oversize frames dropped (configured limit and the 16 MiB default); short-code
mailbox single release, expiry and size/entry limits; `relay_move` with both peers following to the
second relay and still exchanging messages, including token replay, expiry and peer binding; and TLS
with fingerprint pinning (wrong pin rejected, host names verified for CA certificates).

## Android notes

The module uses only APIs available on API 26+: `java.util.Base64`, `javax.net.ssl`, `java.security`,
`java.util.concurrent`, `java.io`. Crypto and JSON come from `:core` (Bouncy Castle lightweight API and
kotlinx-serialization, both Android-safe), so the relay needs no Android-specific code and the same
classes are covered by the JVM tests. The APK build itself waits for an Android SDK on this machine; the
relay part of the app is plain Kotlin, so it is testable today.
