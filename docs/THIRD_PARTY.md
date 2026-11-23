# Third-party software

PhoneBridge is MIT licensed. This file lists every third-party library or algorithm source used, with
its licence, so the whole dependency tree stays licence-compatible (MIT/Apache/BSD/CC0 preferred; no
GPL in PhoneBridge itself).

Policy: reuse free, licence-compatible code (MIT / Apache-2.0 / BSD / CC0) before writing anything
from scratch, and record it here. GPL/AGPL is not used.

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
| Android platform APIs (`android.*`, `AndroidX`-free) | Apache-2.0 | Used directly through the SDK; no third-party Android libraries are bundled yet. |
| `:core` (this repo) | MIT | Shared protocol/crypto/pairing library; consumed as a Gradle project dependency. |

Nothing was copied in from another project for the Android work; the accessibility tree mapping,
gesture dispatch, command routing, permission checks and control queue are written here. The app
deliberately has no AndroidX/Compose dependency yet (plain platform `Activity` + views), so there is
nothing else to license-track.

## Other modules

`relay/` is owned by another lane; its dependencies should be listed there (currently Kotlin stdlib and
`kotlin("test")` only).

## Test-only dependencies

| Library | Version | Licence |
| --- | --- | --- |
| `kotlin-test` / JUnit 5 | bundled with Kotlin 2.3.0 | Apache-2.0 / EPL-2.0 (test scope only) |

## Assets

None yet. Android UI assets must be free-licensed (MIT/Apache/BSD/CC0) and recorded here with their
source and author before they are committed.
