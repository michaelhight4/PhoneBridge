# Testing

This repo has multiple test suites. Run the ones relevant to your change; before merging to `main`, the affected module must build and its tests must pass.

## Prerequisites

- JDK 25 (Temurin) on PATH
- Node.js + npm
- Git
- Python (for helper scripts, optional)
- Android SDK is **not** required for JVM/Node tests. Android APK builds are pending SDK installation.

Use the repo Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` on Unix).

## JVM tests (core, relay)

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :relay:test
.\gradlew.bat :android:executor-core:test
.\gradlew.bat :android:ui-core:test
.\gradlew.bat :e2e:test          # v1 acceptance run, see "End-to-end harness" below
# or all JVM tests
.\gradlew.bat test
```

## Protocol conformance tests (Node + vitest + ajv)

Validates `protocol/schema/*.schema.json` and `protocol/examples/*` (every message in `docs/PROTOCOL.md`, including command args/results). Also validates captured protocol samples if present.

```powershell
cd tests/conformance
npm ci
npm test
```

### Captured sample hook

Kotlin `:core` tests and TypeScript `mcp` tests can emit protocol samples for cross-implementation conformance. Write one JSON message per file to any of these locations (all are gitignored build output):

- `build/protocol-samples/*.json`
- `core/build/protocol-samples/*.json`
- `mcp/build/protocol-samples/*.json`
- `relay/build/protocol-samples/*.json`

The conformance runner (`src/protocol-samples.test.js`) discovers them automatically when present. If a sample includes a `$schema` or `schema` field naming a schema file/id, that schema is used; otherwise the runner heuristically picks candidate schemas and requires at least one match.

## End-to-end harness (fake phone)

`tests/e2e` (Gradle module `:e2e`) is the v1 acceptance run. It starts real relays, pairs a bot by
short code, link and `.pbpair` file, opens the Noise `XX` session and drives a fake phone through
`status`, `screenshot`, `ui_tree`, `tap`, `swipe`, `type`, `key`, `open_app`, a View-level permission
denial, control queueing between two bots, Kick everyone and `relay_move` between two relays.

```powershell
.\gradlew.bat :e2e:test
```

The fake phone is the real `:core` + `:relay` + `:android:executor-core` + `:android:ui-core`
(`SessionHandler`, `ApprovalController`, `BridgeState`) with only `DeviceControl` faked, so a green
run means the protocol works, not that a mock agreed with itself.

For manual integration with an external bot:

```powershell
.\gradlew.bat :e2e:runFakePhone
```

It prints a relay URL, certificate pin, short code, link and `.pbpair` path, and auto-approves pairing
requests at Control. Press Enter to stop.

## Mock device

A small Node mock phone that connects to a relay as a device, answers every command with realistic fake data (generated PNG screenshot, fake UI tree, tap/type logging), honours permission levels and the control queue.

```powershell
cd tests/mock-device
npm ci
node src/index.js --relay ws://127.0.0.1:8787
```

Options:

- `--relay <ws://...>` relay URL (required)
- `--name <name>` device display name
- `--auto-accept` / `--no-auto-accept` pair request handling (default auto-accept in dev mode)
- `--plaintext` plaintext dev payloads (default; base64url UTF-8 JSON)
- `--crypto` reserved for encrypted E2E mode (not implemented until core crypto lands)
- `--log <file>` append JSONL event log
- `--once` exit after first connection closes

Dev mode note: because core crypto (tasks 01/05) may not be merged, the mock device uses clearly marked plaintext dev payloads. When encrypted crypto lands on `main`, add the encrypted path and keep dev mode behind an explicit flag.

## Android

Android modules are only included when an Android SDK is found. There is no Android SDK installed on this PC. Write Android code fully, keep non-Android logic in plain Kotlin classes with JVM unit tests, and note APK build as pending SDK in `HANDOFF.md`.

## Before merging

1. `git merge main` into your branch, rebuild and rerun affected tests.
2. `git -C C:\Users\seeth\PhoneBridge merge --ff-only <branch>` (retry if main moved).
3. If `origin` exists, `git -C C:\Users\seeth\PhoneBridge push origin main`.

Do not merge with failing builds, failing tests, or unresolved conflicts.
