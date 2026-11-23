# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 — setup (Grok Bot)
- Repo scaffolded: docs/SPEC.md, docs/PROTOCOL.md, docs/BOT_WORKFLOW.md, Gradle wrapper (9.2.1) with
  `core` and `relay` JVM modules (empty), folders android/, mcp/, desktop/.
- Toolchain on this PC: JDK 25 (Temurin), Node, Git. **No Android SDK** (no ANDROID_HOME, no
  %LOCALAPPDATA%\Android\Sdk), no global Gradle (use `gradlew.bat`). Android modules are included in
  `settings.gradle.kts` only when an SDK is found; until then write the Android code and keep
  JVM-testable logic in plain Kotlin classes with JVM unit tests.
- No GitHub remote yet (gh not logged in). Push steps are skipped until `origin` exists.
