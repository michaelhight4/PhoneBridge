# tests/e2e - the v1 acceptance harness

Gradle module `:e2e`. Plain Kotlin/JVM, so it needs no Android SDK and no phone.

```powershell
.\gradlew.bat :e2e:test          # the whole v1 path, green
.\gradlew.bat :e2e:runFakePhone  # relay + fake phone for manual integration
```

## What it proves

`EndToEndTest` starts a real relay and pairs a real bot client by all three transfer forms, then
drives a fake phone through the whole documented command set:

| Acceptance item | Test |
| --- | --- |
| Pairing by **short code** (relay mailbox), over a **pinned TLS** relay | `pairs by short code over a pinned TLS relay and runs every command` |
| Pairing by **link** (QR payload) and by **`.pbpair` file** | `pairs by link and by pairing file` |
| `status`, `screenshot` (png + jpeg), `ui_tree` (+ `tap {node_id}`), `notifications`, `tap`, `swipe`, `type`, `key`, `open_app` (package + label), `bad_args`, `unknown_cmd` | same test |
| Permission denial at **View** level (nothing reaches the device) | `a view level bot cannot run control commands` |
| Full-level command without Shizuku | `a full level bot without shizuku is told so` |
| **Control queueing** between two bots, positions, promotion on release | `two bots queue for control and the queued one acts after release` |
| **Kick everyone** (sealed `kicked`, keys survive, re-handshake works) | `kick everyone revokes every session and the paired keys survive` |
| **`relay_move` between two relays**, peers follow, session keeps working | `relay_move hands the heart to a second relay and the session keeps working` |

## What is real and what is faked

Real: `:core` (protocol model, Noise `XX`, pairing, bot registry, control arbiter), `:relay` (server,
client, TLS, mailbox, move tokens), `:android:executor-core` (command table, permissions, args,
UI-tree ids) and `:android:ui-core` (`SessionHandler`, `ApprovalController`, `BridgeState`,
`ActivityLog`) - i.e. the same classes the Android service uses.

Faked: only `DeviceControl` (`FakePhoneDevice`), which stands in for the accessibility service,
MediaProjection and the package manager, plus the Shizuku-backed Full commands (the fake answers
`shizuku_unavailable`) and the embedded relay (the phone attaches to a relay the test starts).

## Files

| File | What |
| --- | --- |
| `FakePhone.kt` | The phone side, assembled like `BridgeService`; offers, approvals, kick, `relay_move` |
| `FakePhoneDevice.kt` | Scriptable `DeviceControl` that records taps, swipes, typing, keys, launches |
| `BotClient.kt` | Bot side: relay connection, all three pairing forms, Noise session, requests, events |
| `E2e.kt` | Relay startup, temp dirs, timeouts |
| `FakePhoneServer.kt` | `:e2e:runFakePhone`: a manual target that prints a short code/link/file |

## Notes

- Relays listen on `127.0.0.1` with port `0`, so tests can run in parallel and never collide. One
  test uses the real self-signed TLS listener plus the certificate pin from the pairing offer.
- The `relay_move` test shares a `moveSecret` between the two relays, exactly like two relays the
  user owns.
- The MCP server/CLI (`mcp/`, task 05) is not on `main` yet. When it lands, point it at
  `:e2e:runFakePhone` or add a Node test that reuses the same fake phone; the harness already speaks
  the merged protocol, so no Kotlin changes should be needed.
