# Third-party software and specifications

PhoneBridge itself is MIT licensed (see [../LICENSE](../LICENSE)). Everything below is compatible with
that: Apache-2.0 and EPL-2.0 libraries are used as dependencies, and specifications are implemented
from their text. No GPL/AGPL code is used anywhere in this repository.

## Runtime dependencies

| Component | Library | Version | License | Why |
| --- | --- | --- | --- | --- |
| `core`, `relay` | Kotlin standard library | 2.3.0 | Apache-2.0 | language runtime |
| `relay` | [Google Gson](https://github.com/google/gson) | 2.11.0 | Apache-2.0 | JSON encoding/decoding. Small (~280 KB), pure Java, works on Android API 26+ and on a plain JVM, and keeps the relay embeddable in the phone app and the desktop app. Only the routing fields of an envelope are parsed; encrypted payloads are never decoded. |

## Test-only dependencies

| Component | Library | Version | License |
| --- | --- | --- | --- |
| `core`, `relay` | `kotlin-test` + JUnit 5 (Jupiter) | via Kotlin 2.3.0 / JUnit 5.11 | EPL-2.0 |

## Implemented from specifications (no third-party code)

| Area | Specification | Where |
| --- | --- | --- |
| WebSocket server and client, framing, masking, close codes | RFC 6455 | `relay/src/main/kotlin/dev/phonebridge/relay/ws/` |
| X25519 scalar multiplication | RFC 7748 (validated against its section 5.2 and 6.1 test vectors) | `relay/.../crypto/X25519.kt` |
| Self-signed X.509 v1 certificate and PKCS#12 storage | RFC 5280 / PKCS#12 | `relay/.../crypto/Tls.kt` |
| Crockford base32, check symbols, confusable mapping | Crockford base32 specification | `relay/.../crypto/Base32.kt` |
| SHA-256 and HMAC-SHA256 | FIPS 180-4 / RFC 2104, via `java.security` / `javax.crypto` | `relay/.../crypto/Codec.kt` |
| TLS transport | JSSE (`javax.net.ssl`), part of the JDK and Android | `relay/.../crypto/Tls.kt` |

### Why the WebSocket layer is not a library

- `org.java-websocket:Java-WebSocket` (MIT) has no server-side TLS: its `WebSocketServer` accepts a
  plain `ServerSocket`, so a `wss://` relay would need nginx or a similar proxy in front, which
  contradicts "the relay is embedded in the phone app".
- Ktor's CIO server would work, but pulls in coroutines, slf4j and a server stack, and its server
  artifacts are aimed at JVM servers rather than an Android app; the relay needs to stay small enough
  to embed.

So the module implements the small, well-specified subset of RFC 6455 it needs (text and binary
messages, ping/pong, close, masking, 16 MiB cap) on top of `java.net.Socket` / `javax.net.ssl`.
The implementation is covered by the in-process tests in `relay/src/test`.

## Platform APIs

The relay uses only APIs present on Android API 26+ (`java.util.Base64`, `javax.net.ssl`,
`java.security`, `java.util.concurrent`, `java.io`, `java.net`) and on JDK 17+, so the same classes run
in the Android app, on the desktop and on a VPS.

## Assets

None. The relay ships no images, fonts or other binary assets.
