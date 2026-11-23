/**
 * High-level bot client: keystore + pairing + one live session per device.
 *
 * Both the MCP server and the CLI are thin wrappers around this class.
 */
import { toBase64Url } from "./bytes.js";
import { fingerprintForPeerId } from "./crypto.js";
import { DeviceSession, targetFromStored, type DeviceTarget, type RequestOptions } from "./session.js";
import { Keystore, type PermissionLevel, type StoredDevice } from "./keystore.js";
import { normalizeLevel } from "./levels.js";
import { PhoneBridgeError, isPhoneBridgeError } from "./errors.js";
import { parseOfferJson, parsePairingInput, type PairingOffer } from "./pairing.js";
import { RelayConnection, type RelayConnectionOptions } from "./relay.js";

export interface ClientOptions {
  /** Keystore directory; defaults to `$PHONEBRIDGE_HOME` or `~/.phonebridge`. */
  home?: string;
  botName?: string;
  defaultTimeoutMs?: number;
  insecureDev?: boolean;
  /** Test seam: replaces the real WebSocket relay. */
  relayFactory?: (options: RelayConnectionOptions) => RelayConnection;
  /** Test hook: fixed clock in ms since epoch. */
  now?: () => number;
}

export interface PairOptions {
  relayUrl?: string;
  botName?: string;
  requestedLevel?: PermissionLevel;
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
   * Pairs with a phone from any supported input (short code + relay, link, `.pbpair` file, offer JSON)
   * and keeps the resulting session open.
   */
  async pair(input: string, options: PairOptions = {}): Promise<PairOutcome> {
    const parsed = await parsePairingInput(input, {
      ...(options.relayUrl === undefined ? {} : { relayUrl: options.relayUrl }),
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
      ...(options.now === undefined ? {} : { now: options.now }),
    });

    const offer =
      parsed.kind === "offer"
        ? parsed.offer
        : await this.lookupOffer(parsed.code, parsed.relayUrl, options);

    const requestedLevel = options.requestedLevel ?? "control";
    const target: DeviceTarget = {
      peerId: offer.deviceId,
      publicKey: offer.devicePublicKey,
      relayUrl: offer.relay,
      relayFingerprint: offer.relayFingerprint,
      name: offer.deviceName ?? "Android phone",
      level: requestedLevel,
    };

    const session = await DeviceSession.connect({
      identity: await this.keystore.identity(),
      device: target,
      botName: options.botName ?? this.botName,
      pairingSecret: offer.secret,
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
      ...(this.options.relayFactory === undefined ? {} : { relayFactory: this.options.relayFactory }),
      ...(this.options.defaultTimeoutMs === undefined
        ? {}
        : { defaultTimeoutMs: this.options.defaultTimeoutMs }),
    });

    try {
      const result = asRecord(await session.pairRequest(requestedLevel), "pair_result");
      if (result["ok"] === false) {
        const reason = String(result["reason"] ?? "denied");
        throw new PhoneBridgeError(
          reason === "expired" ? "expired" : "permission_denied",
          `the device refused the pairing request: ${reason}`,
          { reason },
        );
      }
      const granted = normalizeLevel(result["granted_level"], requestedLevel);
      const deviceName =
        typeof result["device_name"] === "string" && result["device_name"].trim() !== ""
          ? result["device_name"]
          : target.name;
      const record: StoredDevice = {
        peerId: offer.deviceId,
        name: deviceName,
        publicKey: toBase64Url(offer.devicePublicKey),
        relayUrl: offer.relay,
        ...(offer.relayFingerprint === undefined ? {} : { relayFingerprint: offer.relayFingerprint }),
        level: granted,
        pairedAt: new Date(this.now()).toISOString(),
        botName: options.botName ?? this.botName,
      };
      await this.keystore.upsertDevice(record);
      await this.keystore.save();
      session.device.level = granted;
      session.device.name = deviceName;
      this.replaceSession(session);
      return {
        device: record,
        level: granted,
        deviceName,
        fingerprint: fingerprintForPeerId(offer.deviceId),
      };
    } catch (error) {
      session.close("pairing failed");
      throw error;
    }
  }

  /** Opens (or reuses) a session with a paired device. */
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
    code: string,
    relayUrl: string,
    options: PairOptions,
  ): Promise<PairingOffer> {
    const relay = new RelayConnection({
      url: relayUrl,
      role: "bot",
      identity: await this.keystore.identity(),
      ...(this.options.insecureDev === undefined ? {} : { insecureDev: this.options.insecureDev }),
    });
    try {
      await relay.connect();
      const payload = await relay.lookupPairing(code, options.timeoutMs ?? 10_000);
      return parseOfferJson(payload, {
        ...(options.now === undefined ? {} : { now: options.now }),
      });
    } catch (error) {
      if (isPhoneBridgeError(error) && error.code === "timeout") {
        throw new PhoneBridgeError(
          "expired",
          "the pairing code was not found on the relay (codes expire after 10 minutes)",
          { code },
        );
      }
      throw error;
    } finally {
      relay.close();
    }
  }

  private now(): number {
    return this.options.now?.() ?? Date.now();
  }
}

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null) {
    throw new PhoneBridgeError("internal", `${label} was not an object`);
  }
  return value as Record<string, unknown>;
}
