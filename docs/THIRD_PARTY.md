# Third-party software

PhoneBridge is MIT licensed. This file lists every third-party library or algorithm source used, with
its licence, so the whole dependency tree stays licence-compatible (MIT/Apache/BSD/CC0 preferred; no
GPL in PhoneBridge itself).

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
| Noise `XX` handshake, `CipherState`, `SymmetricState`, transport keys | [The Noise Protocol Framework, revision 34](https://noiseprotocol.org/noise.html) | Implemented from the published specification in `crypto/Noise.kt`; no Noise library code was copied or linked (no maintained JVM Noise library was available under a compatible licence for our needs) |
| Crockford base32 pairing codes + CRC-5 check symbol | [Crockford base32](https://www.crockford.com/base32.html) alphabet, CRC-5/USB polynomial `0x05` | `pairing/ShortCode.kt`; the exact algorithm is documented in docs/PROTOCOL.md |
| Base64url, RFC 4648 base32 | RFC 4648 | `util/B64Url.kt` uses `java.util.Base64` (present on Android API 26+); `util/Base32.kt` is local |
| HKDF-SHA256 | RFC 5869 | `crypto/Primitives.kt` |

## Test-only dependencies

| Library | Version | Licence |
| --- | --- | --- |
| `kotlin-test` / JUnit 5 | bundled with Kotlin 2.3.0 | Apache-2.0 / EPL-2.0 (test scope only) |

## Assets

None yet. Android UI assets must be free-licensed (MIT/Apache/BSD/CC0) and recorded here with their
source and author before they are committed.
