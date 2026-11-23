# Security Policy

## Reporting a vulnerability

**Reporting address (placeholder):** `security@phonebridge.dev` (replace with the project's real security contact before publishing).

Please do not open a public issue for a security vulnerability. Send a report to the security contact above with:

- a description of the issue and its impact;
- affected component/version/commit;
- reproduction steps or a proof of concept;
- any suggested mitigation.

If you need encrypted contact, publish a project PGP key and fingerprint alongside the reporting address before release.

## Threat model

PhoneBridge gives an AI bot real control of a user's device. The design assumes hostile networks and treats remote infrastructure as untrusted.

### What the relay can and cannot do

- **The relay is untrusted.** It routes encrypted envelopes between paired peers. It authenticates peers by public key and enforces basic limits (rate limits, envelope size caps, hello timeout), but it is not a security boundary for message contents.
- **The relay cannot read commands, screenshots, UI trees, shell output, or file contents** when end-to-end encryption is active. Application payloads travel inside the Noise `XX` session (`Noise_XX_25519_ChaChaPoly_SHA256`) established directly between bot and device.
- **The relay can observe metadata** available at the transport/routing layer: connection timing, peer IDs, source/destination routing fields, message sizes, and frequency. Users who need stronger metadata protection need additional transport protections outside PhoneBridge v1.
- **A malicious or compromised relay can deny service**, drop/reorder/delay envelopes, or attempt replay/misdirection. Peers must treat relay errors and unexpected disconnects as untrusted signals. E2E authentication and counters prevent silent plaintext tampering/replay acceptance inside an established Noise session.

### Pairing and consent

- **Nothing happens without on-device consent.** A bot cannot become paired silently. After presenting a one-time pairing offer, the device shows an explicit "Allow this bot?" approval prompt with the bot identity/fingerprint before storing long-term bot keys.
- **Pairing secrets are one-time and expire after 10 minutes.** Short codes, `phonebridge://pair?...` links, and `.pbpair` files carry the same offer data and must be treated as secrets until used or expired.
- **Do not pair bots you do not trust.** Pairing grants a bot an authenticated channel subject to the permission level you approve.

### Key storage

- Device and bot identities are long-term X25519 key pairs. The public key identifies the peer (`peer_id` is derived from SHA-256 of the public key); fingerprints are for human verification.
- Private keys must be stored in the platform's best available protected storage (Android Keystore / app-private protected storage on Android; equivalent OS-protected storage on desktop). Implementations must not log private keys, pairing secrets, Noise keys, or decrypted payloads.
- A stored public key that does not match its private key must be rejected on load.
- Revoking/unpairing a bot removes that bot's trusted record and prevents new sessions for it. It does not affect other bots.

### Kick / unpair / control

- **Kick everyone** revokes live sessions immediately. Pairing keys may remain unless the user unpairs; kicked sessions must re-authenticate/reconnect as required by the protocol.
- **Unpair** removes a bot's trusted pairing record. Unpairing one bot never affects other bots.
- The device should always show who is in control through a persistent notification/UI state and provide an off switch.
- **One bot drives at a time.** Control/Full actions require control; View reads may be allowed concurrently as specified by the protocol. Control auto-releases after 120 seconds of inactivity and queued bots are promoted FIFO.

### What a malicious paired bot can do

The impact depends on the permission level granted. Users should grant the lowest level that works.

| Level | Intended capability | Malicious bot risk |
| --- | --- | --- |
| **View** | `status`, `screenshot`, `ui_tree`, `notifications` | Can read on-screen content, UI structure, notifications, and device status exposed by those commands. May capture sensitive information visible on screen or in notifications. Cannot tap/type/install/shell through protocol permissions. |
| **Control** | View + `tap`, `long_press`, `swipe`, `type`, `key`, `open_app`, `request_control`, `release_control` | Everything View can do, plus can operate the UI as the user: open apps, press buttons, submit forms, send messages, approve prompts shown on screen, change settings reachable through UI, and attempt social-engineering flows. It can act whenever it holds control. |
| **Full** | Control + `install_apk`, `shell`, `files`, `settings`, `relay_move` | Everything Control can do, plus high-impact device management: install APKs, run shell commands through the enabled backend, read/write/delete files exposed by the implementation, change system/secure/global settings exposed by the implementation, and move relay hosting. A malicious Full bot should be treated as a serious device compromise. |

### Additional security notes

- **Shizuku / privileged backends:** Full-level features may depend on user-enabled privileged backends. If unavailable, commands must fail with explicit errors (for example `shizuku_unavailable`), not silently escalate.
- **Accessibility/service abuse:** The Android app uses powerful platform capabilities. Only install builds from trusted sources and review requested permissions/services.
- **Logs and screenshots:** Activity logs and diagnostics should avoid secrets. Screenshots and UI trees can contain sensitive data; handle them as sensitive.
- **No telemetry / no master key:** PhoneBridge has no central server and no author backdoor by design. Users are responsible for the bots they pair and the hosts they run.
- **Supply chain:** Review dependencies and releases. Keep dependencies patched and verify checksums/signatures before publishing binaries.
