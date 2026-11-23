# Third-party code and libraries

Policy: reuse free, licence-compatible code (MIT / Apache-2.0 / BSD / CC0) before writing anything
from scratch, and record it here. GPL/AGPL is not used.

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

## Other modules

`core/` and `relay/` are owned by other lanes; their dependencies should be listed there (currently
Kotlin stdlib and `kotlin("test")` only).
