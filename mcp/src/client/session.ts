/**
 * An end-to-end session with one paired device: Noise `XX` over relay frames, then JSON
 * requests/responses/events and protocol `chunk` events for large payloads.
 */
import { randomUUID } from "node:crypto";
import { fromBase64Loose, toBase64Url, utf8 } from "./bytes.js";
import { ChunkAssembler, type ChunkPayload } from "./chunks.js";
import type { Identity } from "./crypto.js";
import { PhoneBridgeError, remoteError } from "./errors.js";
import { assertLevel, type PermissionLevel } from "./levels.js";
import type { StoredDevice } from "./keystore.js";
import { E2ESession, FRAME_HANDSHAKE, FRAME_TRANSPORT, frameType, MAX_PLAINTEXT_BYTES } from "./noise.js";
import { RelayConnection, type Envelope, type RelayConnectionOptions } from "./relay.js";

export interface DeviceTarget {
  peerId: string;
  publicKey: Uint8Array;
  relayUrl: string;
  relayPin?: string | undefined;
  name: string;
  level: PermissionLevel;
}

export function targetFromStored(device: StoredDevice): DeviceTarget {
  return {
    peerId: device.peerId,
    publicKey: fromBase64Loose(device.publicKey),
    relayUrl: device.relayUrl,
    relayPin: device.relayPin,
    name: device.name,
    level: device.level,
  };
}

export interface ConnectOptions {
  identity: Identity;
  device: DeviceTarget;
  botName?: string;
  insecureDev?: boolean;
  handshakeTimeoutMs?: number;
  defaultTimeoutMs?: number;
  relayFactory?: (options: RelayConnectionOptions) => RelayConnection;
  /** Reuse an authenticated relay (the pairing flow already has one open). */
  relay?: RelayConnection | undefined;
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

interface ChunkWaiter {
  resolve: (payload: Uint8Array) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const DEFAULT_TIMEOUTS: Record<string, number> = {
  status: 10_000,
  screenshot: 90_000,
  ui_tree: 30_000,
  notifications: 15_000,
  install_apk: 300_000,
  request_control: 15_000,
  release_control: 10_000,
  relay_move: 20_000,
};

const CHUNK_TIMEOUT_MS = 60_000;

export class DeviceSession {
  readonly device: DeviceTarget;
  readonly botName: string;
  private readonly relay: RelayConnection;
  private readonly ownsRelay: boolean;
  private e2e: E2ESession | undefined;
  private state: "handshake" | "ready" | "closed" = "handshake";
  private readonly handshakeTimeoutMs: number;
  private readonly defaultTimeoutMs: number;

  private frameInbox: Uint8Array[] = [];
  private frameWaiters: Array<{
    resolve: (frame: Uint8Array) => void;
    reject: (error: Error) => void;
    timer: NodeJS.Timeout;
  }> = [];
  private pending = new Map<string, PendingRequest>();
  private assembler = new ChunkAssembler();
  private chunkWaiters = new Map<string, ChunkWaiter>();
  private completedChunks = new Map<string, Uint8Array>();
  private eventHandlers = new Set<(event: DeviceEvent) => void>();
  private closeHandlers = new Set<(reason: string) => void>();
  private lastEvent: DeviceEvent | undefined;
  private readonly unsubscribers: Array<() => void> = [];

  private constructor(
    relay: RelayConnection,
    ownsRelay: boolean,
    options: ConnectOptions,
  ) {
    this.relay = relay;
    this.ownsRelay = ownsRelay;
    this.device = options.device;
    this.botName = options.botName ?? "PhoneBridge bot";
    this.handshakeTimeoutMs = options.handshakeTimeoutMs ?? 20_000;
    this.defaultTimeoutMs = options.defaultTimeoutMs ?? 20_000;
  }

  static async connect(options: ConnectOptions): Promise<DeviceSession> {
    let relay = options.relay;
    let ownsRelay = false;
    if (relay === undefined) {
      const factory =
        options.relayFactory ?? ((relayOptions: RelayConnectionOptions) => new RelayConnection(relayOptions));
      relay = factory({
        url: options.device.relayUrl,
        role: "bot",
        identity: options.identity,
        relayPin: options.device.relayPin,
        ...(options.insecureDev === undefined ? {} : { insecureDev: options.insecureDev }),
      });
      await relay.connect();
      ownsRelay = true;
    }
    const session = new DeviceSession(relay, ownsRelay, options);
    session.attach();
    try {
      await session.runHandshake(options.identity);
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

  get handshakeHash(): Uint8Array | undefined {
    return this.e2e?.handshakeHash;
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
    this.rejectFrameWaiters(new PhoneBridgeError("peer_offline", "session closed", { reason }));
    this.rejectChunkWaiters(new PhoneBridgeError("peer_offline", "session closed", { reason }));
    for (const unsubscribe of this.unsubscribers.splice(0)) unsubscribe();
    if (this.ownsRelay) this.relay.close();
    for (const handler of this.closeHandlers) handler(reason);
  }

  /** Sends a request and awaits the matching response, reassembling chunked payloads. */
  async request(cmd: string, args: Record<string, unknown> = {}, options: RequestOptions = {}): Promise<unknown> {
    if (this.state !== "ready" || this.e2e === undefined) {
      throw new PhoneBridgeError("peer_offline", "device session is not connected");
    }
    assertLevel(this.device.level, cmd);
    const id = randomUUID();
    const json = JSON.stringify({ id, cmd, args });
    if (utf8(json).length > MAX_PLAINTEXT_BYTES) {
      throw new PhoneBridgeError(
        "bad_args",
        `request of ${utf8(json).length} bytes exceeds the 8 MiB transport limit; use a URL instead of inline data`,
      );
    }
    const frame = await this.e2e.sealText(json);
    const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUTS[cmd] ?? this.defaultTimeoutMs;
    const promise = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new PhoneBridgeError("timeout", `${cmd} timed out after ${timeoutMs} ms`, { cmd, id }));
      }, timeoutMs);
      this.pending.set(id, { id, cmd, resolve, reject, timer });
    });
    try {
      this.relay.sendRoute(this.device.peerId, frame);
    } catch (error) {
      const pending = this.pending.get(id);
      if (pending !== undefined) {
        this.pending.delete(id);
        clearTimeout(pending.timer);
      }
      throw error instanceof PhoneBridgeError
        ? error
        : new PhoneBridgeError("peer_offline", "could not send the request to the relay");
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

  longPress(args: { x?: number; y?: number; node_id?: string; ms?: number }): Promise<unknown> {
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
    return this.request("shell", args, { timeoutMs: (args.timeout_ms ?? this.defaultTimeoutMs) + 10_000 });
  }

  files(args: { op: string; path: string; data_b64?: string }): Promise<unknown> {
    return this.request("files", args, { timeoutMs: 120_000 });
  }

  settings(args: { namespace: string; key: string; value?: string }): Promise<unknown> {
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

  // ---- internals ------------------------------------------------------------

  private attach(): void {
    this.unsubscribers.push(
      this.relay.onEnvelope((envelope) => this.handleEnvelope(envelope)),
      this.relay.onRelayError((error) => {
        if (this.state === "closed") return;
        if (error.code === "peer_offline") {
          this.rejectPending(new PhoneBridgeError("peer_offline", error.message, { target: this.device.peerId }));
          return;
        }
        this.pushError(error);
      }),
      this.relay.onRelayMoved((envelope) => this.handleRelayMoved(envelope)),
      this.relay.onClose(({ code, reason }) => {
        if (this.state === "closed") return;
        this.state = "closed";
        const message = `relay connection closed (${code}${reason ? `: ${reason}` : ""})`;
        this.rejectPending(new PhoneBridgeError("peer_offline", message));
        this.rejectFrameWaiters(new PhoneBridgeError("peer_offline", message));
        this.rejectChunkWaiters(new PhoneBridgeError("peer_offline", message));
        for (const handler of this.closeHandlers) handler(reason || "relay closed");
      }),
    );
  }

  private async runHandshake(identity: Identity): Promise<void> {
    const e2e = new E2ESession("initiator", identity);
    this.relay.sendRoute(this.device.peerId, await e2e.writeHandshakeFrame());

    const reply = await this.takeFrame(this.handshakeTimeoutMs, "noise hs2");
    if (frameType(reply) !== FRAME_HANDSHAKE) {
      throw new PhoneBridgeError("handshake_failed", "expected a Noise handshake frame from the device");
    }
    await e2e.readHandshakeFrame(reply);

    this.relay.sendRoute(this.device.peerId, await e2e.writeHandshakeFrame());
    // The initiator completes the XX pattern by writing the third message.
    if (!e2e.isEstablished) {
      throw new PhoneBridgeError("handshake_failed", "Noise handshake did not complete");
    }
    e2e.requirePeer(this.device.publicKey);
    this.e2e = e2e;
    this.state = "ready";
    this.flushFrames();
  }

  private handleEnvelope(envelope: Envelope): void {
    if (this.state === "closed") return;
    if (envelope.from !== undefined && envelope.from !== this.device.peerId) return;
    if (typeof envelope.payload !== "string") return;
    let frame: Uint8Array;
    try {
      frame = fromBase64Loose(envelope.payload);
    } catch {
      this.pushError(new PhoneBridgeError("bad_args", "device sent an invalid base64 payload"));
      return;
    }
    if (this.state === "handshake") {
      this.pushFrame(frame);
      return;
    }
    if (frameType(frame) !== FRAME_TRANSPORT) return;
    void this.handleTransportFrame(frame);
  }

  private async handleTransportFrame(frame: Uint8Array): Promise<void> {
    let text: string;
    try {
      if (this.e2e === undefined) throw new PhoneBridgeError("handshake_failed", "no session");
      text = await this.e2e.openText(frame);
    } catch (error) {
      this.pushError(
        error instanceof PhoneBridgeError ? error : new PhoneBridgeError("handshake_failed", String(error)),
      );
      return;
    }
    let value: unknown;
    try {
      value = JSON.parse(text) as unknown;
    } catch {
      this.pushError(new PhoneBridgeError("bad_args", "device sent invalid JSON inside an encrypted frame"));
      return;
    }
    await this.handleApplication(value);
  }

  private async handleApplication(value: unknown): Promise<void> {
    if (typeof value !== "object" || value === null) {
      this.pushError(new PhoneBridgeError("bad_args", "device sent a non-object message"));
      return;
    }
    const message = value as Record<string, unknown>;

    if (typeof message["event"] === "string") {
      await this.handleEvent(message["event"], message["data"]);
      return;
    }

    const id = message["id"];
    if (typeof id !== "string") {
      this.pushError(new PhoneBridgeError("bad_args", "device sent a message with neither an id nor an event"));
      return;
    }
    const pending = this.pending.get(id);
    if (pending === undefined) return;
    this.pending.delete(id);
    clearTimeout(pending.timer);
    if (message["ok"] === false || message["error"] !== undefined) {
      pending.reject(remoteError(message["error"] ?? { code: "internal", message: "request failed" }));
      return;
    }
    pending.resolve(await this.materialiseChunks(message["result"]));
  }

  private async handleEvent(event: string, data: unknown): Promise<void> {
    if (event === "chunk") {
      this.handleChunk(data);
      this.emit({ event, data });
      return;
    }
    this.emit({ event, data });
    if (event === "kicked") this.close("kicked by the device");
  }

  private handleChunk(data: unknown): void {
    if (typeof data !== "object" || data === null) {
      this.pushError(new PhoneBridgeError("bad_args", "device sent a chunk without data"));
      return;
    }
    const record = data as Record<string, unknown>;
    const chunk: ChunkPayload = {
      chunk_id: typeof record["chunk_id"] === "string" ? record["chunk_id"] : "",
      index: typeof record["index"] === "number" ? record["index"] : -1,
      total: typeof record["total"] === "number" ? record["total"] : -1,
      data_b64: typeof record["data_b64"] === "string" ? record["data_b64"] : "",
      ...(typeof record["sha256"] === "string" ? { sha256: record["sha256"] } : {}),
    };
    let payload: Uint8Array | undefined;
    try {
      payload = this.assembler.accept(chunk);
    } catch (error) {
      this.pushError(
        error instanceof PhoneBridgeError ? error : new PhoneBridgeError("bad_args", String(error)),
      );
      return;
    }
    if (payload === undefined) return;
    const waiter = this.chunkWaiters.get(chunk.chunk_id);
    if (waiter !== undefined) {
      this.chunkWaiters.delete(chunk.chunk_id);
      clearTimeout(waiter.timer);
      waiter.resolve(payload);
      return;
    }
    this.completedChunks.set(chunk.chunk_id, payload);
  }

  /** If a result announced `chunked:true`, waits for the chunk events and attaches `data_b64`. */
  private async materialiseChunks(result: unknown): Promise<unknown> {
    if (typeof result !== "object" || result === null) return result;
    const record = result as Record<string, unknown>;
    if (record["chunked"] !== true) return result;
    const chunkId = typeof record["chunk_id"] === "string" ? record["chunk_id"] : "";
    if (chunkId === "") {
      throw new PhoneBridgeError("bad_args", "device announced chunks without a chunk_id");
    }
    const payload = await this.awaitChunk(chunkId);
    const announcedSize = typeof record["size"] === "number" ? record["size"] : undefined;
    if (announcedSize !== undefined && announcedSize !== payload.length) {
      throw new PhoneBridgeError("bad_args", "reassembled payload size does not match the announcement");
    }
    return { ...record, data_b64: toBase64Url(payload), bytes: payload.length };
  }

  private awaitChunk(chunkId: string): Promise<Uint8Array> {
    const ready = this.completedChunks.get(chunkId);
    if (ready !== undefined) {
      this.completedChunks.delete(chunkId);
      return Promise.resolve(ready);
    }
    return new Promise<Uint8Array>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.chunkWaiters.delete(chunkId);
        reject(new PhoneBridgeError("timeout", `chunked payload ${chunkId} did not complete in time`));
      }, CHUNK_TIMEOUT_MS);
      this.chunkWaiters.set(chunkId, { resolve, reject, timer });
    });
  }

  private handleRelayMoved(envelope: Envelope): void {
    if (typeof envelope.new_url !== "string" || envelope.new_url.trim() === "") return;
    this.device.relayUrl = envelope.new_url;
    this.emit({
      event: "relay_moved",
      data: { new_url: envelope.new_url, token: envelope.token, expires: envelope.expires },
    });
  }

  private pushError(error: PhoneBridgeError): void {
    this.emit({ event: "error", data: error.toJSON() });
  }

  private emit(event: DeviceEvent): void {
    this.lastEvent = event;
    for (const handler of this.eventHandlers) handler(event);
  }

  private pushFrame(frame: Uint8Array): void {
    const waiter = this.frameWaiters.shift();
    if (waiter !== undefined) {
      clearTimeout(waiter.timer);
      waiter.resolve(frame);
      return;
    }
    this.frameInbox.push(frame);
  }

  private takeFrame(timeoutMs: number, label: string): Promise<Uint8Array> {
    const buffered = this.frameInbox.shift();
    if (buffered !== undefined) return Promise.resolve(buffered);
    return new Promise<Uint8Array>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.frameWaiters = this.frameWaiters.filter((waiter) => waiter.timer !== timer);
        reject(new PhoneBridgeError("timeout", `${label} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.frameWaiters.push({ resolve, reject, timer });
    });
  }

  private flushFrames(): void {
    const frames = this.frameInbox.splice(0);
    this.frameInbox = [];
    for (const frame of frames) {
      if (frameType(frame) === FRAME_TRANSPORT) void this.handleTransportFrame(frame);
    }
  }

  private rejectPending(error: PhoneBridgeError): void {
    const pending = [...this.pending.values()];
    this.pending.clear();
    for (const entry of pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
  }

  private rejectFrameWaiters(error: PhoneBridgeError): void {
    const waiters = this.frameWaiters;
    this.frameWaiters = [];
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }

  private rejectChunkWaiters(error: PhoneBridgeError): void {
    const waiters = [...this.chunkWaiters.values()];
    this.chunkWaiters.clear();
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.completedChunks.clear();
  }
}
