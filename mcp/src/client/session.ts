/**
 * An end-to-end session with one paired device.
 *
 * Flow: WebSocket to the relay (opaque envelopes) -> hs1/hs2 -> X25519 key schedule ->
 * hs3/hs4 confirmation -> XChaCha20-Poly1305 frames carrying JSON requests, responses and events.
 */
import { randomUUID } from "node:crypto";
import { fromBase64Url, fromUtf8, toBase64Url, utf8 } from "./bytes.js";
import { PhoneBridgeError, remoteError } from "./errors.js";
import {
  assertDevicePinned,
  assertReply,
  buildFinishMessage,
  createInitiatorHello,
  deriveHandshakeKeys,
  type HandshakeReply,
  verifyFinish,
} from "./handshake.js";
import type { Identity } from "./crypto.js";
import { assertLevel, type PermissionLevel } from "./levels.js";
import type { StoredDevice } from "./keystore.js";
import { RelayConnection, type Envelope, type RelayConnectionOptions } from "./relay.js";
import { ChunkAssembler, SecureChannel, sealMessage } from "./transport.js";

export interface DeviceTarget {
  peerId: string;
  publicKey: Uint8Array;
  relayUrl: string;
  relayFingerprint?: string | undefined;
  name: string;
  level: PermissionLevel;
}

export function targetFromStored(device: StoredDevice): DeviceTarget {
  return {
    peerId: device.peerId,
    publicKey: fromBase64Url(device.publicKey),
    relayUrl: device.relayUrl,
    relayFingerprint: device.relayFingerprint,
    name: device.name,
    level: device.level,
  };
}

export interface ConnectOptions {
  identity: Identity;
  device: DeviceTarget;
  botName?: string;
  /** One-time pairing secret; present only on the first handshake with a new device. */
  pairingSecret?: Uint8Array;
  insecureDev?: boolean;
  handshakeTimeoutMs?: number;
  defaultTimeoutMs?: number;
  /** Test seam: replaces the real WebSocket relay. */
  relayFactory?: (options: RelayConnectionOptions) => RelayConnection;
}

export interface RequestOptions {
  timeoutMs?: number;
}

export interface DeviceEvent {
  event: string;
  data: unknown;
}

interface PendingRequest {
  id: string;
  cmd: string;
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

interface Frame {
  kind: "handshake" | "secured";
  value: unknown;
}

const DEFAULT_TIMEOUTS: Record<string, number> = {
  status: 10_000,
  screenshot: 90_000,
  ui_tree: 30_000,
  notifications: 15_000,
  pair_request: 120_000,
  install_apk: 300_000,
  request_control: 15_000,
  release_control: 10_000,
  relay_move: 20_000,
};

export class DeviceSession {
  readonly device: DeviceTarget;
  readonly botName: string;
  private readonly relay: RelayConnection;
  private readonly identity: Identity;
  private readonly defaultTimeoutMs: number;
  private readonly handshakeTimeoutMs: number;

  private channel: SecureChannel | undefined;
  private state: "hs2" | "hs4" | "ready" | "closed" = "hs2";
  private inbox: Frame[] = [];
  private inboxWaiters: Array<{
    resolve: (frame: Frame) => void;
    reject: (error: Error) => void;
    timer: NodeJS.Timeout;
  }> = [];
  private pending = new Map<string, PendingRequest>();
  private assembler = new ChunkAssembler();
  private eventHandlers = new Set<(event: DeviceEvent) => void>();
  private closeHandlers = new Set<(reason: string) => void>();
  private lastEvent: DeviceEvent | undefined;
  private readonly unsubscribers: Array<() => void> = [];

  private constructor(relay: RelayConnection, identity: Identity, options: ConnectOptions) {
    this.relay = relay;
    this.identity = identity;
    this.device = options.device;
    this.botName = options.botName ?? "PhoneBridge bot";
    this.defaultTimeoutMs = options.defaultTimeoutMs ?? 20_000;
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 20_000;
  }

  static async connect(options: ConnectOptions): Promise<DeviceSession> {
    const factory =
      options.relayFactory ?? ((relayOptions: RelayConnectionOptions) => new RelayConnection(relayOptions));
    const relay = factory({
      url: options.device.relayUrl,
      role: "bot",
      identity: options.identity,
      relayFingerprint: options.device.relayFingerprint,
      insecureDev: options.insecureDev,
    });
    await relay.connect();
    const session = new DeviceSession(relay, options.identity, options);
    session.attach();
    try {
      await session.runHandshake(options);
    } catch (error) {
      session.close("handshake failed");
      throw error;
    }
    return session;
  }

  get isReady(): boolean {
    return this.state === "ready" && !this.relay.isClosed;
  }

  get grantedLevel(): PermissionLevel {
    return this.device.level;
  }

  onEvent(handler: (event: DeviceEvent) => void): () => void {
    this.eventHandlers.add(handler);
    return () => this.eventHandlers.delete(handler);
  }

  onClose(handler: (reason: string) => void): () => void {
    this.closeHandlers.add(handler);
    return () => this.closeHandlers.delete(handler);
  }

  get lastEventSeen(): DeviceEvent | undefined {
    return this.lastEvent;
  }

  close(reason = "closed by the bot"): void {
    if (this.state === "closed") return;
    this.state = "closed";
    this.rejectPending(new PhoneBridgeError("peer_offline", "session closed", { reason }));
    this.rejectInbox(new PhoneBridgeError("peer_offline", "session closed", { reason }));
    for (const unsubscribe of this.unsubscribers.splice(0)) unsubscribe();
    this.relay.close();
    for (const handler of this.closeHandlers) handler(reason);
  }

  /**
   * Sends a request and awaits the matching response. Rejects with `timeout` after `timeoutMs`
   * (per-command defaults: status 10 s, screenshot 90 s, install_apk 300 s …).
   */
  async request(cmd: string, args: Record<string, unknown> = {}, options: RequestOptions = {}): Promise<unknown> {
    if (this.state !== "ready" || this.channel === undefined) {
      throw new PhoneBridgeError("peer_offline", "device session is not connected");
    }
    assertLevel(this.device.level, cmd);
    const id = randomUUID();
    const frames = await sealMessage(this.channel, { id, cmd, args });
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUTS[cmd] ?? this.defaultTimeoutMs;
    const promise = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new PhoneBridgeError("timeout", `${cmd} timed out after ${timeoutMs} ms`, { cmd, id }));
      }, timeoutMs);
      this.pending.set(id, { id, cmd, resolve, reject, timer });
    });
    for (const frame of frames) {
      this.relay.sendRoute(this.device.peerId, frame);
    }
    return promise;
  }

  // ---- convenience wrappers -------------------------------------------------

  status(): Promise<unknown> {
    return this.request("status");
  }

  screenshot(args: { format?: "png" | "jpeg"; quality?: number; max_width?: number } = {}): Promise<unknown> {
    return this.request("screenshot", args);
  }

  uiTree(args: { max_depth?: number; visible_only?: boolean } = {}): Promise<unknown> {
    return this.request("ui_tree", args);
  }

  notifications(args: { since?: number } = {}): Promise<unknown> {
    return this.request("notifications", args);
  }

  tap(target: { x: number; y: number } | { node_id: string }): Promise<unknown> {
    return this.request("tap", target);
  }

  longPress(args: { x: number; y: number; ms?: number }): Promise<unknown> {
    return this.request("long_press", args);
  }

  swipe(args: { x1: number; y1: number; x2: number; y2: number; ms?: number }): Promise<unknown> {
    return this.request("swipe", args);
  }

  typeText(args: { text: string; node_id?: string }): Promise<unknown> {
    return this.request("type", args);
  }

  key(key: string): Promise<unknown> {
    return this.request("key", { key });
  }

  openApp(args: { package?: string; label?: string }): Promise<unknown> {
    return this.request("open_app", args);
  }

  installApk(args: { url?: string; data_b64?: string; name?: string }): Promise<unknown> {
    return this.request("install_apk", args);
  }

  shell(args: { command: string; timeout_ms?: number }): Promise<unknown> {
    return this.request("shell", args, {
      timeoutMs: (args.timeout_ms ?? this.defaultTimeoutMs) + 10_000,
    });
  }

  files(args: { op: "list" | "read" | "write" | "delete"; path: string; data_b64?: string }): Promise<unknown> {
    return this.request("files", args);
  }

  settings(args: { namespace: "system" | "secure" | "global"; key: string; value?: string }): Promise<unknown> {
    return this.request("settings", args);
  }

  relayMove(args: { to: string }): Promise<unknown> {
    return this.request("relay_move", args);
  }

  requestControl(): Promise<unknown> {
    return this.request("request_control");
  }

  releaseControl(): Promise<unknown> {
    return this.request("release_control");
  }

  /** Sends the pairing request that makes the phone show "Allow this bot?". */
  pairRequest(requestedLevel: PermissionLevel): Promise<unknown> {
    return this.request(
      "pair_request",
      {
        bot_name: this.botName,
        bot_pub: toBase64Url(this.identity.publicKey),
        requested_level: requestedLevel,
      },
      { timeoutMs: 120_000 },
    );
  }

  // ---- internals ------------------------------------------------------------

  private attach(): void {
    this.unsubscribers.push(
      this.relay.onEnvelope((envelope) => this.handleEnvelope(envelope)),
      this.relay.onRelayError((envelope) => this.handleRelayError(envelope)),
      this.relay.onRelayMoved((envelope) => this.handleRelayMoved(envelope)),
      this.relay.onClose(({ code, reason }) => {
        if (this.state === "closed") return;
        this.state = "closed";
        this.rejectPending(
          new PhoneBridgeError("peer_offline", `relay connection closed (${code}${reason ? `: ${reason}` : ""})`),
        );
        this.rejectInbox(new PhoneBridgeError("peer_offline", "relay connection closed"));
        for (const handler of this.closeHandlers) handler(reason || "relay closed");
      }),
    );
  }

  private async runHandshake(options: ConnectOptions): Promise<void> {
    const hello = await createInitiatorHello(this.identity.publicKey, {
      ...(options.pairingSecret === undefined ? {} : { pairing: true }),
    });
    this.sendPayload(hello.message);

    const replyFrame = await this.takeFrame(this.handshakeTimeoutMs, "hs2");
    if (replyFrame.kind !== "handshake") {
      throw new PhoneBridgeError("handshake_failed", "expected a plaintext hs2 handshake reply");
    }
    const reply = replyFrame.value as HandshakeReply;
    assertReply(reply);
    assertDevicePinned(reply, this.device.publicKey);

    const keys = await deriveHandshakeKeys({
      role: "bot",
      staticKeyPair: this.identity,
      peerStaticPublic: this.device.publicKey,
      ephemeralKeyPair: hello.ephemeralKeyPair,
      peerEphemeralPublic: fromBase64Url(reply.e),
      ownNonce: hello.nonce,
      peerNonce: fromBase64Url(reply.n),
      ...(options.pairingSecret === undefined ? {} : { pairingSecret: options.pairingSecret }),
    });

    this.channel = new SecureChannel(keys.botToDevice, keys.deviceToBot);
    this.state = "hs4";
    this.sendPayload(await this.channel.sealJson(buildFinishMessage("hs3", keys.transcriptHash)));

    const finishFrame = await this.takeFrame(this.handshakeTimeoutMs, "hs4");
    if (finishFrame.kind !== "secured") {
      throw new PhoneBridgeError("handshake_failed", "expected an encrypted hs4 confirmation");
    }
    verifyFinish(finishFrame.value, "hs4", keys.transcriptHash);
    this.state = "ready";
    this.flushInbox();
  }

  private handleEnvelope(envelope: Envelope): void {
    if (this.state === "closed") return;
    if (envelope.from !== undefined && envelope.from !== this.device.peerId) return;
    if (typeof envelope.payload !== "string") return;
    const bytes = fromBase64Url(envelope.payload);
    if (this.state === "hs2") {
      let parsed: unknown;
      try {
        parsed = JSON.parse(fromUtf8(bytes)) as unknown;
      } catch {
        this.pushError(new PhoneBridgeError("handshake_failed", "received malformed handshake data"));
        return;
      }
      this.pushFrame({ kind: "handshake", value: parsed });
      return;
    }
    if (this.channel === undefined) return;
    void this.deliverSecure(bytes);
  }

  private async deliverSecure(ciphertext: Uint8Array): Promise<void> {
    let value: unknown;
    try {
      value = await this.openSecure(ciphertext);
    } catch (error) {
      this.pushError(
        error instanceof PhoneBridgeError ? error : new PhoneBridgeError("handshake_failed", String(error)),
      );
      return;
    }
    if (value === undefined) return; // partial chunk
    if (this.state === "hs4") {
      this.pushFrame({ kind: "secured", value });
      return;
    }
    this.handleApplication(value);
  }

  private async openSecure(ciphertext: Uint8Array): Promise<unknown> {
    const channel = this.channel;
    if (channel === undefined) throw new PhoneBridgeError("handshake_failed", "no session key");
    // Handshake finish frames (hs4) are still raw JSON, everything else is chunk-aware.
    if (this.state === "hs4") {
      return channel.openJson(ciphertext);
    }
    const decoded = await channel.openJson(ciphertext);
    return this.assembler.push(decoded);
  }

  private handleApplication(value: unknown): void {
    if (typeof value !== "object" || value === null) {
      this.pushError(new PhoneBridgeError("bad_args", "device sent a non-object message"));
      return;
    }
    const message = value as Record<string, unknown>;

    if (typeof message["event"] === "string") {
      const event: DeviceEvent = { event: message["event"], data: message["data"] };
      this.lastEvent = event;
      for (const handler of this.eventHandlers) handler(event);
      if (event.event === "kicked") this.close("kicked by the device");
      return;
    }

    const id = message["id"];
    if (typeof id === "string") {
      const pending = this.pending.get(id);
      if (pending === undefined) return;
      this.settle(pending, message);
      return;
    }

    // docs/PROTOCOL.md describes `pair_result { ok, granted_level, device_name }` without an id.
    if (message["ok"] !== undefined) {
      const pairPending = [...this.pending.values()].filter((entry) => entry.cmd === "pair_request");
      if (pairPending.length === 1) {
        this.settle(pairPending[0] as PendingRequest, message);
        return;
      }
    }

    this.pushError(
      new PhoneBridgeError("bad_args", "device sent a message with neither an id nor an event"),
    );
  }

  private sendPayload(value: unknown): void {
    const bytes = value instanceof Uint8Array ? value : utf8(JSON.stringify(value));
    this.relay.sendRoute(this.device.peerId, bytes);
  }

  /** Resolves or rejects one pending request from a response frame. */
  private settle(pending: PendingRequest, message: Record<string, unknown>): void {
    this.pending.delete(pending.id);
    clearTimeout(pending.timer);
    if (message["ok"] === false || message["error"] !== undefined) {
      pending.reject(
        remoteError(message["error"] ?? { code: "internal", message: "request failed" }),
      );
      return;
    }
    const result = message["result"];
    if (result !== undefined) {
      pending.resolve(result);
      return;
    }
    // pair_result / shorthand replies put fields at the top level.
    const { id: _id, ok: _ok, ...rest } = message;
    void _id;
    void _ok;
    pending.resolve(rest);
  }

  private takeFrame(timeoutMs: number, label: string): Promise<Frame> {
    const buffered = this.inbox.shift();
    if (buffered !== undefined) return Promise.resolve(buffered);
    return new Promise<Frame>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.inboxWaiters = this.inboxWaiters.filter((waiter) => waiter.timer !== timer);
        reject(new PhoneBridgeError("timeout", `${label} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.inboxWaiters.push({ resolve, reject, timer });
    });
  }

  private pushFrame(frame: Frame): void {
    const waiter = this.inboxWaiters.shift();
    if (waiter !== undefined) {
      clearTimeout(waiter.timer);
      waiter.resolve(frame);
      return;
    }
    this.inbox.push(frame);
  }

  private flushInbox(): void {
    const frames = this.inbox.splice(0);
    this.inbox = [];
    for (const frame of frames) {
      if (frame.kind === "secured") this.handleApplication(frame.value);
    }
  }

  private handleRelayError(envelope: Envelope): void {
    const code = envelope.code ?? "relay_error";
    if (code === "peer_offline") {
      this.rejectPending(
        new PhoneBridgeError("peer_offline", envelope.message ?? "the paired device is offline", {
          target: this.device.peerId,
        }),
      );
      return;
    }
    this.pushError(new PhoneBridgeError("relay_error", envelope.message ?? `relay error: ${code}`));
  }

  /** The relay moved ("the heart" changed host): remember the new URL and tell listeners. */
  private handleRelayMoved(envelope: Envelope): void {
    if (typeof envelope.new_url !== "string" || envelope.new_url.trim() === "") return;
    this.device.relayUrl = envelope.new_url;
    const event: DeviceEvent = {
      event: "relay_moved",
      data: {
        new_url: envelope.new_url,
        token: envelope.token,
        expires: envelope.expires,
      },
    };
    this.lastEvent = event;
    for (const handler of this.eventHandlers) handler(event);
  }

  private pushError(error: PhoneBridgeError): void {
    this.lastEvent = { event: "error", data: error.toJSON() };
    for (const handler of this.eventHandlers) handler(this.lastEvent);
  }

  private rejectPending(error: PhoneBridgeError): void {
    const pending = [...this.pending.values()];
    this.pending.clear();
    for (const entry of pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
  }

  private rejectInbox(error: PhoneBridgeError): void {
    const waiters = this.inboxWaiters;
    this.inboxWaiters = [];
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }
}
