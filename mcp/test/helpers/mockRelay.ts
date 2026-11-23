/**
 * In-process relay for tests, implementing the relay half of docs/PROTOCOL.md and
 * relay/PROTOCOL-RELAY.md on 127.0.0.1: challenge -> hello (dh-hmac-sha256) -> hello_ok, verbatim
 * `route` forwarding, the short-code mailbox and relay_moved.
 */
import { randomBytes, randomUUID } from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";
import { fromBase64Loose, toBase64Url } from "../../src/client/bytes.js";
import { generateKeyPair, peerIdForPublicKey, type KeyPair } from "../../src/client/crypto.js";
import { verifyHelloProof } from "../../src/client/helloAuth.js";
import type { Envelope } from "../../src/client/relay.js";

interface Peer {
  peerId: string;
  role: string;
  socket: WebSocket;
  publicKey: Uint8Array;
}

interface OfferEntry {
  code: string;
  offer: Record<string, unknown>;
  expiresAt: number;
  taken: boolean;
}

interface ConnectionState {
  nonce: Uint8Array;
  keyPair: KeyPair;
  relayId: string;
  peerId: string | undefined;
  authed: boolean;
}

export class MockRelay {
  private server: WebSocketServer | undefined;
  private peers = new Map<string, Peer>();
  private offers = new Map<string, OfferEntry>();
  private connections = new Map<WebSocket, ConnectionState>();
  private peerWaiters: Array<{ predicate: (peer: Peer) => boolean; resolve: (peer: Peer) => void }> = [];

  dropRoutes = false;
  routeCount = 0;
  port = 0;
  /** Delay before route forwarding, to force out-of-order responses in tests. */
  routeDelayMs = 0;

  async listen(): Promise<string> {
    const server = new WebSocketServer({ host: "127.0.0.1", port: 0 });
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      server.once("listening", () => resolve());
      server.once("error", reject);
    });
    const address = server.address();
    if (typeof address === "string" || address === null) throw new Error("relay did not bind a port");
    this.port = address.port;
    server.on("connection", (socket) => {
      void this.beginConnection(socket);
    });
    return this.url();
  }

  url(): string {
    return `ws://127.0.0.1:${this.port}`;
  }

  registerOffer(code: string, offer: Record<string, unknown>, expiresAtMs = Date.now() + 10 * 60_000): void {
    this.offers.set(code.toUpperCase(), { code: code.toUpperCase(), offer, expiresAt: expiresAtMs, taken: false });
  }

  connectedPeers(): string[] {
    return [...this.peers.keys()];
  }

  peer(peerId: string): Peer | undefined {
    return this.peers.get(peerId);
  }

  async waitForPeer(predicate: (peer: Peer) => boolean = () => true, timeoutMs = 10_000): Promise<Peer> {
    for (const peer of this.peers.values()) {
      if (predicate(peer)) return peer;
    }
    return new Promise<Peer>((resolve, reject) => {
      let wrapped: (peer: Peer) => void = () => undefined;
      const timer = setTimeout(() => {
        this.peerWaiters = this.peerWaiters.filter((waiter) => waiter.resolve !== wrapped);
        reject(new Error("mock relay: timed out waiting for a peer"));
      }, timeoutMs);
      wrapped = (peer: Peer): void => {
        clearTimeout(timer);
        resolve(peer);
      };
      this.peerWaiters.push({ predicate, resolve: wrapped });
    });
  }

  sendToPeer(peerId: string, message: Record<string, unknown>): void {
    const peer = this.peers.get(peerId);
    if (peer !== undefined) peer.socket.send(JSON.stringify({ v: 1, ...message }));
  }

  broadcast(message: Record<string, unknown>): void {
    for (const peer of this.peers.values()) peer.socket.send(JSON.stringify({ v: 1, ...message }));
  }

  /** Marks a peer as offline from the relay's point of view without closing its socket. */
  forgetPeer(peerId: string): void {
    this.peers.delete(peerId);
  }

  async close(): Promise<void> {
    const server = this.server;
    this.server = undefined;
    for (const client of server?.clients ?? []) client.terminate();
    for (const peer of this.peers.values()) peer.socket.terminate();
    this.peers.clear();
    this.peerWaiters = [];
    this.connections.clear();
    if (server === undefined) return;
    await new Promise<void>((resolve) => {
      let settled = false;
      const done = (): void => {
        if (settled) return;
        settled = true;
        resolve();
      };
      server.close(done);
      // A socket that is mid-handshake can delay the close callback; tests must not hang on it.
      setTimeout(done, 500).unref?.();
    });
  }

  private async beginConnection(socket: WebSocket): Promise<void> {
    const keyPair = await generateKeyPair();
    const nonce = new Uint8Array(randomBytes(32));
    const state: ConnectionState = {
      nonce,
      keyPair,
      relayId: peerIdForPublicKey(keyPair.publicKey),
      peerId: undefined,
      authed: false,
    };
    this.connections.set(socket, state);
    socket.send(
      JSON.stringify({
        v: 1,
        type: "challenge",
        nonce: toBase64Url(nonce),
        relay_pub: toBase64Url(keyPair.publicKey),
        relay_id: state.relayId,
        proof: "dh-hmac-sha256",
        expires: Math.floor(Date.now() / 1000) + 30,
      }),
    );
    socket.on("message", (data) => {
      void this.handleMessage(socket, data.toString());
    });
    socket.on("close", () => {
      this.connections.delete(socket);
      if (state.peerId !== undefined && this.peers.get(state.peerId)?.socket === socket) {
        this.peers.delete(state.peerId);
      }
    });
    socket.on("error", () => {
      /* terminated sockets are expected in failure tests */
    });
  }

  private async handleMessage(socket: WebSocket, text: string): Promise<void> {
    let message: Envelope;
    try {
      message = JSON.parse(text) as Envelope;
    } catch {
      socket.send(JSON.stringify({ v: 1, type: "error", error: { code: "bad_envelope", message: "invalid JSON" } }));
      return;
    }
    const state = this.connections.get(socket);
    if (state === undefined) return;

    if (message.type === "hello") {
      await this.handleHello(socket, state, message);
      return;
    }
    if (!state.authed) {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "send hello first" } }),
      );
      return;
    }

    switch (message.type) {
      case "route": {
        this.routeCount += 1;
        if (this.dropRoutes) return;
        if (typeof message.to !== "string" || typeof message.payload !== "string") {
          socket.send(
            JSON.stringify({ v: 1, type: "error", id: message.id, error: { code: "bad_envelope", message: "route" } }),
          );
          return;
        }
        const target = this.peers.get(message.to);
        if (target === undefined) {
          socket.send(
            JSON.stringify({
              v: 1,
              type: "error",
              to: state.peerId,
              id: message.id,
              error: { code: "peer_offline", message: "peer is not connected" },
            }),
          );
          return;
        }
        const forward = (): void => {
          if (target.socket.readyState === WebSocket.OPEN) target.socket.send(JSON.stringify(message));
        };
        if (this.routeDelayMs > 0) setTimeout(forward, this.routeDelayMs);
        else forward();
        return;
      }
      case "pair_store": {
        if (typeof message.code !== "string" || typeof message.offer !== "object" || message.offer === null) {
          socket.send(
            JSON.stringify({ v: 1, type: "error", error: { code: "bad_args", message: "pair_store" } }),
          );
          return;
        }
        const code = message.code.toUpperCase();
        const expiresAt = Date.now() + 10 * 60_000;
        this.offers.set(code, { code, offer: message.offer, expiresAt, taken: false });
        socket.send(
          JSON.stringify({ v: 1, type: "pair_stored", to: state.peerId, code, expires: Math.floor(expiresAt / 1000) }),
        );
        return;
      }
      case "pair_lookup": {
        const code = typeof message.code === "string" ? message.code.toUpperCase() : "";
        const offer = this.offers.get(code);
        if (offer === undefined) {
          socket.send(
            JSON.stringify({
              v: 1,
              type: "error",
              to: state.peerId,
              id: message.id,
              error: { code: "code_not_found", message: "unknown pairing code" },
            }),
          );
          return;
        }
        if (offer.expiresAt <= Date.now()) {
          this.offers.delete(code);
          socket.send(
            JSON.stringify({
              v: 1,
              type: "error",
              to: state.peerId,
              id: message.id,
              error: { code: "code_expired", message: "pairing code expired" },
            }),
          );
          return;
        }
        if (offer.taken) {
          socket.send(
            JSON.stringify({
              v: 1,
              type: "error",
              to: state.peerId,
              id: message.id,
              error: { code: "code_expired", message: "pairing code already used" },
            }),
          );
          return;
        }
        offer.taken = true;
        socket.send(
          JSON.stringify({
            v: 1,
            type: "pair_offer",
            to: state.peerId,
            id: message.id,
            code,
            offer: offer.offer,
            expires: Math.floor(offer.expiresAt / 1000),
          }),
        );
        return;
      }
      case "ping":
        socket.send(JSON.stringify({ v: 1, type: "pong", from: state.peerId, to: message.from, id: message.id }));
        return;
      default:
        // Unknown types are ignored, never fatal.
        return;
    }
  }

  private async handleHello(socket: WebSocket, state: ConnectionState, message: Envelope): Promise<void> {
    if (typeof message.from !== "string" || typeof message.pub !== "string" || typeof message.sig !== "string") {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "hello needs from/pub/sig" } }),
      );
      return;
    }
    let publicKey: Uint8Array;
    let nonce: Uint8Array;
    let proof: Uint8Array;
    try {
      publicKey = fromBase64Loose(message.pub);
      nonce = fromBase64Loose(message.nonce ?? "");
      proof = fromBase64Loose(message.sig);
    } catch {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "malformed hello" } }),
      );
      return;
    }
    if (publicKey.length !== 32 || proof.length !== 32 || nonce.length !== 32) {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "bad key sizes" } }),
      );
      return;
    }
    if (!Buffer.from(nonce).equals(Buffer.from(state.nonce))) {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "nonce mismatch" } }),
      );
      return;
    }
    if (peerIdForPublicKey(publicKey) !== message.from) {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "peer_id mismatch" } }),
      );
      return;
    }
    const valid = await verifyHelloProof(
      state.keyPair.secretKey,
      state.keyPair.publicKey,
      state.relayId,
      message.from,
      publicKey,
      nonce,
      proof,
    );
    if (!valid) {
      socket.send(
        JSON.stringify({ v: 1, type: "error", error: { code: "unauthorized", message: "bad proof" } }),
      );
      return;
    }
    state.authed = true;
    state.peerId = message.from;
    const peer: Peer = {
      peerId: message.from,
      role: typeof message.role === "string" ? message.role : "bot",
      socket,
      publicKey,
    };
    this.peers.set(peer.peerId, peer);
    socket.send(
      JSON.stringify({
        v: 1,
        type: "hello_ok",
        to: peer.peerId,
        relay_id: state.relayId,
        peer: peer.peerId,
        server_time: Math.floor(Date.now() / 1000),
        limits: { max_envelope_bytes: 16 * 1024 * 1024, rate_messages: 240, rate_window_ms: 1000 },
      }),
    );
    for (const waiter of [...this.peerWaiters]) {
      if (!waiter.predicate(peer)) continue;
      this.peerWaiters = this.peerWaiters.filter((entry) => entry !== waiter);
      waiter.resolve(peer);
    }
  }
}

/** Convenience: a fresh UUID for tests that need one. */
export function nextId(): string {
  return randomUUID();
}
