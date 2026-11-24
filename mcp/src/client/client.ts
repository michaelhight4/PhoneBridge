/**
 * High-level bot client: keystore + pairing exchange + one Noise session per device.
 *
 * Pairing (docs/PROTOCOL.md 5.3/5.4) is a sealed request/response over relay `route` envelopes, before
 * any Noise session exists. After the phone shows "Allow this bot?" and answers, the bot opens Noise
 * `XX` with the device and pins the paired static key.
 */
import { randomUUID } from "node:crypto";
import { fromBase64Loose, fromUtf8, toBase64Url, toHex, utf8 } from "./bytes.js";
import { openBotMessage, sealBotMessage } from "./botMessageCrypto.js";
import { fingerprintForPeerId, sha256, type Identity } from "./crypto.js";
import { InboxStateStore, type InboxStateMessage } from "./inboxState.js";
import { DeviceSession, targetFromStored, type DeviceTarget, type RequestOptions } from "./session.js";
import { Keystore, type PermissionLevel, type StoredDevice } from "./keystore.js";
import { normalizeLevel } from "./levels.js";
import { PhoneBridgeError, isPhoneBridgeError } from "./errors.js";
import {
  openPairingPayload,
  pairingRequestAad,
  pairingResultAad,
  sealPairingPayload,
} from "./pairingCrypto.js";
import { parseOfferJson, parsePairingInput, type PairingOffer } from "./pairing.js";
import {
  RelayConnection,
  type Envelope,
  type RelayBotMessage,
  type RelayConnectionOptions,
  type RelayPeerEntry,
} from "./relay.js";

export interface ClientOptions {
  home?: string;
  botName?: string;
  defaultTimeoutMs?: number;
  insecureDev?: boolean;
  relayFactory?: (options: RelayConnectionOptions) => RelayConnection;
  now?: () => number;
}

export interface PairOptions {
  relayUrl?: string;
  botName?: string;
  requestedLevel?: PermissionLevel;
  /** How long to wait for the user's on-device approval. */
  timeoutMs?: number;
  /** Test hook: treat this as "now" when checking offer expiry. */
  now?: number;
}

export interface PairOutcome {
  device: StoredDevice;
  level: PermissionLevel;
  deviceName: string;
  fingerprint: string;
}

export interface ListPeersOptions {
  device?: string;
  relayUrl?: string;
  timeoutMs?: number;
}

export interface SendMessageOptions {
  replyTo?: string;
  device?: string;
  relayUrl?: string;
  timeoutMs?: number;
}

export interface ReadInboxOptions {
  wait?: boolean;
  timeoutMs?: number;
  limit?: number;
  keep?: boolean;
  device?: string;
  relayUrl?: string;
}

export interface ReplyOptions {
  target?: string;
  replyTo?: string;
  device?: string;
  relayUrl?: string;
  timeoutMs?: number;
}

/** Another bot this bot may message, as published by a paired device. */
export interface BotPeer {
  peerId: string;
  name: string;
  publicKey: string;
  fingerprint: string;
  deviceId?: string;
  deviceName?: string;
  enabled?: boolean;
  canMessage?: boolean;
  online?: boolean;
}

export interface SentBotMessage {
  id: string;
  to: string;
  toName: string;
  toFingerprint: string;
  deviceId?: string;
  createdAt: number;
  replyTo?: string;
}

export interface DecryptedBotMessage {
  id: string;
  from: string;
  fromName?: string;
  fromFingerprint: string;
  to: string;
  deviceId?: string;
  createdAt: number;
  text: string;
  replyTo?: string;
}

interface RawPairResult {
  v?: number;
  ok?: boolean;
  granted_level?: unknown;
  device_name?: unknown;
  device_id?: unknown;
  reason?: unknown;
}

export class PhoneBridgeClient {
  readonly keystore: Keystore;
  readonly botName: string;
  private readonly options: ClientOptions;
  private session: DeviceSession | undefined;

  private constructor(keystore: Keystore, options: ClientOptions) {
    this.keystore = keystore;
    this.options = options;
    this.botName = options.botName ?? "PhoneBridge bot";
  }

  static async open(options: ClientOptions = {}): Promise<PhoneBridgeClient> {
    const keystore = await Keystore.open(options.home === undefined ? {} : { home: options.home });
    return new PhoneBridgeClient(keystore, options);
  }

  get botPeerId(): string {
    return this.keystore.botPeerId;
  }

  get botFingerprint(): string {
    return this.keystore.botFingerprint;
  }

  devices(): StoredDevice[] {
    return this.keystore.devices();
  }

  resolveDevice(ref?: string): StoredDevice {
    return this.keystore.resolve(ref);
  }

  /**
   * Pairs with a phone from a short code, link, `.pbpair` file or offer JSON, then keeps the Noise
   * session open.
   */
  async pair(input: string, options: PairOptions = {}): Promise<PairOutcome> {
    const parsed = await parsePairingInput(input, {
      ...(options.relayUrl === undefined ? {} : { relayUrl: options.relayUrl }),
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
      ...(options.now === undefined ? {} : { now: options.now }),
    });

    const initialOffer = parsed.kind === "offer" ? relayOverride(parsed.offer, options.relayUrl) : undefined;
    const initialRelayUrl = parsed.kind === "offer" ? (initialOffer?.relay ?? parsed.offer.relay) : parsed.relayUrl;
    const relay = new RelayConnection({
      url: initialRelayUrl,
      role: "bot",
      identity: await this.keystore.identity(),
      relayPin: initialOffer?.relayPin,
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
    });

    try {
      await relay.connect();
      const advertised =
        parsed.kind === "offer" ? parsed.offer : await this.lookupOffer(relay, parsed.code, options);
      // A bot outside the device's network (for example in the cloud) reaches the heart through a
      // tunnel or public hostname, not the LAN address the device advertises. When the caller names
      // a relay URL, remember that one; the advertised certificate pin only applies to the advertised
      // URL, so a different URL is checked against the system CAs instead.
      const offer = relayOverride(advertised, parsed.kind === "offer" ? options.relayUrl : parsed.relayUrl);

      const identity = await this.keystore.identity();
      const requestedLevel = options.requestedLevel ?? "control";
      const botName = options.botName ?? this.botName;
      const result = await this.exchangePairing(
        relay,
        offer,
        identity,
        botName,
        requestedLevel,
        options.timeoutMs ?? 120_000,
      );

      if (result.ok !== true) {
        const reason = typeof result.reason === "string" ? result.reason : "denied";
        throw new PhoneBridgeError(reasonErrorCode(reason), `the device refused the pairing request: ${reason}`, {
          reason,
        });
      }
      if (typeof result.device_id === "string" && result.device_id !== offer.deviceId) {
        throw new PhoneBridgeError("handshake_failed", "pair_result came from a different device", {
          expected: offer.deviceId,
          actual: result.device_id,
        });
      }

      const granted = normalizeLevel(result.granted_level, requestedLevel);
      const deviceName =
        typeof result.device_name === "string" && result.device_name.trim() !== ""
          ? result.device_name
          : offer.deviceName ?? "Android phone";

      const target: DeviceTarget = {
        peerId: offer.deviceId,
        publicKey: offer.devicePublicKey,
        relayUrl: offer.relay,
        relayPin: offer.relayPin,
        name: deviceName,
        level: granted,
      };

      const session = await DeviceSession.connect({
        identity,
        device: target,
        botName,
        relay,
        ...(this.options.defaultTimeoutMs === undefined
          ? {}
          : { defaultTimeoutMs: this.options.defaultTimeoutMs }),
      });

      const record: StoredDevice = {
        peerId: offer.deviceId,
        name: deviceName,
        publicKey: toBase64Url(offer.devicePublicKey),
        relayUrl: offer.relay,
        ...(offer.relayPin === undefined ? {} : { relayPin: offer.relayPin }),
        level: granted,
        pairedAt: new Date(this.now()).toISOString(),
        botName,
      };
      await this.keystore.upsertDevice(record);
      await this.keystore.save();
      this.replaceSession(session);
      return {
        device: record,
        level: granted,
        deviceName,
        fingerprint: fingerprintForPeerId(offer.deviceId),
      };
    } catch (error) {
      relay.close();
      throw error;
    }
  }

  /** Opens (or reuses) a Noise session with a paired device. */
  async connect(ref?: string, options: { handshakeTimeoutMs?: number } = {}): Promise<DeviceSession> {
    const record = this.keystore.resolve(ref);
    if (this.session !== undefined && this.session.device.peerId === record.peerId && this.session.isReady) {
      return this.session;
    }
    if (this.session !== undefined) this.session.close("switching device");
    const session = await DeviceSession.connect({
      identity: await this.keystore.identity(),
      device: targetFromStored(record),
      botName: this.botName,
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
      ...(this.options.relayFactory === undefined ? {} : { relayFactory: this.options.relayFactory }),
      ...(this.options.defaultTimeoutMs === undefined
        ? {}
        : { defaultTimeoutMs: this.options.defaultTimeoutMs }),
      ...(options.handshakeTimeoutMs === undefined ? {} : { handshakeTimeoutMs: options.handshakeTimeoutMs }),
    });
    this.keystore.touchDevice(record.peerId);
    await this.keystore.flush();
    this.replaceSession(session);
    return session;
  }

  async request(
    cmd: string,
    args: Record<string, unknown> = {},
    options: RequestOptions & { device?: string } = {},
  ): Promise<unknown> {
    const session = await this.connect(options.device);
    return session.request(cmd, args, options);
  }

  async status(device?: string): Promise<unknown> {
    return (await this.connect(device)).status();
  }

  /** Bots this bot may message, according to the relay's device-published ACL. */
  async listPeers(options: ListPeersOptions = {}): Promise<BotPeer[]> {
    const device = this.keystore.resolve(options.device);
    const relay = await this.botRelay(device, options.relayUrl);
    try {
      await this.connectBotRelay(relay);
      const peers = await relay.listPeers(undefined, options.timeoutMs ?? 10_000);
      return peers.map(toBotPeer);
    } finally {
      relay.close();
    }
  }

  /** Encrypts and stores one end-to-end bot message; resolves `peerRef` from the relay's peer list. */
  async sendMessage(peerRef: string, text: string, options: SendMessageOptions = {}): Promise<SentBotMessage> {
    const device = this.keystore.resolve(options.device);
    const identity = await this.keystore.identity();
    const relay = await this.botRelay(device, options.relayUrl);
    try {
      await this.connectBotRelay(relay);
      const peers = (await relay.listPeers(undefined, options.timeoutMs ?? 10_000)).map(toBotPeer);
      const peer = resolveBotPeer(peers, peerRef);
      if (peer.enabled === false || peer.canMessage === false) {
        throw new PhoneBridgeError(
          "permission_denied",
          `messaging is disabled for bot ${peer.peerId}`,
          { peerId: peer.peerId },
        );
      }
      const recipientPublicKey = fromBase64Loose(peer.publicKey);
      if (recipientPublicKey.length !== 32) {
        throw new PhoneBridgeError("bad_args", `bot ${peer.peerId} has an invalid public key`);
      }
      const messageId = randomUUID();
      const frame = await sealBotMessage(
        identity,
        recipientPublicKey,
        identity.peerId,
        peer.peerId,
        messageId,
        text,
        options.replyTo,
      );
      const outgoing: RelayBotMessage = {
        id: messageId,
        from: identity.peerId,
        from_name: this.botName,
        to: peer.peerId,
        device_id: device.peerId,
        created_at: this.now(),
        payload: toBase64Url(frame),
      };
      const stored = await relay.sendBotMessage(outgoing, device.peerId, options.timeoutMs ?? 10_000);
      return {
        id: stored.id,
        to: stored.to,
        toName: peer.name,
        toFingerprint: peer.fingerprint,
        ...(stored.device_id === undefined ? {} : { deviceId: stored.device_id }),
        createdAt: stored.created_at,
        ...(options.replyTo === undefined ? {} : { replyTo: options.replyTo }),
      };
    } finally {
      relay.close();
    }
  }

  /**
   * Reads the bot mailbox. A `msg_deliver` listener is attached before connecting, so messages pushed
   * while `readBotInbox` is in flight are not lost. With `wait`, an empty mailbox waits for a push and
   * returns an empty array on timeout (friendly to polling bots).
   */
  async readInbox(options: ReadInboxOptions = {}): Promise<DecryptedBotMessage[]> {
    const device = this.keystore.resolve(options.device);
    const identity = await this.keystore.identity();
    const relay = await this.botRelay(device, options.relayUrl);
    const pushed: RelayBotMessage[] = [];
    let signalPush: (() => void) | undefined;
    const pushAvailable = new Promise<void>((resolve) => {
      signalPush = resolve;
    });
    const unsubscribe = relay.onBotMessage((message) => {
      if (message.to !== identity.peerId) return;
      pushed.push(message);
      signalPush?.();
    });

    try {
      await this.connectBotRelay(relay);
      const timeoutMs = options.timeoutMs ?? 10_000;
      const listed = await relay.readBotInbox(
        {
          limit: options.limit ?? 50,
          consume: options.keep !== true,
          ...(device.peerId === undefined ? {} : { deviceId: device.peerId }),
        },
        timeoutMs,
      );
      const byId = new Map<string, RelayBotMessage>();
      for (const message of listed) byId.set(message.id, message);
      for (const message of pushed) if (!byId.has(message.id)) byId.set(message.id, message);

      if (byId.size === 0 && options.wait === true) {
        const timer = setTimeout(() => signalPush?.(), options.timeoutMs ?? 60_000);
        try {
          await pushAvailable;
        } finally {
          clearTimeout(timer);
        }
        for (const message of pushed) if (!byId.has(message.id)) byId.set(message.id, message);
      }

      if (options.keep !== true && pushed.length > 0) {
        const ids = [...new Set(pushed.map((message) => message.id))];
        try {
          await relay.ackBotMessages(ids, device.peerId, timeoutMs);
        } catch {
          /* Best effort: the message has already been read and decrypted locally. */
        }
      }

      const peers = await this.tryListPeersForNames(relay, device.peerId, timeoutMs);
      const inboxState = await InboxStateStore.open({ home: this.keystore.home });
      const results: DecryptedBotMessage[] = [];
      for (const message of byId.values()) {
        if (message.to !== identity.peerId) continue;
        const peer = peers.get(message.from);
        let expectedSenderPublicKey: Uint8Array | undefined;
        if (peer !== undefined) {
          try {
            expectedSenderPublicKey = fromBase64Loose(peer.publicKey);
          } catch {
            expectedSenderPublicKey = undefined;
          }
        }
        const plaintext = await openBotMessage(
          identity,
          message.from,
          message.to,
          message.id,
          fromBase64Loose(message.payload),
          expectedSenderPublicKey,
        );
        const fromName = message.from_name ?? peer?.name;
        const result: DecryptedBotMessage = {
          id: message.id,
          from: message.from,
          ...(fromName === undefined ? {} : { fromName }),
          fromFingerprint: peer?.fingerprint ?? fingerprintForPeerId(message.from),
          to: message.to,
          ...(message.device_id === undefined ? {} : { deviceId: message.device_id }),
          createdAt: message.created_at,
          text: plaintext.text,
          ...(plaintext.replyTo === undefined ? {} : { replyTo: plaintext.replyTo }),
        };
        results.push(result);
        const cached: InboxStateMessage = {
          id: result.id,
          from: result.from,
          ...(result.fromName === undefined ? {} : { fromName: result.fromName }),
          ...(result.deviceId === undefined ? {} : { deviceId: result.deviceId }),
          text: result.text,
          createdAt: result.createdAt,
        };
        inboxState.note(cached);
      }
      results.sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id));
      await inboxState.save();
      return results;
    } finally {
      unsubscribe();
      relay.close();
    }
  }

  /** Replies to the latest cached message, or to an explicit peer/message reference. */
  async reply(text: string, options: ReplyOptions = {}): Promise<SentBotMessage> {
    const inboxState = await InboxStateStore.open({ home: this.keystore.home });
    let target = options.target;
    let replyTo = options.replyTo;

    if (target === undefined) {
      const cached =
        replyTo === undefined ? inboxState.lastReceived : inboxState.findById(replyTo);
      if (cached === undefined) {
        throw new PhoneBridgeError(
          "bad_input",
          "no cached message to reply to; pass a peer or run `inbox` first",
        );
      }
      target = cached.from;
      replyTo ??= cached.id;
    } else {
      const cached = inboxState.findById(target) ?? findCachedPeer(inboxState, target);
      if (cached !== undefined) {
        target = cached.from;
        replyTo ??= cached.id;
      }
    }

    return this.sendMessage(target, text, {
      ...(replyTo === undefined ? {} : { replyTo }),
      ...(options.device === undefined ? {} : { device: options.device }),
      ...(options.relayUrl === undefined ? {} : { relayUrl: options.relayUrl }),
      ...(options.timeoutMs === undefined ? {} : { timeoutMs: options.timeoutMs }),
    });
  }

  async screenshot(
    args: { format?: "png" | "jpeg"; quality?: number; max_width?: number } = {},
    device?: string,
  ): Promise<unknown> {
    return (await this.connect(device)).screenshot(args);
  }

  get activeSession(): DeviceSession | undefined {
    return this.session;
  }

  async forget(peerId: string): Promise<boolean> {
    if (this.session?.device.peerId === peerId) this.session.close("unpaired");
    const removed = this.keystore.removeDevice(peerId);
    if (removed) await this.keystore.save();
    return removed;
  }

  async close(): Promise<void> {
    this.session?.close("client closed");
    this.session = undefined;
    await this.keystore.flush();
  }

  private replaceSession(session: DeviceSession): void {
    if (this.session !== undefined && this.session !== session) this.session.close("replaced");
    this.session = session;
    session.onEvent((event) => {
      if (event.event !== "relay_moved") return;
      const data = event.data as Record<string, unknown> | undefined;
      const newUrl = typeof data?.["new_url"] === "string" ? data["new_url"] : undefined;
      if (newUrl === undefined) return;
      this.keystore.patchDevice(session.device.peerId, { relayUrl: newUrl });
      void this.keystore.flush();
    });
    session.onClose(() => {
      if (this.session === session) this.session = undefined;
    });
  }

  private async botRelay(device: StoredDevice, relayUrlOverride?: string): Promise<RelayConnection> {
    const override = relayUrlOverride?.trim() ?? "";
    const useOverride = override !== "" && override !== device.relayUrl;
    // The advertised certificate pin only holds for the advertised URL (see `relayOverride`).
    const relayPin = useOverride ? undefined : device.relayPin;
    const factory = this.options.relayFactory ?? ((opts) => new RelayConnection(opts));
    return factory({
      url: useOverride ? override : device.relayUrl,
      role: "bot",
      identity: await this.keystore.identity(),
      ...(relayPin === undefined ? {} : { relayPin }),
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
    });
  }

  private async connectBotRelay(relay: RelayConnection): Promise<void> {
    try {
      await relay.connect();
    } catch (error) {
      relay.close();
      throw error;
    }
  }

  private async tryListPeersForNames(
    relay: RelayConnection,
    deviceId: string | undefined,
    timeoutMs: number,
  ): Promise<Map<string, BotPeer>> {
    try {
      const peers = await relay.listPeers(deviceId, timeoutMs);
      return new Map(peers.map((entry) => {
        const peer = toBotPeer(entry);
        return [peer.peerId, peer] as const;
      }));
    } catch {
      // Names are an enrichment only; a relay that denies peers_list must not hide the inbox.
      return new Map();
    }
  }

  private async lookupOffer(
    relay: RelayConnection,
    code: string,
    options: PairOptions,
  ): Promise<PairingOffer> {
    const lookup = await relay.lookupPairing(code, options.timeoutMs ?? 10_000);
    return parseOfferJson(JSON.stringify(lookup.offer), {
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
      ...(options.now === undefined ? {} : { now: options.now }),
    });
  }

  /** Sealed `pair_request` / `pair_result` exchange (no Noise session yet). */
  private async exchangePairing(
    relay: RelayConnection,
    offer: PairingOffer,
    identity: Identity,
    botName: string,
    requestedLevel: PermissionLevel,
    timeoutMs: number,
  ): Promise<RawPairResult> {
    const request = {
      v: 1,
      bot_name: botName,
      bot_pub: toBase64Url(identity.publicKey),
      requested_level: requestedLevel,
      bot_id: identity.peerId,
    };
    const sealed = await sealPairingPayload(
      offer.secret,
      pairingRequestAad(offer.devicePublicKey),
      utf8(JSON.stringify(request)),
    );

    const pendingReply = this.waitForRoute(relay, offer.deviceId, timeoutMs);
    relay.sendRoute(offer.deviceId, sealed);
    const reply = await pendingReply;

    const opened = await openPairingPayload(
      offer.secret,
      pairingResultAad(offer.devicePublicKey),
      reply,
    );
    let result: RawPairResult;
    try {
      result = JSON.parse(fromUtf8(opened)) as RawPairResult;
    } catch {
      throw new PhoneBridgeError("handshake_failed", "device sent a pair_result that is not JSON");
    }
    return result;
  }

  private waitForRoute(relay: RelayConnection, from: string, timeoutMs: number): Promise<Uint8Array> {
    return new Promise<Uint8Array>((resolve, reject) => {
      let unsubscribe: () => void = () => undefined;
      const timer = setTimeout(() => {
        unsubscribe();
        reject(
          new PhoneBridgeError(
            "timeout",
            `the device did not answer the pairing request within ${timeoutMs} ms (did the user tap Allow?)`,
          ),
        );
      }, timeoutMs);
      unsubscribe = relay.onEnvelope((envelope: Envelope) => {
        if (envelope.from !== from) return;
        if (typeof envelope.payload !== "string") return;
        let payload: Uint8Array;
        try {
          payload = fromBase64Loose(envelope.payload);
        } catch {
          return;
        }
        clearTimeout(timer);
        unsubscribe();
        resolve(payload);
      });
    });
  }

  private now(): number {
    return this.options.now?.() ?? Date.now();
  }
}

function toBotPeer(entry: RelayPeerEntry): BotPeer {
  const fingerprint =
    entry.fingerprint !== undefined && entry.fingerprint.trim() !== ""
      ? entry.fingerprint
      : fingerprintForPeerId(entry.peer_id);
  return {
    peerId: entry.peer_id,
    name: entry.name,
    publicKey: entry.public_key,
    fingerprint,
    ...(entry.device_id === undefined ? {} : { deviceId: entry.device_id }),
    ...(entry.device_name === undefined ? {} : { deviceName: entry.device_name }),
    ...(entry.enabled === undefined ? {} : { enabled: entry.enabled }),
    ...(entry.can_message === undefined ? {} : { canMessage: entry.can_message }),
    ...(entry.online === undefined ? {} : { online: entry.online }),
  };
}

/** Resolves a peer reference exactly like `Keystore.resolve` does for devices. */
function resolveBotPeer(peers: BotPeer[], ref: string): BotPeer {
  const wanted = ref.trim();
  const lower = wanted.toLowerCase();
  const fingerprint = wanted.replace(/[^0-9a-zA-Z]/g, "").toLowerCase();
  const hexFingerprint = /^[0-9a-f]{8}$/.test(fingerprint) ? fingerprint : undefined;
  const matches = peers.filter((peer) => {
    if (peer.peerId === lower) return true;
    if (lower.length >= 6 && peer.peerId.startsWith(lower)) return true;
    if (peer.name.toLowerCase() === lower) return true;
    const peerFingerprint = peer.fingerprint.replace(/[^0-9a-zA-Z]/g, "").toLowerCase();
    if (fingerprint.length >= 8 && peerFingerprint === fingerprint) return true;
    if (hexFingerprint !== undefined) {
      try {
        if (toHex(sha256(fromBase64Loose(peer.publicKey))).startsWith(hexFingerprint)) return true;
      } catch {
        /* An unusable key cannot be matched by its hash. */
      }
    }
    return false;
  });
  if (matches.length === 0) throw new PhoneBridgeError("no_peer", `no bot peer matches "${wanted}"`);
  if (matches.length > 1) {
    throw new PhoneBridgeError("ambiguous_peer", `"${wanted}" matches several bot peers`, {
      peers: matches.map((peer) => peer.peerId),
    });
  }
  return matches[0] as BotPeer;
}

function findCachedPeer(inboxState: InboxStateStore, ref: string): InboxStateMessage | undefined {
  const wanted = ref.trim().toLowerCase();
  const fingerprint = ref.replace(/[^0-9a-zA-Z]/g, "").toLowerCase();
  for (const message of [...inboxState.all()].reverse()) {
    const peerId = message.from.toLowerCase();
    if (peerId === wanted) return message;
    if (wanted.length >= 6 && peerId.startsWith(wanted)) return message;
    if (message.fromName?.toLowerCase() === wanted) return message;
    if (fingerprint.length >= 8 && peerId.startsWith(fingerprint)) return message;
  }
  return undefined;
}

function reasonErrorCode(reason: string): "permission_denied" | "expired" | "handshake_failed" {
  switch (reason) {
    case "expired":
    case "used":
    case "unknown_code":
      return "expired";
    case "bad_secret":
    case "bad_request":
      return "handshake_failed";
    default:
      return "permission_denied";
  }
}

export { isPhoneBridgeError };

/** Use `url` instead of the offer's advertised relay; the certificate pin only holds for the same URL. */
function relayOverride<T extends { relay: string; relayPin?: string | undefined }>(offer: T, url: string | undefined): T {
  if (url === undefined || url.trim() === "" || url.trim() === offer.relay) return offer;
  return { ...offer, relay: url.trim(), relayPin: undefined };
}
