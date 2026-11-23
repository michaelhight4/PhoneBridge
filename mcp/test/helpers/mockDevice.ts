/**
 * Device-side mock for tests: completes the hs1..hs4 handshake and answers requests exactly the way
 * the Android app will (docs/PROTOCOL.md), including pairing requests, events and chunked results.
 */
import { fromBase64Url, fromUtf8, toBase64Url, utf8 } from "../../src/client/bytes.js";
import {
  createIdentity,
  randomBytes,
  type Identity,
} from "../../src/client/crypto.js";
import {
  assertHello,
  buildFinishMessage,
  createResponderReply,
  deriveHandshakeKeys,
  verifyFinish,
  type HandshakeHello,
} from "../../src/client/handshake.js";
import { ChunkAssembler, SecureChannel, sealMessage } from "../../src/client/transport.js";
import { RelayConnection } from "../../src/client/relay.js";
import { normalizeLevel, type PermissionLevel } from "../../src/client/levels.js";
import type { RawPairingOffer } from "../../src/client/pairing.js";
import { makeCode } from "../../src/client/codes.js";

export interface MockDeviceOptions {
  relayUrl: string;
  identity?: Identity;
  level?: PermissionLevel;
  /** Set for a first-time pairing handshake; enables the `p:1` flag check. */
  pairingSecret?: Uint8Array;
  deviceName?: string;
  autoPair?: boolean;
}

export type CommandHandler = (
  args: Record<string, unknown>,
  request: { id: string; cmd: string },
) => unknown | Promise<unknown>;

export class MockDevice {
  readonly identity: Identity;
  level: PermissionLevel;
  readonly deviceName: string;
  private readonly relayUrl: string;
  private readonly pairingSecret: Uint8Array | undefined;
  private readonly autoPair: boolean;
  private relay: RelayConnection | undefined;
  private channel: SecureChannel | undefined;
  private assembler = new ChunkAssembler();
  private botPeerId: string | undefined;
  private transcriptHash: Uint8Array | undefined;
  /** Recorded requests, useful for assertions. */
  readonly received: Array<{ id: string; cmd: string; args: Record<string, unknown> }> = [];
  handlers = new Map<string, CommandHandler>();
  /** Commands that never get an answer (timeout tests). */
  silent = new Set<string>();
  /** Commands answered with a protocol error. */
  failing = new Map<string, { code: string; message: string }>();
  /** Artificial response delay per command in ms. */
  delays = new Map<string, number>();
  handshakes = 0;

  private constructor(options: MockDeviceOptions & { identity: Identity }) {
    this.identity = options.identity;
    this.relayUrl = options.relayUrl;
    this.level = options.level ?? "full";
    this.deviceName = options.deviceName ?? "Mock Pixel";
    this.pairingSecret = options.pairingSecret;
    this.autoPair = options.autoPair ?? false;
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

  get isReady(): boolean {
    return this.channel !== undefined;
  }

  async start(): Promise<void> {
    const relay = new RelayConnection({
      url: this.relayUrl,
      role: "device",
      identity: this.identity,
    });
    await relay.connect();
    this.relay = relay;
    relay.onEnvelope((envelope) => {
      if (typeof envelope.payload !== "string") return;
      this.botPeerId = envelope.from;
      void this.handlePayload(fromBase64Url(envelope.payload));
    });
  }

  async stop(): Promise<void> {
    this.relay?.close();
    this.relay = undefined;
    this.channel = undefined;
  }

  async emitEvent(event: string, data: unknown): Promise<void> {
    const channel = this.requireChannel();
    if (this.botPeerId === undefined) throw new Error("mock device: no bot connected");
    const frames = await sealMessage(channel, { event, data });
    for (const frame of frames) this.requireRelay().sendRoute(this.botPeerId, frame);
  }

  private async handlePayload(payload: Uint8Array): Promise<void> {
    if (this.channel === undefined) {
      await this.handleHandshake(payload);
      return;
    }
    let value: unknown;
    try {
      value = this.assembler.push(await this.channel.openJson(payload));
    } catch {
      // A reconnecting bot starts a new handshake; detect the plaintext hs1 and reset the session.
      try {
        const maybe = JSON.parse(fromUtf8(payload)) as Record<string, unknown>;
        if (maybe["t"] === "hs1") {
          this.channel = undefined;
          this.assembler = new ChunkAssembler();
          await this.handleHandshake(payload);
        }
      } catch {
        /* not a handshake frame: ignore */
      }
      return;
    }
    if (value === undefined) return;
    await this.handleMessage(value);
  }

  private async handleHandshake(payload: Uint8Array): Promise<void> {
    const hello = JSON.parse(fromUtf8(payload)) as HandshakeHello;
    assertHello(hello);
    const isPairing = hello.p === 1;
    if (isPairing && this.pairingSecret === undefined) {
      throw new Error("mock device: pairing handshake without a secret");
    }

    const botPublicKey = fromBase64Url(hello.bp);
    const reply = await createResponderReply(hello, this.identity.publicKey);
    const keys = await deriveHandshakeKeys({
      role: "device",
      staticKeyPair: this.identity,
      peerStaticPublic: botPublicKey,
      ephemeralKeyPair: reply.ephemeralKeyPair,
      peerEphemeralPublic: fromBase64Url(hello.e),
      ownNonce: reply.nonce,
      peerNonce: fromBase64Url(hello.n),
      ...(isPairing ? { pairingSecret: this.pairingSecret as Uint8Array } : {}),
    });
    this.send(utf8(JSON.stringify(reply.message)));
    this.transcriptHash = keys.transcriptHash;
    this.channel = new SecureChannel(keys.deviceToBot, keys.botToDevice);
    this.handshakes += 1;
  }

  private async handleMessage(value: unknown): Promise<void> {
    if (typeof value !== "object" || value === null) return;
    const message = value as Record<string, unknown>;

    if (message["t"] === "hs3") {
      const transcript = this.transcriptHash;
      if (transcript === undefined) throw new Error("mock device: hs3 before key derivation");
      verifyFinish(message, "hs3", transcript);
      const finish = await buildFinishMessage("hs4", transcript);
      const frames = await this.requireChannel().sealJson(finish);
      this.send(frames);
      return;
    }

    const id = message["id"];
    const cmd = message["cmd"];
    if (typeof id !== "string" || typeof cmd !== "string") return;
    const args = (message["args"] as Record<string, unknown> | undefined) ?? {};
    this.received.push({ id, cmd, args });

    if (!this.botPeerId) return;
    const relay = this.requireRelay();
    const channel = this.requireChannel();

    const finish = async (): Promise<void> => {
      if (this.silent.has(cmd)) return;
      const delay = this.delays.get(cmd);
      if (delay !== undefined) await new Promise((resolve) => setTimeout(resolve, delay));
      if (this.silent.has(cmd)) return;
      const failure = this.failing.get(cmd);
      if (failure !== undefined) {
        const frames = await sealMessage(channel, { id, ok: false, error: failure });
        for (const frame of frames) relay.sendRoute(this.botPeerId as string, frame);
        return;
      }
      let result: unknown;
      if (cmd === "pair_request" && this.autoPair) {
        result = {
          ok: true,
          granted_level: normalizeLevel(args["requested_level"], this.level),
          device_name: this.deviceName,
        };
      } else {
        const handler = this.handlers.get(cmd);
        result = handler === undefined ? defaultResult(cmd, args) : await handler(args, { id, cmd });
      }
      const frames = await sealMessage(channel, { id, ok: true, result });
      for (const frame of frames) relay.sendRoute(this.botPeerId as string, frame);
    };
    await finish();
  }

  private send(payload: Uint8Array): void {
    if (this.botPeerId === undefined) {
      // The relay envelope's `from` arrives with the first payload; hs1 always has it.
      throw new Error("mock device: no peer id for the bot yet");
    }
    this.requireRelay().sendRoute(this.botPeerId, payload);
  }

  private requireRelay(): RelayConnection {
    if (this.relay === undefined) throw new Error("mock device is not started");
    return this.relay;
  }

  private requireChannel(): SecureChannel {
    if (this.channel === undefined) throw new Error("mock device has no session");
    return this.channel;
  }
}

/** Builds the device-side pairing offer for the short-code and link flows. */
export async function createMockOffer(
  device: MockDevice | Identity,
  relayUrl: string,
  options: {
    code?: string;
    secret?: Uint8Array;
    ttlMs?: number;
    relayFingerprint?: string;
    now?: number;
  } = {},
): Promise<{ offer: RawPairingOffer; secret: Uint8Array; code: string; link: string }> {
  const identity = device instanceof MockDevice ? device.identity : device;
  const secret = options.secret ?? randomBytes(32);
  const code = options.code ?? makeCode("7K4Q9");
  const expiresAt = (options.now ?? Date.now()) + (options.ttlMs ?? 10 * 60_000);
  const offer: RawPairingOffer = {
    v: 1,
    relay: relayUrl,
    device_id: identity.peerId,
    device_pub: toBase64Url(identity.publicKey),
    secret: toBase64Url(secret),
    code,
    exp: Math.floor(expiresAt / 1000),
    ...(options.relayFingerprint === undefined ? {} : { relay_fp: options.relayFingerprint }),
  };
  const link = `phonebridge://pair?d=${Buffer.from(JSON.stringify(offer), "utf8").toString("base64url")}`;
  return { offer, secret, code, link };
}

/** A deterministic X25519 key pair for tests that need to assert on derived keys. */
export async function deterministicIdentity(label: string): Promise<Identity> {
  const { identityFromSecret, sha256 } = await import("../../src/client/crypto.js");
  return identityFromSecret(sha256(utf8(label)));
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
        data_b64: Buffer.from("mock-png-bytes").toString("base64"),
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
      return { op: args["op"], path: args["path"], entries: [] };
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

