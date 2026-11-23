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
