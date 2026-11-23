/**
 * Relay transport: a WebSocket connection to the PhoneBridge relay.
 *
 * The relay only forwards opaque envelopes. This module implements the relay handshake from
 * docs/PROTOCOL.md, concretized as:
 *   1. peer -> relay  { v:1, type:"hello", from, pub, role, id }
 *   2. relay -> peer  { v:1, type:"hello_challenge", challenge:<b64url 32B>, id }
 *   3. peer -> relay  { v:1, type:"hello_proof", from, proof:<b64url 32B>, id }
 *   4. relay -> peer  { v:1, type:"hello_ok", peer_id, id }
 *   proof = X25519(peer long-term secret, challenge point). The relay holds the challenge scalar and
 *   checks X25519(challenge, peer public key) equals the proof, so key ownership is proved without
 *   sending any secret.
 *
 * A relay may answer `hello` directly with `hello_ok`. `wss://` relays are pinned with their
 * SHA-256 certificate fingerprint when the pairing offer carries `relay_fp`.
 */
import { createHash } from "node:crypto";
import type { ConnectionOptions } from "node:tls";
import WebSocket, { type ClientOptions } from "ws";
import { fromBase64Url, fromUtf8, toBase64Url } from "./bytes.js";
import { x25519, type Identity } from "./crypto.js";
import { PhoneBridgeError, remoteError } from "./errors.js";
import { normalizeRelayUrl } from "./pairing.js";

export interface Envelope {
  v: number;
  type: string;
  from?: string;
  to?: string;
  id?: string;
  payload?: string;
  code?: string;
  message?: string;
  new_url?: string;
  token?: string;
  expires?: number;
  challenge?: string;
  nonce?: string;
  peer_id?: string;
  pub?: string;
  proof?: string;
  role?: string;
  [key: string]: unknown;
}

export interface RelayConnectionOptions {
  url: string;
  role: "bot" | "device";
  identity: Identity;
  relayFingerprint?: string | undefined;
  insecureDev?: boolean;
  connectTimeoutMs?: number;
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
  private envelopeHandlers = new Set<Handler<Envelope>>();
  private errorHandlers = new Set<Handler<Envelope>>();
  private moveHandlers = new Set<Handler<Envelope>>();
  private closeHandlers = new Set<Handler<{ code: number; reason: string }>>();
  private serverFingerprint: string | undefined;

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

  get observedServerFingerprint(): string | undefined {
    return this.serverFingerprint;
  }

  onEnvelope(handler: Handler<Envelope>): () => void {
    this.envelopeHandlers.add(handler);
    return () => this.envelopeHandlers.delete(handler);
  }

  onRelayError(handler: Handler<Envelope>): () => void {
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

  async connect(): Promise<void> {
    if (this.socket !== undefined) throw new Error("relay connection already opened");
    const identity = this.options.identity;
    const timeout = this.options.connectTimeoutMs ?? 15_000;
    const socketOptions = this.socketOptions();
    const socket = new WebSocket(this.url, socketOptions);
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
      await this.doHello(identity);
    } catch (error) {
      this.close();
      throw error;
    }
    this.ready = true;
    const pending = this.buffered;
    this.buffered = [];
    for (const message of pending) this.dispatch(message);
  }

  private socketOptions(): ClientOptions {
    if (!this.url.startsWith("wss:")) return {};
    const tlsOptions: ConnectionOptions = { rejectUnauthorized: true };
    const pin = this.options.relayFingerprint;
    if (pin !== undefined && pin.trim() !== "") {
      const expected = normalizeFingerprint(pin);
      tlsOptions.checkServerIdentity = (_hostname, cert) => {
        const raw = cert.raw;
        const actual = createHash("sha256").update(raw).digest("hex");
        this.serverFingerprint = actual;
        if (actual !== expected) {
          return new Error(
            `relay certificate fingerprint mismatch: expected ${expected}, got ${actual}`,
          );
        }
        return undefined;
      };
    }
    return tlsOptions as unknown as ClientOptions;
  }

  private async doHello(identity: Identity): Promise<void> {
    const helloId = crypto.randomUUID();
    this.sendRaw({
      v: 1,
      type: "hello",
      from: identity.peerId,
      pub: toBase64Url(identity.publicKey),
      role: this.options.role,
      id: helloId,
    });
    const reply = await this.waitFor(
      (message) =>
        message.type === "hello_ok" ||
        message.peer_id !== undefined ||
        typeof message.challenge === "string" ||
        typeof message.nonce === "string" ||
        message.type === "error",
      15_000,
      "relay hello",
    );
    if (reply.type === "error") throw this.envelopeError(reply);

    const challengeText = reply.challenge ?? reply.nonce;
    if (typeof challengeText === "string") {
      const challenge = fromBase64Url(challengeText);
      if (challenge.length < 16) {
        throw new PhoneBridgeError("relay_error", "relay sent a short hello challenge");
      }
      // One-pass DH proof: the relay sends its ephemeral challenge point C = c*G. The peer answers
      // X25519(peer secret, C); the relay checks X25519(c, peer pub) equals the same value.
      const proof = await x25519(identity.secretKey, challenge);
      const authId = crypto.randomUUID();
      this.sendRaw({
        v: 1,
        type: "hello_proof",
        from: identity.peerId,
        proof: toBase64Url(proof),
        id: authId,
      });
      const ack = await this.waitFor(
        (message) => message.type === "hello_ok" || message.type === "error",
        15_000,
        "relay hello proof",
      );
      if (ack.type === "error") throw this.envelopeError(ack);
      if (typeof ack.peer_id === "string" && ack.peer_id !== identity.peerId) {
        throw new PhoneBridgeError("relay_error", "relay assigned a different peer_id", {
          expected: identity.peerId,
          actual: ack.peer_id,
        });
      }
      return;
    }

    if (typeof reply.peer_id === "string" && reply.peer_id !== identity.peerId) {
      throw new PhoneBridgeError("relay_error", "relay assigned a different peer_id", {
        expected: identity.peerId,
        actual: reply.peer_id,
      });
    }
  }

  /** Sends an encrypted payload to `to`; returns the relay envelope id for correlation. */
  sendRoute(to: string, payload: Uint8Array): string {
    const id = crypto.randomUUID();
    this.sendRaw({
      v: 1,
      type: "route",
      from: this.options.identity.peerId,
      to,
      id,
      payload: toBase64Url(payload),
    });
    return id;
  }

  /** Short-code pairing: asks the relay for the pending offer. Returns the offer JSON text. */
  async lookupPairing(code: string, timeoutMs = 10_000): Promise<string> {
    const id = crypto.randomUUID();
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
        (message.id === undefined || message.id === id || message.code !== undefined),
      timeoutMs,
      "pair_lookup",
    );
    if (reply.type === "error") throw this.envelopeError(reply);
    if (typeof reply.payload !== "string") {
      throw new PhoneBridgeError("relay_error", "relay pair_offer reply has no payload");
    }
    return fromUtf8(fromBase64Url(reply.payload));
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.ready = false;
    this.rejectWaiters(new PhoneBridgeError("relay_error", "relay connection closed"));
    this.socket?.close();
    this.socket = undefined;
  }

  private sendRaw(message: Envelope): void {
    const socket = this.socket;
    if (socket === undefined || socket.readyState !== WebSocket.OPEN) {
      throw new PhoneBridgeError("relay_error", "relay socket is not open");
    }
    socket.send(JSON.stringify(message));
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
        this.sendRaw({ v: 1, type: "pong", id: message.id });
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
      case "error":
        for (const handler of this.errorHandlers) handler(message);
        break;
      case "relay_moved":
        for (const handler of this.moveHandlers) handler(message);
        break;
      case "hello_ok":
      case "hello_challenge":
      case "hello_proof":
        break;
      default:
        for (const handler of this.envelopeHandlers) handler(message);
    }
  }

  private dispatchError(error: PhoneBridgeError): void {
    for (const handler of this.errorHandlers) {
      handler({ v: 1, type: "error", code: error.code, message: error.message });
    }
  }

  private handleClose(code: number, reason: string): void {
    this.ready = false;
    this.closed = true;
    this.rejectWaiters(new PhoneBridgeError("peer_offline", "relay connection closed", { code, reason }));
    for (const handler of this.closeHandlers) handler({ code, reason });
  }

  private envelopeError(message: Envelope): PhoneBridgeError {
    const code = message.code ?? "relay_error";
    const error = remoteError({ code, message: message.message ?? `relay error: ${code}` });
    return new PhoneBridgeError(
      code === "peer_offline" ? "peer_offline" : error.code,
      error.message,
      { relayCode: code },
    );
  }

  private waitFor(
    match: (message: Envelope) => boolean,
    timeoutMs: number,
    label: string,
  ): Promise<Envelope> {
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

/** Normalizes `sha256:AB:CD:…` / hex / base64 fingerprints to lowercase hex. */
export function normalizeFingerprint(value: string): string {
  let text = value.trim().toLowerCase();
  if (text.startsWith("sha256:")) text = text.slice("sha256:".length);
  text = text.replace(/[\s:]/g, "");
  if (/^[0-9a-f]{64}$/.test(text)) return text;
  const decoded = fromBase64Url(value.trim());
  if (decoded.length === 32) {
    return Buffer.from(decoded).toString("hex");
  }
  throw new PhoneBridgeError("bad_input", "relay fingerprint must be a SHA-256 hash");
}
