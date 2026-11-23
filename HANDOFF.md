# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 11:30 -0600 — iceman (Iceman Contributor Free) — JSON Schemas, conformance tests, mock device
- **Done:** Added `protocol/schema/*.schema.json` (draft 2020-12) for envelope, pairing-offer, pair-request, pair-result, request, response, event, and every command args/result in `docs/PROTOCOL.md`. Added `protocol/examples/` with one valid and at least one invalid example per message. Added `tests/conformance` (Node + vitest + ajv + ajv-formats) validating all examples; includes `src/protocol-samples.test.js` that auto-discovers captured JSON samples from `build/protocol-samples/*.json` (also `core|mcp|relay/build/protocol-samples/*.json`) and validates them when present. Added `tests/mock-device` (Node, `ws`) that connects to a relay as a device, completes hello auth (X25519 + HelloProof HMAC-SHA256), answers every command with realistic fake data (generated PNG screenshot, fake home-screen ui_tree, tap/type/key logging), honours permission levels and FIFO control queue with 120s idle auto-release. Added `docs/TESTING.md`.
- **Dev mode / crypto:** Mock device uses clearly marked **plaintext dev mode** by default (route payload is base64url UTF-8 JSON). Encrypted E2E path (Noise XX / X25519+XChaCha20-Poly1305 per PROTOCOL) is not implemented here because tasks 01/05 crypto are not merged; add encrypted path once main has them and keep `--plaintext` as explicit dev flag. Relay hello proof is implemented to interop with pb/relay.
- **Left:** When core/mcp emit `build/protocol-samples/*.json`, conformance will pick them up automatically. When task 01/05 crypto lands, wire encrypted payloads in mock device. Android APK build remains pending SDK.
- **How to test:** `cd tests/conformance && npm ci && npm test` (186 tests pass). `cd tests/mock-device && npm ci && node src/index.js --help` (starts; connect with `node src/index.js --relay ws://127.0.0.1:8787`).
- **Blockers:** No GitHub `origin` remote configured; push skipped. Core/mcp crypto not merged; plaintext dev mode used as documented.

## 2026-11-23 — setup (Grok Bot)
- Repo scaffolded: docs/SPEC.md, docs/PROTOCOL.md, docs/BOT_WORKFLOW.md, Gradle wrapper (9.2.1) with
  `core` and `relay` JVM modules (empty), folders android/, mcp/, desktop/.
- Toolchain on this PC: JDK 25 (Temurin), Node, Git. **No Android SDK** (no ANDROID_HOME, no
  %LOCALAPPDATA%\Android\Sdk), no global Gradle (use `gradlew.bat`). Android modules are included in
  `settings.gradle.kts` only when an SDK is found; until then write the Android code and keep
  JVM-testable logic in plain Kotlin classes with JVM unit tests.
- No GitHub remote yet (gh not logged in). Push steps are skipped until `origin` exists.
