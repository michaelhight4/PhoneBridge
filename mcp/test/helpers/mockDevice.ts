/**
 * Device-side mock: relays the way the Android app does (docs/PROTOCOL.md) - sealed pairing
 * request/result, Noise `XX` responder, command execution with permission levels, events and
 * protocol chunk events.
 */
import { fromUtf8, toBase64Url, utf8 } from "../../src/client/bytes.js";
import { createIdentity, randomBytes, type Identity } from "../../src/client/crypto.js";
import { buildChunks } from "../../src/client/chunks.js";
import { E2ESession, FRAME_HANDSHAKE, frameType } from "../../src/client/noise.js";
import {
  openPairingPayload,
  pairingRequestAad,
  pairingResultAad,
  sealPairingPayload,
} from "../../src/client/pairingCrypto.js";
import { RelayConnection } from "../../src/client/relay.js";
import { normalizeLevel, type PermissionLevel } from "../../src/client/levels.js";
import { randomCode } from "../../src/client/codes.js";
import type { RawPairingOffer } from "../../src/client/pairing.js";

export interface MockDeviceOptions {
  relayUrl: string;
  identity?: Identity;
  level?: PermissionLevel;
  /** One-time secret for the first pairing; omit for an already-paired device. */
  pairingSecret?: Uint8Array;
  deviceName?: string;
  autoPair?: boolean;
  relayPin?: string;
}

export type CommandHandler = (
  args: Record<string, unknown>,
  request: { id: string; cmd: string },
) => unknown | Promise<unknown>;

export interface ChunkedResult {
  __chunked: Uint8Array;
  [key: string]: unknown;
}

export class MockDevice {
  readonly identity: Identity;
  level: PermissionLevel;
  readonly deviceName: string;
  private readonly relayUrl: string;
  private readonly relayPin: string | undefined;
  private readonly autoPair: boolean;
  private pairingSecret: Uint8Array | undefined;
  private relay: RelayConnection | undefined;
  private e2e: E2ESession | undefined;
  private phase: "pairing" | "noise" | "ready" = "noise";
  private botPeerId: string | undefined;
  private botPublicKey: Uint8Array | undefined;

  readonly received: Array<{ id: string; cmd: string; args: Record<string, unknown> }> = [];
  handlers = new Map<string, CommandHandler>();
  silent = new Set<string>();
  failing = new Map<string, { code: string; message: string }>();
  delays = new Map<string, number>();
  handshakes = 0;
  pairingRequests = 0;
  /** Device's own short-code offer, if it created one. */
  offer: RawPairingOffer | undefined;

  private constructor(options: MockDeviceOptions & { identity: Identity }) {
    this.identity = options.identity;
    this.relayUrl = options.relayUrl;
    this.relayPin = options.relayPin;
    this.level = options.level ?? "full";
    this.deviceName = options.deviceName ?? "Mock Pixel";
    this.pairingSecret = options.pairingSecret;
    this.autoPair = options.autoPair ?? false;
    if (options.pairingSecret !== undefined) this.phase = "pairing";
  }

  static async create(options: MockDeviceOptions): Promise<MockDevice> {
    const identity = options.identity ?? (await createIdentity());
    const device = new MockDevice({ ...options, identity });
    await device.start();
    return device;
  }

  get peerId(): string {
    return this.identity.peerId;
  }

  get isPaired(): boolean {
    return this.phase !== "pairing";
  }

  get sessionReady(): boolean {
    return this.phase === "ready";
  }

  async start(): Promise<void> {
    const relay = new RelayConnection({
      url: this.relayUrl,
      role: "device",
      identity: this.identity,
      ...(this.relayPin === undefined ? {} : { relayPin: this.relayPin }),
    });
    await relay.connect();
    this.relay = relay;
    relay.onEnvelope((envelope) => {
      if (typeof envelope.payload !== "string") return;
      if (envelope.from !== undefined) this.botPeerId = envelope.from;
      const frame = Buffer.from(envelope.payload, "base64url");
      void this.handleFrame(new Uint8Array(frame));
    });
  }

  async stop(): Promise<void> {
    this.relay?.close();
    this.relay = undefined;
    this.e2e = undefined;
    this.phase = this.pairingSecret === undefined ? "noise" : "pairing";
  }

  /** Creates an offer for the short-code flow and registers it on the mock relay. */
  async createOffer(relay: { registerOffer: (code: string, offer: Record<string, unknown>, exp?: number) => void }, options: {
    code?: string;
    secret?: Uint8Array;
    ttlMs?: number;
    relayPin?: string;
  } = {}): Promise<{ offer: RawPairingOffer; secret: Uint8Array; code: string; link: string }> {
    const secret = options.secret ?? randomBytes(32);
    const code = options.code ?? randomCode(() => randomBytes(5));
    const expiresAt = Date.now() + (options.ttlMs ?? 10 * 60_000);
    const offer: RawPairingOffer = {
      v: 1,
      relay: this.relayUrl,
      device_id: this.identity.peerId,
      device_pub: toBase64Url(this.identity.publicKey),
      secret: toBase64Url(secret),
      code,
      exp: Math.floor(expiresAt / 1000),
      device_name: this.deviceName,
      ...(options.relayPin === undefined ? {} : { relay_pin: options.relayPin }),
    };
    this.offer = offer;
    this.pairingSecret = secret;
    this.phase = "pairing";
    relay.registerOffer(code, offer as unknown as Record<string, unknown>, expiresAt);
    const link = `phonebridge://pair?d=${Buffer.from(JSON.stringify(offer), "utf8").toString("base64url")}`;
    return { offer, secret, code, link };
  }

  async emitEvent(event: string, data: unknown): Promise<void> {
    const e2e = this.requireE2e();
    if (this.botPeerId === undefined) throw new Error("mock device: no bot connected");
    this.requireRelay().sendRoute(
      this.botPeerId,
      await e2e.sealText(JSON.stringify({ event, data })),
    );
  }

  /** Publishes the offer to the relay's short-code mailbox (`pair_store`). */
  storeOfferOnRelay(offer: RawPairingOffer): void {
    this.requireRelay().sendControl({ type: "pair_store", code: offer.code, offer });
  }

  private async handleFrame(frame: Uint8Array): Promise<void> {
    if (this.phase === "pairing") {
      await this.handlePairing(frame);
      return;
    }
    if (this.e2e === undefined) {
      this.e2e = new E2ESession("responder", this.identity);
      this.phase = "noise";
    }
    const e2e = this.e2e;
    if (!e2e.isEstablished) {
      if (frameType(frame) !== FRAME_HANDSHAKE) return;
      await e2e.readHandshakeFrame(frame);
      if (e2e.canWriteHandshake) {
        this.send(await e2e.writeHandshakeFrame());
      }
      if (e2e.isEstablished) {
        this.handshakes += 1;
        this.phase = "ready";
        if (this.botPublicKey !== undefined) e2e.requirePeer(this.botPublicKey);
      }
      return;
    }
    if (frameType(frame) === FRAME_HANDSHAKE) {
      // A reconnecting bot starts a fresh Noise handshake.
      this.e2e = new E2ESession("responder", this.identity);
      this.phase = "noise";
      await this.handleFrame(frame);
      return;
    }
    const text = await e2e.openText(frame);
    await this.handleMessage(JSON.parse(text) as unknown);
  }

  private async handlePairing(sealed: Uint8Array): Promise<void> {
    const secret = this.pairingSecret;
    if (secret === undefined) return;
    let opened: Uint8Array;
    try {
      opened = await openPairingPayload(secret, pairingRequestAad(this.identity.publicKey), sealed);
    } catch {
      return;
    }
    const request = JSON.parse(fromUtf8(opened)) as Record<string, unknown>;
    if (typeof request["bot_pub"] !== "string") return;
    const botPublicKey = new Uint8Array(Buffer.from(request["bot_pub"], "base64url"));
    this.botPublicKey = botPublicKey;
    this.pairingRequests += 1;

    const requested = normalizeLevel(request["requested_level"], "view");
    const result = this.autoPair
      ? { v: 1, ok: true, granted_level: this.level ?? requested, device_name: this.deviceName, device_id: this.identity.peerId }
      : { v: 1, ok: false, reason: "denied", device_name: this.deviceName, device_id: this.identity.peerId };

    const sealedResult = await sealPairingPayload(
      secret,
      pairingResultAad(this.identity.publicKey),
      utf8(JSON.stringify(result)),
    );
    this.send(sealedResult);
    if (!this.autoPair) return;
    // The one-time secret is burned after the first accepted request.
    this.pairingSecret = undefined;
    this.phase = "noise";
  }

  private async handleMessage(value: unknown): Promise<void> {
    if (typeof value !== "object" || value === null) return;
    const message = value as Record<string, unknown>;
    const id = message["id"];
    const cmd = message["cmd"];
    if (typeof id !== "string" || typeof cmd !== "string") return;
    const args = (message["args"] as Record<string, unknown> | undefined) ?? {};
    this.received.push({ id, cmd, args });
    if (this.botPeerId === undefined) return;

    if (this.silent.has(cmd)) return;
    const delay = this.delays.get(cmd);
    if (delay !== undefined) await new Promise((resolve) => setTimeout(resolve, delay));
    if (this.silent.has(cmd)) return;

    const e2e = this.requireE2e();
    const relay = this.requireRelay();
    const failure = this.failing.get(cmd);
    if (failure !== undefined) {
      relay.sendRoute(
        this.botPeerId,
        await e2e.sealText(JSON.stringify({ id, ok: false, error: failure })),
      );
      return;
    }

    const handler = this.handlers.get(cmd);
    const result = handler === undefined ? defaultResult(cmd, args) : await handler(args, { id, cmd });
    if (typeof result === "object" && result !== null && "__chunked" in result) {
      const chunked = result as ChunkedResult;
      const set = buildChunks(chunked.__chunked);
      for (const chunk of set.chunks) {
        relay.sendRoute(
          this.botPeerId,
          await e2e.sealText(JSON.stringify({ event: "chunk", data: chunk })),
        );
      }
      const { __chunked: _raw, ...metadata } = chunked;
      void _raw;
      relay.sendRoute(
        this.botPeerId,
        await e2e.sealText(
          JSON.stringify({
            id,
            ok: true,
            result: {
              ...metadata,
              chunked: true,
              chunk_id: set.chunkId,
              total: set.total,
              size: set.size,
              sha256: set.sha256,
            },
          }),
        ),
      );
      return;
    }
    relay.sendRoute(
      this.botPeerId,
      await e2e.sealText(JSON.stringify({ id, ok: true, result })),
    );
  }

  private send(frame: Uint8Array): void {
    if (this.botPeerId === undefined) throw new Error("mock device: no peer id for the bot yet");
    this.requireRelay().sendRoute(this.botPeerId, frame);
  }

  private requireRelay(): RelayConnection {
    if (this.relay === undefined) throw new Error("mock device is not started");
    return this.relay;
  }

  private requireE2e(): E2ESession {
    if (this.e2e === undefined) throw new Error("mock device has no session");
    return this.e2e;
  }
}

export function defaultResult(cmd: string, args: Record<string, unknown>): unknown {
  switch (cmd) {
    case "status":
      return {
        device: "Mock Pixel",
        battery: 87,
        screen_on: true,
        controller: null,
        queue: [],
        shizuku: false,
      };
    case "screenshot":
      return {
        mime: args["format"] === "jpeg" ? "image/jpeg" : "image/png",
        width: 1080,
        height: 2400,
        data_b64: Buffer.from("mock-png-bytes").toString("base64url"),
      };
    case "ui_tree":
      return {
        root: {
          id: "root",
          cls: "android.widget.FrameLayout",
          text: "",
          desc: "",
          res_id: "",
          bounds: [0, 0, 1080, 2400],
          visible: true,
          clickable: false,
          focusable: false,
          editable: false,
          children: [],
        },
      };
    case "tap":
    case "long_press":
    case "swipe":
    case "type":
    case "key":
    case "open_app":
    case "release_control":
      return {};
    case "request_control":
      return { granted: true };
    case "notifications":
      return { items: [] };
    case "shell":
      return { exit: 0, stdout: `ran ${String(args["command"] ?? "")}`, stderr: "" };
    case "files":
      if (args["op"] === "read") {
        return { op: "read", data_b64: Buffer.from("file-bytes").toString("base64url"), bytes: 10 };
      }
      if (args["op"] === "list") {
        return { op: "list", entries: [{ name: "a.txt", path: "/sdcard/a.txt", is_dir: false, size: 10, mtime: 0 }] };
      }
      return { op: args["op"], ok: true, bytes: 0 };
    case "install_apk":
      return { package: "com.example.mock" };
    case "relay_move":
      return { new_url: String(args["to"] ?? "") };
    case "settings":
      return { value: String(args["value"] ?? "") };
    default:
      return {};
  }
}
