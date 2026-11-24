/**
 * Relay transport, matching `relay/PROTOCOL-RELAY.md` and `core/.../crypto/HelloAuth.kt`.
 *
 *   peer                                relay
 *    | -------- WebSocket upgrade -------> |
 *    | <------------- challenge ---------- |   nonce, per-connection relay_pub, relay_id
 *    | ---------------- hello -----------> |   pub, nonce echo, dh-hmac-sha256 sig
 *    | <------------- hello_ok ----------- |
 *    | <============= route =============> |   opaque end-to-end frames only
 *
 * `wss://` relays are pinned when the pairing offer carries `relay_pin`.
 */
import { createHash } from "node:crypto";
import { randomUUID } from "node:crypto";
import type { ConnectionOptions } from "node:tls";
import WebSocket, { type ClientOptions } from "ws";
import { fromBase64Loose, toBase64Url, utf8 } from "./bytes.js";
import type { Identity } from "./crypto.js";
import { helloProof, parseChallenge, HELLO_PROOF_METHOD } from "./helloAuth.js";
import { PhoneBridgeError, remoteError } from "./errors.js";
import { normalizeRelayUrl } from "./pairing.js";

export interface Envelope {
  v?: number;
  type: string;
  from?: string;
  to?: string;
  id?: string;
  payload?: string;
  pub?: string;
  sig?: string;
  role?: string;
  nonce?: string;
  relay_pub?: string;
  relay_id?: string;
  proof?: string;
  expires?: number;
  move_token?: string;
  peer?: string;
  server_time?: number;
  limits?: Record<string, unknown>;
  error?: { code?: string; message?: string } | string;
  code?: string;
  offer?: Record<string, unknown>;
  device_id?: string;
  device_name?: string;
  peers?: RelayPeerEntry[];
  count?: number;
  limit?: number;
  consume?: boolean;
  ids?: string[];
  ack_ids?: string[];
  created_at?: number;
  message?: RelayBotMessage;
  messages?: RelayBotMessage[];
  new_url?: string;
  token?: string;
  [key: string]: unknown;
}

export interface RelayConnectionOptions {
  url: string;
  role: "bot" | "device";
  identity: Identity;
  /** Base64url (or hex) SHA-256 of the relay certificate, from the pairing offer's `relay_pin`. */
  relayPin?: string | undefined;
  insecureDev?: boolean;
  connectTimeoutMs?: number;
  /** How long to wait for the relay's challenge/hello_ok (docs: 30 s). */
  helloTimeoutMs?: number;
}

export interface RelayInfo {
  relayId: string | undefined;
  limits: Record<string, unknown> | undefined;
  moved: boolean;
}

export interface PairingLookup {
  code: string;
  offer: Record<string, unknown>;
  expires: number | undefined;
}

/** One paired bot as published by a device, matching `core` `RelayPeerEntry`. */
export interface RelayPeerEntry {
  peer_id: string;
  name: string;
  public_key: string;
  fingerprint?: string;
  device_id?: string;
  device_name?: string;
  enabled?: boolean;
  can_message?: boolean;
  online?: boolean;
}

/** One stored bot message, matching `core` `RelayBotMessage`. */
export interface RelayBotMessage {
  id: string;
  from: string;
  from_name?: string;
  to: string;
  device_id?: string;
  created_at: number;
  expires_at?: number;
  payload: string;
}

type Handler<T> = (value: T) => void;

interface Waiter {
  match: (message: Envelope) => boolean;
  resolve: (message: Envelope) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export class RelayConnection {
  readonly url: string;
  private readonly options: RelayConnectionOptions;
  private socket: WebSocket | undefined;
  private waiters: Waiter[] = [];
  private buffered: Envelope[] = [];
  private ready = false;
  private closed = false;
  private info: RelayInfo = { relayId: undefined, limits: undefined, moved: false };
  private envelopeHandlers = new Set<Handler<Envelope>>();
  private botMessageHandlers = new Set<Handler<RelayBotMessage>>();
  private errorHandlers = new Set<Handler<PhoneBridgeError>>();
  private moveHandlers = new Set<Handler<Envelope>>();
  private closeHandlers = new Set<Handler<{ code: number; reason: string }>>();

  constructor(options: RelayConnectionOptions) {
    this.options = options;
    this.url = normalizeRelayUrl(options.url, options.insecureDev === true);
  }

  get isReady(): boolean {
    return this.ready && this.socket?.readyState === WebSocket.OPEN;
  }

  get isClosed(): boolean {
    return this.closed;
  }

  get relayId(): string | undefined {
    return this.info.relayId;
  }

  onEnvelope(handler: Handler<Envelope>): () => void {
    this.envelopeHandlers.add(handler);
    return () => this.envelopeHandlers.delete(handler);
  }

  /** Live `msg_deliver` pushes. Handlers run only for well-formed messages addressed to this bot. */
  onBotMessage(handler: Handler<RelayBotMessage>): () => void {
    this.botMessageHandlers.add(handler);
    return () => this.botMessageHandlers.delete(handler);
  }

  onRelayError(handler: Handler<PhoneBridgeError>): () => void {
    this.errorHandlers.add(handler);
    return () => this.errorHandlers.delete(handler);
  }

  onRelayMoved(handler: Handler<Envelope>): () => void {
    this.moveHandlers.add(handler);
    return () => this.moveHandlers.delete(handler);
  }

  onClose(handler: Handler<{ code: number; reason: string }>): () => void {
    this.closeHandlers.add(handler);
    return () => this.closeHandlers.delete(handler);
  }

  async connect(): Promise<RelayInfo> {
    if (this.socket !== undefined) throw new PhoneBridgeError("relay_error", "relay connection already opened");
    const timeout = this.options.connectTimeoutMs ?? 20_000;
    const socket = new WebSocket(this.url, this.socketOptions());
    this.socket = socket;

    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new PhoneBridgeError("relay_error", `relay connect timed out after ${timeout} ms`));
        socket.terminate();
      }, timeout);
      const onError = (error: Error): void => {
        clearTimeout(timer);
        reject(new PhoneBridgeError("relay_error", `relay connection failed: ${error.message}`));
      };
      socket.once("error", onError);
      socket.once("open", () => {
        clearTimeout(timer);
        socket.off("error", onError);
        socket.on("error", (error) => {
          this.dispatchError(new PhoneBridgeError("relay_error", error.message));
        });
        resolve();
      });
      socket.on("message", (data) => this.handleMessage(data.toString()));
      socket.on("close", (code, reason) => this.handleClose(code, reason.toString()));
    });

    try {
      await this.authenticate();
    } catch (error) {
      this.close();
      throw error;
    }
    this.ready = true;
    const pending = this.buffered;
    this.buffered = [];
    for (const message of pending) this.dispatch(message);
    return this.info;
  }

  private socketOptions(): ClientOptions {
    if (!this.url.startsWith("wss:")) return {};
    const tlsOptions: ConnectionOptions = { rejectUnauthorized: true };
    const pin = this.options.relayPin;
    if (pin !== undefined && pin.trim() !== "") {
      const expected = decodePin(pin);
      tlsOptions.checkServerIdentity = (_hostname, cert) => {
        const actual = new Uint8Array(createHash("sha256").update(cert.raw).digest());
        if (!bytesEq(actual, expected)) {
          return new Error(
            `relay certificate fingerprint mismatch: expected ${toBase64Url(expected)}, got ${toBase64Url(actual)}`,
          );
        }
        return undefined;
      };
    }
    return tlsOptions as unknown as ClientOptions;
  }

  private async authenticate(): Promise<void> {
    const identity = this.options.identity;
    const helloTimeout = this.options.helloTimeoutMs ?? 30_000;

    const challengeEnvelope = await this.waitFor(
      (message) => message.type === "challenge" || message.type === "error",
      helloTimeout,
      "relay challenge",
    );
    if (challengeEnvelope.type === "error") throw this.envelopeError(challengeEnvelope);

    const challenge = parseChallenge(challengeEnvelope as unknown as Record<string, unknown>);
    const proof = await helloProof(
      identity.secretKey,
      identity.publicKey,
      challenge.relayPublicKey,
      challenge.relayId,
      identity.peerId,
      challenge.nonce,
    );
    this.sendRaw({
      v: 1,
      type: "hello",
      from: identity.peerId,
      pub: toBase64Url(identity.publicKey),
      nonce: toBase64Url(challenge.nonce),
      sig: toBase64Url(proof),
      role: this.options.role,
    });

    const ok = await this.waitFor(
      (message) => message.type === "hello_ok" || message.type === "error",
      helloTimeout,
      "relay hello_ok",
    );
    if (ok.type === "error") throw this.envelopeError(ok);
    const peer = typeof ok.peer === "string" ? ok.peer : typeof ok.to === "string" ? ok.to : undefined;
    if (peer !== undefined && peer !== identity.peerId) {
      throw new PhoneBridgeError("relay_error", "relay authenticated a different peer_id", {
        expected: identity.peerId,
        actual: peer,
      });
    }
    this.info = {
      relayId: typeof ok.relay_id === "string" ? ok.relay_id : challenge.relayId,
      limits: typeof ok.limits === "object" && ok.limits !== null ? ok.limits : undefined,
      moved: false,
    };
    if (typeof challengeEnvelope.proof === "string" && challengeEnvelope.proof !== HELLO_PROOF_METHOD) {
      throw new PhoneBridgeError("relay_error", "relay asked for an unsupported proof method");
    }
  }

  /** Sends one end-to-end frame to `to`; returns the relay envelope id for correlation. */
  sendRoute(to: string, frame: Uint8Array): string {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "route",
      from: this.options.identity.peerId,
      to,
      id,
      payload: toBase64Url(frame),
    });
    return id;
  }

  /** Sends a non-route envelope such as `pair_store` (device role) or `ping`. */
  sendControl(message: { type: string; [key: string]: unknown }): void {
    const envelope = {
      ...message,
      from: (message["from"] as string | undefined) ?? this.options.identity.peerId,
    } as Envelope;
    this.sendRaw(envelope);
  }

  /** Short-code pairing: asks the relay for the offer behind a code. */
  async lookupPairing(code: string, timeoutMs = 10_000): Promise<PairingLookup> {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "pair_lookup",
      from: this.options.identity.peerId,
      id,
      code,
    });
    const reply = await this.waitFor(
      (message) =>
        (message.type === "pair_offer" || message.type === "error") &&
        (message.id === undefined || message.id === id),
      timeoutMs,
      "pair_lookup",
    );
    if (reply.type === "error") {
      const error = this.envelopeError(reply);
      const relayCode = relayErrorCode(reply);
      if (relayCode === "code_not_found" || relayCode === "code_expired") {
        throw new PhoneBridgeError("expired", "the pairing code is unknown or has expired", {
          code,
          relayCode,
        });
      }
      throw error;
    }
    if (typeof reply.offer !== "object" || reply.offer === null) {
      throw new PhoneBridgeError("relay_error", "relay pair_offer reply has no offer");
    }
    return {
      code: typeof reply.code === "string" ? reply.code : normalizeLookupCode(code),
      offer: reply.offer,
      expires: typeof reply.expires === "number" ? reply.expires : undefined,
    };
  }

  /** Bot-to-bot ACL: asks the relay which paired bots this bot may message. */
  async listPeers(deviceId?: string, timeoutMs = 10_000): Promise<RelayPeerEntry[]> {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "peers_list",
      from: this.options.identity.peerId,
      id,
      ...(deviceId === undefined ? {} : { device_id: deviceId }),
    });
    const reply = await this.waitFor(
      (message) =>
        (message.type === "peers_result" || message.type === "error") &&
        (message.id === undefined || message.id === id),
      timeoutMs,
      "peers_list",
    );
    if (reply.type === "error") throw this.envelopeError(reply);
    if (!Array.isArray(reply.peers)) {
      throw new PhoneBridgeError("relay_error", "relay peers_result has no peers list");
    }
    return reply.peers.map(readPeerEntry);
  }

  /** Stores one encrypted bot message in the recipient's mailbox. */
  async sendBotMessage(
    message: RelayBotMessage,
    deviceId?: string,
    timeoutMs = 10_000,
  ): Promise<RelayBotMessage> {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "msg_send",
      from: this.options.identity.peerId,
      id,
      ...(deviceId === undefined ? {} : { device_id: deviceId }),
      message,
    });
    const reply = await this.waitFor(
      (candidate) =>
        (candidate.type === "msg_sent" || candidate.type === "error") &&
        (candidate.id === undefined || candidate.id === id),
      timeoutMs,
      "msg_send",
    );
    if (reply.type === "error") throw this.envelopeError(reply);
    if (reply.message === undefined) {
      throw new PhoneBridgeError("relay_error", "relay msg_sent has no message");
    }
    return readBotMessage(reply.message);
  }

  /** Reads (and optionally consumes) this bot's store-and-forward mailbox. */
  async readBotInbox(
    options: { limit?: number; consume?: boolean; ackIds?: string[]; deviceId?: string } = {},
    timeoutMs = 10_000,
  ): Promise<RelayBotMessage[]> {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "msg_inbox",
      from: this.options.identity.peerId,
      id,
      ...(options.deviceId === undefined ? {} : { device_id: options.deviceId }),
      ...(options.limit === undefined ? {} : { limit: options.limit }),
      ...(options.consume === undefined ? {} : { consume: options.consume }),
      ...(options.ackIds === undefined ? {} : { ack_ids: options.ackIds }),
    });
    const reply = await this.waitFor(
      (candidate) =>
        (candidate.type === "msg_list" || candidate.type === "error") &&
        (candidate.id === undefined || candidate.id === id),
      timeoutMs,
      "msg_inbox",
    );
    if (reply.type === "error") throw this.envelopeError(reply);
    const messages = Array.isArray(reply.messages) ? reply.messages : [];
    return messages.map(readBotMessage);
  }

  /** Acknowledges live-delivered message ids so the relay removes them from the mailbox. */
  async ackBotMessages(ids: string[], deviceId?: string, timeoutMs = 10_000): Promise<string[]> {
    const id = randomUUID();
    this.sendRaw({
      v: 1,
      type: "msg_ack",
      from: this.options.identity.peerId,
      id,
      ...(deviceId === undefined ? {} : { device_id: deviceId }),
      ids,
    });
    const reply = await this.waitFor(
      (candidate) =>
        (candidate.type === "msg_acked" || candidate.type === "error") &&
        (candidate.id === undefined || candidate.id === id),
      timeoutMs,
      "msg_ack",
    );
    if (reply.type === "error") throw this.envelopeError(reply);
    return Array.isArray(reply.ack_ids) ? reply.ack_ids.filter((entry): entry is string => typeof entry === "string") : [];
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    this.rejectWaiters(new PhoneBridgeError("peer_offline", "relay connection closed"));
    this.socket?.close();
    this.socket = undefined;
  }

  private sendRaw(message: Envelope): void {
    const socket = this.socket;
    if (socket === undefined || socket.readyState !== WebSocket.OPEN) {
      throw new PhoneBridgeError("relay_error", "relay socket is not open");
    }
    socket.send(JSON.stringify({ v: 1, ...message }));
  }

  private handleMessage(text: string): void {
    let message: Envelope;
    try {
      message = JSON.parse(text) as Envelope;
    } catch {
      this.dispatchError(new PhoneBridgeError("relay_error", "relay sent invalid JSON"));
      return;
    }
    if (typeof message !== "object" || message === null || typeof message.type !== "string") {
      this.dispatchError(new PhoneBridgeError("relay_error", "relay sent an envelope without a type"));
      return;
    }
    if (message.type === "ping") {
      try {
        this.sendRaw({ v: 1, type: "pong", from: this.options.identity.peerId, to: message.from, id: message.id });
      } catch {
        /* socket closing */
      }
      return;
    }
    if (message.type === "pong") return;

    const waiterIndex = this.waiters.findIndex((waiter) => waiter.match(message));
    if (waiterIndex >= 0) {
      const [waiter] = this.waiters.splice(waiterIndex, 1);
      if (waiter !== undefined) {
        clearTimeout(waiter.timer);
        waiter.resolve(message);
      }
      return;
    }
    if (!this.ready && this.buffered.length < 256) {
      this.buffered.push(message);
      return;
    }
    this.dispatch(message);
  }

  private dispatch(message: Envelope): void {
    switch (message.type) {
      case "route":
        for (const handler of this.envelopeHandlers) handler(message);
        break;
      case "error": {
        const error = this.envelopeError(message);
        for (const handler of this.errorHandlers) handler(error);
        break;
      }
      case "relay_moved":
        this.info.moved = true;
        for (const handler of this.moveHandlers) handler(message);
        break;
      case "msg_deliver": {
        if (message.message === undefined) return;
        let delivered: RelayBotMessage;
        try {
          delivered = readBotMessage(message.message);
        } catch {
          return;
        }
        if (delivered.to !== this.options.identity.peerId) return;
        for (const handler of this.botMessageHandlers) {
          try {
            handler(delivered);
          } catch (error) {
            this.dispatchError(new PhoneBridgeError("internal", `bot message handler failed: ${String(error)}`));
          }
        }
        break;
      }
      case "challenge":
      case "hello_ok":
      case "pair_offer":
        break;
      default:
        // Unknown envelope types must be ignored, never fatal (docs/PROTOCOL.md 3.2).
        break;
    }
  }

  private dispatchError(error: PhoneBridgeError): void {
    for (const handler of this.errorHandlers) handler(error);
  }

  private handleClose(code: number, reason: string): void {
    this.ready = false;
    this.closed = true;
    this.rejectWaiters(new PhoneBridgeError("peer_offline", "relay connection closed", { code, reason }));
    for (const handler of this.closeHandlers) handler({ code, reason });
  }

  private envelopeError(message: Envelope): PhoneBridgeError {
    const relayCode = relayErrorCode(message) ?? "internal";
    const text =
      typeof message.error === "object" && message.error !== null && typeof message.error.message === "string"
        ? message.error.message
        : typeof message.error === "string"
          ? message.error
          : `relay error: ${relayCode}`;
    if (relayCode === "peer_offline") {
      return new PhoneBridgeError("peer_offline", text, { relayCode });
    }
    const mapped = remoteError({ code: relayCode, message: text });
    return new PhoneBridgeError(
      ["permission_denied", "bad_args", "timeout", "internal"].includes(mapped.code) ? mapped.code : "relay_error",
      text,
      { relayCode },
    );
  }

  private waitFor(
    match: (message: Envelope) => boolean,
    timeoutMs: number,
    label: string,
  ): Promise<Envelope> {
    // A relay may send the challenge in the same tick as the upgrade; it is buffered while the
    // socket is not marked ready, so look there before waiting.
    const index = this.buffered.findIndex(match);
    if (index >= 0) {
      const [buffered] = this.buffered.splice(index, 1);
      if (buffered !== undefined) return Promise.resolve(buffered);
    }
    return new Promise<Envelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((waiter) => waiter.timer !== timer);
        reject(new PhoneBridgeError("timeout", `${label} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push({ match, resolve, reject, timer });
    });
  }

  private rejectWaiters(error: Error): void {
    const waiters = this.waiters;
    this.waiters = [];
    for (const waiter of waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
  }
}

function relayErrorCode(message: Envelope): string | undefined {
  if (typeof message.error === "object" && message.error !== null && typeof message.error.code === "string") {
    return message.error.code;
  }
  if (typeof message.error === "string" && message.error !== "") return message.error;
  if (typeof message.code === "string") return message.code;
  return undefined;
}

function normalizeLookupCode(code: string): string {
  return code.trim().toUpperCase();
}

function readPeerEntry(value: unknown): RelayPeerEntry {
  if (typeof value !== "object" || value === null) {
    throw new PhoneBridgeError("relay_error", "relay peers_result has a malformed peer");
  }
  const entry = value as Record<string, unknown>;
  if (typeof entry["peer_id"] !== "string" || typeof entry["name"] !== "string" || typeof entry["public_key"] !== "string") {
    throw new PhoneBridgeError("relay_error", "relay peer needs peer_id, name and public_key");
  }
  return {
    peer_id: entry["peer_id"],
    name: entry["name"],
    public_key: entry["public_key"],
    ...optionalString(entry, "fingerprint"),
    ...optionalString(entry, "device_id"),
    ...optionalString(entry, "device_name"),
    ...optionalBoolean(entry, "enabled"),
    ...optionalBoolean(entry, "can_message"),
    ...optionalBoolean(entry, "online"),
  };
}

function readBotMessage(value: unknown): RelayBotMessage {
  if (typeof value !== "object" || value === null) {
    throw new PhoneBridgeError("relay_error", "relay sent a malformed bot message");
  }
  const message = value as Record<string, unknown>;
  const createdAt = message["created_at"];
  if (
    typeof message["id"] !== "string" ||
    typeof message["from"] !== "string" ||
    typeof message["to"] !== "string" ||
    typeof message["payload"] !== "string" ||
    typeof createdAt !== "number" ||
    !Number.isFinite(createdAt)
  ) {
    throw new PhoneBridgeError("relay_error", "bot message needs id, from, to, created_at and payload");
  }
  return {
    id: message["id"],
    from: message["from"],
    to: message["to"],
    created_at: createdAt,
    payload: message["payload"],
    ...optionalString(message, "from_name"),
    ...optionalString(message, "device_id"),
    ...(typeof message["expires_at"] === "number" ? { expires_at: message["expires_at"] } : {}),
  };
}

function optionalString(record: Record<string, unknown>, key: string): Record<string, string> {
  return typeof record[key] === "string" ? { [key]: record[key] as string } : {};
}

function optionalBoolean(record: Record<string, unknown>, key: string): Record<string, boolean> {
  return typeof record[key] === "boolean" ? { [key]: record[key] as boolean } : {};
}

/** Accepts a base64url or hex SHA-256 pin and returns the 32 raw bytes. */
export function decodePin(pin: string): Uint8Array {
  const text = pin.trim();
  if (/^[0-9a-fA-F]{64}$/.test(text)) {
    return new Uint8Array(Buffer.from(text, "hex"));
  }
  const bytes = fromBase64Loose(text);
  if (bytes.length !== 32) throw new PhoneBridgeError("bad_input", "relay pin must be a SHA-256 fingerprint");
  return bytes;
}

function bytesEq(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}

void utf8;
