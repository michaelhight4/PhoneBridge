# Claims

One line per claimed task: `lane | task | branch | claimed (America/Chicago) | status`.

- bigpickle | 05 MCP server, CLI, bot client and API docs (`mcp/`, `docs/API.md`) | `pb/mcp-server-cli` | 2026-11-23 11:20 | done (57 tests green, aligned to `core/` Noise XX, verified against the real relay)
- ds1 | 01 protocol, crypto and pairing library (`core/`, `docs/PROTOCOL.md`) | `pb/protocol-pairing` | 2026-11-23 11:14 | done (111 core tests pass; relay wire contract aligned)
- ds2 | 02 relay server | pb/relay | 2026-11-23 11:14 | done (39 tests green on :core, merged to main)
- ds3 | android app skeleton + accessibility service + command executor (`android/`) | pb/android-accessibility | 2026-11-23 11:15 AM | done (JVM tests green, 74 tests; APK build pending Android SDK)
- ds1 | Shizuku integration: permission flow, install_apk, settings, shell, files (`android/`) | pb/android-shizuku | 2026-11-23 11:40 AM | done (132 executor-core JVM tests pass; APK build pending Android SDK)
- iceman | JSON Schemas, conformance tests, mock device (protocol/, tests/, docs/TESTING.md) | pb/schemas-mock-device-tests | 2026-11-23 11:16 -0600 | done
- iceman | CI, security and release docs (.github/, SECURITY.md, CONTRIBUTING.md, docs/ITCH_PAGE.md, docs/USER_GUIDE.md) | pb/ci-security-docs | 2026-11-23 12:36 -0600 | done
- ds3 | android foreground service, pairing UI, bot list, permissions, notifications (`android/`) | pb/android-service-pairing-ui | 2026-11-23 12:05 PM | done (ui-core 44 JVM tests green, app sources complete; APK build pending Android SDK)
- ds1 | desktop agent phase 2, Windows first: tray app, pairing, bots, permissions, notifications, activity log, embedded relay, screenshot/stream/input/shell/files/install (`desktop/`) | pb/desktop-agent | 2026-11-23 11:54 AM | done (94 desktop JVM tests green, tray app starts and quits cleanly; merged to main)
- ds3 | 06 v1 end-to-end integration: JVM fake-phone e2e harness, integration fixes, QUICKSTART (`tests/e2e`, `docs/QUICKSTART.md`) | pb/v1-integration | 2026-11-23 11:50 AM | done (8 e2e tests green; MCP leg blocked on task 05)
