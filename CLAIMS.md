# Claims

One line per claimed task: `lane | task | branch | claimed (America/Chicago) | status`.

- bigpickle | 05 MCP server, CLI, bot client and API docs (`mcp/`, `docs/API.md`) | `pb/mcp-server-cli` | 2026-11-23 11:20 | done (57 tests green, aligned to `core/` Noise XX, verified against the real relay)
- ds1 | 01 protocol, crypto and pairing library (`core/`, `docs/PROTOCOL.md`) | `pb/protocol-pairing` | 2026-11-23 11:14 | done (111 core tests pass; relay wire contract aligned)
- ds2 | 02 relay server | pb/relay | 2026-11-23 11:14 | done (33 tests green on :core, merged to main)
- ds3 | android app skeleton + accessibility service + command executor (`android/`) | pb/android-accessibility | 2026-11-23 11:15 AM | done (JVM tests green, 74 tests; APK build pending Android SDK)
- iceman | JSON Schemas, conformance tests, mock device (protocol/, tests/, docs/TESTING.md) | pb/schemas-mock-device-tests | 2026-11-23 11:16 -0600 | done
- iceman | CI, security and release docs (.github/, SECURITY.md, CONTRIBUTING.md, docs/ITCH_PAGE.md, docs/USER_GUIDE.md) | pb/ci-security-docs | 2026-11-23 12:36 -0600 | done
- ds3 | android foreground service, pairing UI, bot list, permissions, notifications (`android/`) | pb/android-service-pairing-ui | 2026-11-23 12:05 PM | done (ui-core 39 JVM tests green, app sources complete; APK build pending Android SDK)
