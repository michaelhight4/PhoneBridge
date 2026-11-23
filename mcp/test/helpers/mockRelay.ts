/**
 * In-process relay for tests: implements the relay half of docs/PROTOCOL.md on 127.0.0.1.
 *
 * - verifies the hello challenge/proof with the peer's advertised public key,
 * - forwards `route` envelopes between connected peers,
 * - answers `pair_lookup` from registered offers,
 * - can drop routes or go silent to exercise timeouts.
 */
import { WebSocketServer, WebSocket } from "ws";
import { fromBase64Url, toBase64Url, utf8 } from "../../src/client/bytes.js";
import { generateKeyPair, x25519 } from "../../src/client/crypto.js";

interface Peer {
  peerId: string;
  role: string;
  socket: WebSocket;
  publicKey: Uint8Array;
}

interface OfferEntry {
  code: string;
  json: string;
  expiresAt: number;
  taken: boolean;
}

interface RelayMessage {
  v?: number;
  type: string;
  from?: string;
  to?: string;
  id?: string;
  payload?: string;
  code?: string;
  pub?: string;
  proof?: string;
  role?: string;
  challenge?: string;
  peer_id?: string;
  message?: string;
  [key: string]: unknown;
}

export class MockRelay {
  private server: WebSocketServer | undefined;
  private peers = new Map<string, Peer>();
  private offers = new Map<string, OfferEntry>();
  private challenges = new Map<string, string>();
  private pendingSockets = new Map<WebSocket, string>();
  private peerWaiters: Array<{ predicate: (peer: Peer) => boolean; resolve: (peer: Peer) => void }> = [];

  /** When true every `route` envelope is dropped (peer looks connected but silent). */
  dropRoutes = false;
  /** Number of routed envelopes seen (tests assert that traffic actually flows). */
  routeCount = 0;
  port = 0;

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
    server.on("connection", (socket) => this.handleConnection(socket));
    return this.url();
  }

  url(): string {
    return `ws://127.0.0.1:${this.port}`;
  }

  registerOffer(code: string, offerJson: string, expiresAtMs = Date.now() + 10 * 60_000): void {
    this.offers.set(code, { code, json: offerJson, expiresAt: expiresAtMs, taken: false });
  }

  connectedPeers(): string[] {
    return [...this.peers.keys()];
  }

  peer(peerId: string): Peer | undefined {
    return this.peers.get(peerId);
  }

  async waitForPeer(
    predicate: (peer: Peer) => boolean = () => true,
    timeoutMs = 10_000,
  ): Promise<Peer> {
    for (const peer of this.peers.values()) {
      if (predicate(peer)) return peer;
    }
    return new Promise<Peer>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.peerWaiters = this.peerWaiters.filter((waiter) => waiter.resolve !== wrapped);
        reject(new Error("mock relay: timed out waiting for a peer"));
      }, timeoutMs);
      const wrapped = (peer: Peer): void => {
        clearTimeout(timer);
        resolve(peer);
      };
      this.peerWaiters.push({ predicate, resolve: wrapped });
    });
  }

  /** Sends an unsolicited relay message to one connected peer or all of them. */
  sendToPeer(peerId: string, message: RelayMessage): void {
    const peer = this.peers.get(peerId);
    if (peer !== undefined) peer.socket.send(JSON.stringify({ v: 1, ...message }));
  }

  broadcast(message: RelayMessage): void {
    for (const peer of this.peers.values()) peer.socket.send(JSON.stringify({ v: 1, ...message }));
  }

  /** Marks a peer as offline from the relay point of view without closing its socket. */
  forgetPeer(peerId: string): void {
    this.peers.delete(peerId);
  }

  async close(): Promise<void> {
    for (const peer of this.peers.values()) peer.socket.terminate();
    this.peers.clear();
    this.peerWaiters = [];
    const server = this.server;
    this.server = undefined;
    if (server === undefined) return;
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  private handleConnection(socket: WebSocket): void {
    socket.on("message", (data) => {
      void this.handleMessage(socket, data.toString());
    });
    socket.on("close", () => {
      const peerId = this.pendingSockets.get(socket);
      this.pendingSockets.delete(socket);
      if (peerId !== undefined && this.peers.get(peerId)?.socket === socket) {
        this.peers.delete(peerId);
          }
    });
    socket.on("error", () => {
      /* terminated sockets are expected in failure tests */
    });
  }

  private async handleMessage(socket: WebSocket, text: string): Promise<void> {
    let message: RelayMessage;
    try {
      message = JSON.parse(text) as RelayMessage;
    } catch {
      socket.send(JSON.stringify({ v: 1, type: "error", code: "bad_json", message: "invalid JSON" }));
      return;
    }

    switch (message.type) {
      case "hello": {
        if (typeof message.from !== "string" || typeof message.pub !== "string") {
          socket.send(JSON.stringify({ v: 1, type: "error", code: "bad_hello" }));
          return;
        }
        const challenge = await generateKeyPair();
        this.challenges.set(message.from, toBase64Url(challenge.secretKey));
        this.pendingSockets.set(socket, message.from);
        this.pendingPublicKeys.set(message.from, message.pub);
        this.pendingRoles.set(message.from, String(message.role ?? "bot"));
        socket.send(
          JSON.stringify({
            v: 1,
            type: "hello_challenge",
            challenge: toBase64Url(challenge.publicKey),
            id: message.id,
          }),
        );
        return;
      }
      case "hello_proof": {
        const peerId = message.from;
        if (typeof peerId !== "string" || typeof message.proof !== "string") {
          socket.send(JSON.stringify({ v: 1, type: "error", code: "bad_proof" }));
          return;
        }
        const challengeText = this.challenges.get(peerId);
        const pendingPeer = this.pendingSockets.get(socket);
        if (challengeText === undefined || pendingPeer !== peerId) {
          socket.send(JSON.stringify({ v: 1, type: "error", code: "unknown_peer" }));
          return;
        }
        const publicKey = fromBase64Url(this.pendingPublicKeys.get(peerId) ?? "");
        const challengeSecret = fromBase64Url(challengeText);
        const expected = await x25519(challengeSecret, publicKey);
        if (toBase64Url(expected) !== message.proof) {
          socket.send(JSON.stringify({ v: 1, type: "error", code: "bad_proof", id: message.id }));
          return;
        }
        this.challenges.delete(peerId);
        this.pendingSockets.delete(socket);
        const peer: Peer = {
          peerId,
          role: this.pendingRoles.get(peerId) ?? "bot",
          socket,
          publicKey,
        };
        this.peers.set(peerId, peer);
        socket.send(JSON.stringify({ v: 1, type: "hello_ok", peer_id: peerId, id: message.id }));
        for (const waiter of [...this.peerWaiters]) {
          if (!waiter.predicate(peer)) continue;
          this.peerWaiters = this.peerWaiters.filter((entry) => entry !== waiter);
          waiter.resolve(peer);
        }
        return;
      }
      case "route": {
        this.routeCount += 1;
        if (this.dropRoutes) return;
        if (typeof message.to !== "string" || typeof message.payload !== "string") {
          socket.send(JSON.stringify({ v: 1, type: "error", code: "bad_route", id: message.id }));
          return;
        }
        const target = this.peers.get(message.to);
        if (target === undefined) {
          socket.send(
            JSON.stringify({
              v: 1,
              type: "error",
              code: "peer_offline",
              message: "peer is not connected",
              id: message.id,
              to: message.to,
            }),
          );
          return;
        }
        target.socket.send(JSON.stringify(message));
        return;
      }
      case "pair_lookup": {
        const code = typeof message.code === "string" ? message.code.toUpperCase() : "";
        const offer = this.offers.get(code);
        if (offer === undefined || offer.expiresAt <= Date.now()) {
          socket.send(
            JSON.stringify({ v: 1, type: "error", code: "bad_code", id: message.id, message: "unknown pairing code" }),
          );
          return;
        }
        if (offer.taken) {
          socket.send(
            JSON.stringify({ v: 1, type: "error", code: "expired", id: message.id, message: "code already used" }),
          );
          return;
        }
        offer.taken = true;
        socket.send(
          JSON.stringify({
            v: 1,
            type: "pair_offer",
            id: message.id,
            payload: toBase64Url(utf8(offer.json)),
          }),
        );
        return;
      }
      default:
        socket.send(JSON.stringify({ v: 1, type: "error", code: "unknown_type", id: message.id }));
    }
  }

  private pendingPublicKeys = new Map<string, string>();
  private pendingRoles = new Map<string, string>();
}
