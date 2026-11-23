/**
 * High-level bot client: keystore + pairing exchange + one Noise session per device.
 *
 * Pairing (docs/PROTOCOL.md 5.3/5.4) is a sealed request/response over relay `route` envelopes, before
 * any Noise session exists. After the phone shows "Allow this bot?" and answers, the bot opens Noise
 * `XX` with the device and pins the paired static key.
 */
import { fromBase64Loose, fromUtf8, toBase64Url, utf8 } from "./bytes.js";
import { fingerprintForPeerId, type Identity } from "./crypto.js";
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
import { RelayConnection, type Envelope, type RelayConnectionOptions } from "./relay.js";

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

    const initialRelayUrl = parsed.kind === "offer" ? parsed.offer.relay : parsed.relayUrl;
    const relay = new RelayConnection({
      url: initialRelayUrl,
      role: "bot",
      identity: await this.keystore.identity(),
      relayPin: parsed.kind === "offer" ? parsed.offer.relayPin : undefined,
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
    });

    try {
      await relay.connect();
      const offer =
        parsed.kind === "offer" ? parsed.offer : await this.lookupOffer(relay, parsed.code, options);

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
