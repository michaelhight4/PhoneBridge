# Third-party code and libraries

Policy: reuse free, licence-compatible code (MIT / Apache-2.0 / BSD / CC0) before writing anything
from scratch, and record it here. GPL/AGPL is not used. PhoneBridge itself is MIT (see
[../LICENSE](../LICENSE)).

## Android app + executor (`android/`)

| What | Licence | How it is used |
| --- | --- | --- |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | Apache-2.0 | Language runtime; pulled in by the Kotlin/Android Gradle plugins. |
| `kotlin("test")` (kotlin-test + JUnit 5 platform) | Apache-2.0 | JVM unit tests in `android/executor-core`. Test scope only. |
| Android Gradle plugin `com.android.application` 9.0.1 | Apache-2.0 | Build only, resolved from `google()` in `settings.gradle.kts` `pluginManagement`. Not shipped in the APK. |
| Android platform APIs (`android.*`, `AndroidX`-free) | Apache-2.0 | Used directly through the SDK; no third-party Android libraries are bundled yet. |

Nothing was copied in from another project for this task; the accessibility tree mapping, gesture
dispatch, command routing, permission checks and control queue are written here. The app deliberately
has no AndroidX/Compose dependency yet (plain platform `Activity` + views), so there is nothing else to
license-track.

## Relay (`relay/`)

| What | Licence | How it is used |
| --- | --- | --- |
| Kotlin standard library, `kotlin("test")` + JUnit 5 | Apache-2.0 / EPL-2.0 | Runtime and test-only, as above. |
| [Google Gson](https://github.com/google/gson) 2.11.0 | Apache-2.0 | JSON encoding/decoding of relay messages. Small (~280 KB), pure Java, works on Android API 26+ and on a plain JVM, and keeps the relay embeddable in the phone app and the desktop app. Only the routing fields of an envelope are parsed; encrypted payloads are never decoded. |

Implemented in the relay from specifications (no third-party code):

| Area | Specification | Where |
| --- | --- | --- |
| WebSocket server and client, framing, masking, close codes | RFC 6455 | `relay/src/main/kotlin/dev/phonebridge/relay/ws/` |
| X25519 scalar multiplication | RFC 7748 (validated against its section 5.2 and 6.1 test vectors) | `relay/.../crypto/X25519.kt` |
| Self-signed X.509 v1 certificate and PKCS#12 storage | RFC 5280 / PKCS#12 | `relay/.../crypto/Tls.kt` |
| Crockford base32, check symbols, confusable mapping | Crockford base32 specification | `relay/.../crypto/Base32.kt` |
| SHA-256 and HMAC-SHA256 | FIPS 180-4 / RFC 2104, via `java.security` / `javax.crypto` | `relay/.../crypto/Codec.kt` |
| TLS transport | JSSE (`javax.net.ssl`), part of the JDK and Android | `relay/.../crypto/Tls.kt` |

### Why the relay's WebSocket layer is not a library

- `org.java-websocket:Java-WebSocket` (MIT) has no server-side TLS: its `WebSocketServer` accepts a
  plain `ServerSocket`, so a `wss://` relay would need nginx or a similar proxy in front, which
  contradicts "the relay is embedded in the phone app".
- Ktor's CIO server would work, but pulls in coroutines, slf4j and a server stack, and its server
  artifacts are aimed at JVM servers rather than an Android app; the relay needs to stay small enough
  to embed.

So the module implements the small, well-specified subset of RFC 6455 it needs (text and binary
messages, ping/pong, close, masking, 16 MiB cap) on top of `java.net.Socket` / `javax.net.ssl`.
The implementation is covered by the in-process tests in `relay/src/test`.

The relay uses only APIs present on Android API 26+ (`java.util.Base64`, `javax.net.ssl`,
`java.security`, `java.util.concurrent`, `java.io`, `java.net`) and on JDK 17+, so the same classes run
in the Android app, on the desktop and on a VPS. It ships no images, fonts or other binary assets.

## Core (`core/`)

Kotlin standard library and `kotlin("test")` only at the time of writing; the protocol, crypto and
pairing task owns this section.

## MCP server (`mcp/`)

Node/TypeScript dependencies are recorded here when that module lands.
