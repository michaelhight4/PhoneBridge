# Third-party software

PhoneBridge is MIT licensed. This file lists every third-party library or algorithm source used, with
its licence, so the whole dependency tree stays licence-compatible (MIT/Apache/BSD/CC0 preferred; no
GPL in PhoneBridge itself).

Policy: reuse free, licence-compatible code (MIT / Apache-2.0 / BSD / CC0) before writing anything
from scratch, and record it here. GPL/AGPL is not used. PhoneBridge itself is MIT (see
[../LICENSE](../LICENSE)).

## Runtime dependencies (`core`)

| Library | Version | Licence | Used for |
| --- | --- | --- | --- |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (`kotlinx-serialization-json`) | 1.9.0 | Apache-2.0 | `@Serializable` message model, JSON wire format |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`) | 1.87 | MIT-style Bouncy Castle licence | X25519 (RFC 7748), ChaCha20-Poly1305 (RFC 7539), SHA-256, HMAC |
| [Kotlin standard library](https://kotlinlang.org/) | 2.3.0 | Apache-2.0 | language runtime |

Bouncy Castle is used through its lightweight API (`org.bouncycastle.crypto.*`,
`org.bouncycastle.math.ec.rfc7748.X25519`) rather than JCA providers, because Android API 26 does not
ship X25519 or ChaCha20-Poly1305 in its platform provider. These classes are pure Java and run
unchanged on the JVM and on Android.

## Algorithms implemented here (no third-party code copied)

| Thing | Source | Notes |
| --- | --- | --- |
| Noise `XX` handshake, `CipherState`, `SymmetricState`, transport keys | [The Noise Protocol Framework, revision 34](https://noiseprotocol.org/noise.html) | Implemented from the published specification in `core/.../crypto/Noise.kt`; no Noise library code was copied or linked (no maintained JVM Noise library was available under a compatible licence for our needs) |
| Crockford base32 pairing codes + CRC-5 check symbol | [Crockford base32](https://www.crockford.com/base32.html) alphabet, CRC-5/USB polynomial `0x05` | `core/.../pairing/ShortCode.kt`; the exact algorithm is documented in docs/PROTOCOL.md |
| Base64url, RFC 4648 base32 | RFC 4648 | `core/.../util/B64Url.kt` uses `java.util.Base64` (present on Android API 26+); `core/.../util/Base32.kt` is local |
| HKDF-SHA256 | RFC 5869 | `core/.../crypto/Primitives.kt` |

## Android app + executor (`android/`)

| What | Licence | How it is used |
| --- | --- | --- |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`) | Apache-2.0 | Language runtime; pulled in by the Kotlin/Android Gradle plugins. |
| `kotlin("test")` (kotlin-test + JUnit 5 platform) | Apache-2.0 | JVM unit tests in `android/executor-core`. Test scope only. |
| Android Gradle plugin `com.android.application` 9.0.1 | Apache-2.0 | Build only, resolved from `google()` in `settings.gradle.kts` `pluginManagement`. Not shipped in the APK. |
| Android platform APIs (`android.*`, `AndroidX`-free) | Apache-2.0 | Used directly through the SDK; no third-party Android libraries are bundled other than Shizuku below. |
| [Shizuku API](https://github.com/RikkaApps/Shizuku-API) (`dev.rikka.shizuku:api` + `:provider`) 13.1.5 | Apache-2.0 | The Full-level privileged path: the `ShizukuProvider` binder, the permission request and `Shizuku.newProcess` for one-shot `sh -c` commands (`pm`, `settings`, file commands and shell). Shizuku (the user-installed app) owns the privileged side; PhoneBridge ships only the client API. Root and ADB/Sui backends are both supported by Shizuku itself. |
| `:core` (this repo) | MIT | Shared protocol/crypto/pairing library; consumed as a Gradle project dependency. |

`Shizuku.newProcess` is deprecated upstream in Shizuku 13.1.1 in favour of a `UserService` (an AIDL
service running under the privileged identity). It is still present and supported in 13.1.5, and it
is the smallest way to run one-shot commands: no second process, no AIDL interface to version.
Migrating to `UserService` is a self-contained follow-up if upstream removes `newProcess`.

Nothing else was copied in from another project for the Android work; the accessibility tree
mapping, gesture dispatch, Shizuku command building/quoting, permission checks and control queue are
written here. The app deliberately has no AndroidX/Compose dependency yet (plain platform `Activity`
+ views), so there is nothing else to license-track.

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

## Test-only dependencies

| Library | Version | Licence |
| --- | --- | --- |
| `kotlin-test` / JUnit 5 | bundled with Kotlin 2.3.0 | Apache-2.0 / EPL-2.0 (test scope only) |

## Assets

None yet. Android UI assets must be free-licensed (MIT/Apache/BSD/CC0) and recorded here with their
source and author before they are committed.
