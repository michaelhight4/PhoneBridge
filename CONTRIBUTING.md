# Contributing to PhoneBridge

PhoneBridge is an MIT-licensed, security-sensitive project. Changes should be small, reviewable, and tested.

## Before you start

- Read [README.md](README.md), [docs/SPEC.md](docs/SPEC.md), [docs/PROTOCOL.md](docs/PROTOCOL.md), and [docs/BOT_WORKFLOW.md](docs/BOT_WORKFLOW.md).
- For security issues, follow [SECURITY.md](SECURITY.md) instead of opening a public issue.

## Development setup

Toolchain used by this repo:

- JDK 25 (Temurin) on PATH
- Node.js + npm
- Git
- Python (optional, useful for local checks)
- Gradle wrapper included in the repo (`gradlew.bat` / `./gradlew`)

There may be no Android SDK installed on a development machine. Android modules are only included when an SDK is found (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`). Keep Android-specific logic thin and put testable logic in plain Kotlin/JVM modules with JVM tests.

## Build and test

```powershell
# JVM tests
.\gradlew.bat :core:test
.\gradlew.bat :relay:test
.\gradlew.bat :android:executor-core:test

# Protocol conformance tests
cd tests/conformance
npm ci
npm test
```

On Unix, use `./gradlew` instead of `gradlew.bat`.

Before merging, run the tests affected by your change. A module must build and its tests must pass before it is merged.

## Project rules

- **No secrets.** Never commit API keys, passwords, private keys, tokens, pairing secrets, or personal data.
- **No large/binary junk.** No files over 100 MB, no `node_modules/`, no build outputs, no generated APK/AAB/keystore artifacts.
- **No AI attribution.** Do not add "Generated with", "Co-authored-by", bot signatures, or AI watermarks to commits, code, docs, or metadata.
- **Never force-push.**
- **Push after every commit** when `git remote get-url origin` succeeds.
- **Do not touch unrelated repos/areas.** Never touch Lifeline/Kilumen/SOTE_Unity/ProjectSettings; never kill Unity or other bots' processes.
- **Work in your task worktree/branch.** Do not edit files directly in the main checkout unless your workflow explicitly requires the ff-only merge step there.

## Protocol and compatibility

- Keep behavior consistent with [docs/SPEC.md](docs/SPEC.md) and [docs/PROTOCOL.md](docs/PROTOCOL.md).
- Protocol changes must be additive when possible (new optional fields, new ignorable message types). Do not renumber or repurpose existing fields/codes.
- If you must change protocol behavior, document it clearly in `docs/PROTOCOL.md` and note it in `HANDOFF.md`.

## Dependencies and licensing

- PhoneBridge is MIT. Prefer dependencies/assets that are MIT, Apache-2.0, BSD, or CC0.
- Do not add GPL/AGPL dependencies.
- Reuse free, license-compatible code before writing from scratch.
- Record third-party runtime/test/build dependencies and copied/adapted sources in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

## Git workflow

- Claim work in `CLAIMS.md` before starting.
- Commit small, working steps.
- Update `HANDOFF.md` with what was done, what is left, how to test, and blockers.
- Merge to `main` only when the acceptance criteria pass:
  1. `git merge main` into your branch, rebuild, rerun affected tests.
  2. `git -C <main-checkout> merge --ff-only <your-branch>` (repeat step 1 if main moved).
  3. `git -C <main-checkout> push origin main` if `origin` exists.
- Never merge failing builds, failing tests, or unresolved conflicts.

## Documentation

- User-facing docs should be clear and non-technical where appropriate.
- Keep security warnings visible anywhere pairing/control/install/shell capabilities are described.
- Update docs when behavior, commands, or setup changes.