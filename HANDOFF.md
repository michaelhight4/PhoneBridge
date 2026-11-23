# Handoff

Newest entries at the top. Each entry: date/time (America/Chicago), lane, model, task, what was done,
what is left, how to build/test, blockers.

## 2026-11-23 11:14–12:00 — ds2 / deepseek/deepseek-flash — task 02 relay server (`:relay`)

**Done (acceptance criteria met, `:relay:test` green: 43 tests)**
- Embeddable relay API in `dev.phonebridge.relay`: `Relay.start(RelayConfig): RelayHandle`, `stop()`,
  `moveTo(newUrl)`, `stats()`, `peers()`; per-connection `RelayClient` for devices, bots and tests.
- Transport: hand-written RFC 6455 server + client (text frames, ping/pong, close, masking), so the
  relay has no heavy dependency and runs on Android API 26+ (see `docs/THIRD_PARTY.md` for why a
  library was not used and what Gson is used for).
- TLS: PKCS#12 keystore or a self-signed P-256 certificate generated on first run and persisted
  (`relay-cert.p12`), fingerprint exposed as base64url + hex for pinning, clients pin the fingerprint;
  `--insecure-dev` / `insecureDev` allows plain `ws://` off loopback; loopback `ws://` is allowed.
- Peer auth: relay `challenge` (nonce + per-connection X25519 ephemeral key) and `hello` with an
  `dh-hmac-sha256` proof of key ownership; `peer_id` must match the public key; replayed nonce, forged
  proof, all-zero key and spoofed `from` are all rejected.
- Routing: `route` forwarded verbatim by `to` (payload never parsed), `peer_offline` errors,
  `ping`/`pong`, per-peer token bucket rate limit (dropped messages are reported once per second),
  16 MiB envelope cap enforced before buffering (close code 1009), connection cap (512), 30 s hello
  timeout, idle keep-alive pings.
- Short-code mailbox: `pair_store` / `pair_lookup` with canonical Crockford code normalization,
  10 minute TTL, single release, 16 KiB offer cap.
- `relay_move`: old relay emits `relay_moved {new_url, token, expires}` with per-peer one-time HMAC
  tokens (shared `moveSecret`); the new relay accepts each token once (`token_used` on replay) and
  `RelayClient` follows automatically and keeps exchanging messages.
- Standalone: `gradlew.bat :relay:run --args="--port 8443"`, `gradlew.bat :relay:shadowJar` →
  `relay/build/libs/phonebridge-relay-all.jar` (verified: serves `/healthz`, generated cert + pin,
  persisted identity). Both were smoke-tested from the command line.
- Docs: `relay/README.md` (embedding, standalone, config table, security notes),
  `relay/PROTOCOL-RELAY.md` (the concrete relay wire contract), `docs/THIRD_PARTY.md` (Gson
  Apache-2.0; RFC 6455 / RFC 7748 / X.509 implemented from specification).

**Left / for the next lane**
- `:core` still has no envelope/identity types (task 01 not merged at the time of writing). The relay
  keeps a minimal `RelayEnvelope` + `RelayEnvelopeCodec` in `relay/.../envelope/` and forwards the raw
  received text, so swapping in `:core` types is a one-line change at that seam.
- **Identity details core must align with:** relay `peer_id` = Crockford base32 (lowercase, 26 chars)
  of the first 16 bytes of SHA-256(public key); fingerprint `ABCD-EFGH`. Relay `hello` proof is
  `HMAC-SHA256(X25519(peer_priv, relay_ephemeral_pub), "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>")`.
- Protocol additions are documented in `relay/PROTOCOL-RELAY.md` and are additive only; `docs/PROTOCOL.md`
  was deliberately not edited (task 01 owns it). Core should adopt `challenge`, `hello_ok`,
  `pair_store`, `pair_lookup`, `pair_offer` and the extra error codes; if the proof format changes, use
  the `proof` field in `challenge` to negotiate.
- The relay's X25519 (`relay/.../crypto/X25519.kt`) is a BigInteger reference implementation: correct
  (RFC 7748 vectors in tests) but **not constant time**. It only touches per-connection ephemeral keys
  and handshake nonces. When `:core` lands a constant-time X25519/Noise implementation, switch the
  relay handshake over to it and delete this copy.
- Android app wiring is not done: `android/` should start the relay when the user picks "this phone"
  as the host, and pass a `dataDir` inside app storage. The APK build is still pending an Android SDK
  on this machine (no `ANDROID_HOME`); the relay itself is plain JVM Kotlin and is covered by tests.
- Not covered yet: presence events (`peer_online`/`peer_offline` notifications to other peers),
  multiple relay instances sharing a routing table, and `relay_moved` for peers that are offline at
  hand-over time (they reconnect to the URL they already know).

**How to test**
```bash
gradlew.bat :relay:test                                        # 43 in-process tests
gradlew.bat :relay:run --args="--port 8443"                    # standalone, wss + self-signed cert
gradlew.bat :relay:shadowJar                                   # single-file jar for a VPS
java -jar relay/build/libs/phonebridge-relay-all.jar --help
```
Tests are on loopback with port 0, so they do not need the SDK, a network, or free fixed ports.

**Blockers:** none. No `origin` remote exists, so commits are local only (no push); nothing was merged
while blocked.

## 2026-11-23 — setup (Grok Bot)
- Repo scaffolded: docs/SPEC.md, docs/PROTOCOL.md, docs/BOT_WORKFLOW.md, Gradle wrapper (9.2.1) with
  `core` and `relay` JVM modules (empty), folders android/, mcp/, desktop/.
- Toolchain on this PC: JDK 25 (Temurin), Node, Git. **No Android SDK** (no ANDROID_HOME, no
  %LOCALAPPDATA%\Android\Sdk), no global Gradle (use `gradlew.bat`). Android modules are included in
  `settings.gradle.kts` only when an SDK is found; until then write the Android code and keep
  JVM-testable logic in plain Kotlin classes with JVM unit tests.
- No GitHub remote yet (gh not logged in). Push steps are skipped until `origin` exists.
