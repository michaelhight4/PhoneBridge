/**
 * In-process relay for tests, implementing the relay half of docs/PROTOCOL.md and
 * relay/PROTOCOL-RELAY.md on 127.0.0.1: challenge -> hello (dh-hmac-sha256) -> hello_ok, verbatim
 * `route` forwarding, the short-code mailbox and relay_moved.
 */
import { randomBytes, randomUUID } from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";
import { fromBase64Loose, toBase64Url } from "../../src/client/bytes.js";
import { generateKeyPair, fingerprintForPeerId, peerIdForPublicKey, type KeyPair } from "../../src/client/crypto.js";
import { verifyHelloProof } from "../../src/client/helloAuth.js";
import type { Envelope, RelayBotMessage, RelayPeerEntry } from "../../src/client/relay.js";

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

interface DevicePeers {
  deviceId: string;
  deviceName: string | undefined;
  bots: RelayPeerEntry[];
}

interface ConnectionState {
  nonce: Uint8Array;
  keyPair: KeyPair;
  relayId: string;
  peerId: string | undefined;
  role: string;
  authed: boolean;
}

export class MockRelay {
  private server: WebSocketServer | undefined;
  private peers = new Map<string, Peer>();
  private offers = new Map<string, OfferEntry>();
  private connections = new Map<WebSocket, ConnectionState>();
  private peerWaiters: Array<{ predicate: (peer: Peer) => boolean; resolve: (peer: Peer) => void }> = [];
  private devices = new Map<string, DevicePeers>();
  private mailboxes = new Map<string, RelayBotMessage[]>();

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

  /** Device-side `peers_sync`: publishes the bots a phone allows to message each other. */
  syncPeers(deviceId: string, peers: RelayPeerEntry[], deviceName?: string): void {
    this.devices.set(deviceId, {
      deviceId,
      deviceName,
      bots: peers
        .filter((entry) => entry.peer_id.trim() !== "" && entry.public_key.trim() !== "")
        .map((entry) => ({
          ...entry,
          fingerprint: entry.fingerprint ?? fingerprintForPeerId(entry.peer_id),
          device_id: deviceId,
          device_name: deviceName,
          online: undefined,
        }))
        .filter((entry, index, all) => all.findIndex((other) => other.peer_id === entry.peer_id) === index),
    });
  }

  /** Remaining queued messages for a bot, for assertions about store-and-forward. */
  mailbox(botId: string): RelayBotMessage[] {
    return [...(this.mailboxes.get(botId) ?? [])];
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
    this.devices.clear();
    this.mailboxes.clear();
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
      role: "bot",
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
      case "peers_sync": {
        if (state.role !== "device") {
          this.sendError(socket, "unauthorized", "only devices may sync bot peers", state.peerId, message.id);
          return;
        }
        const count = message.peers === undefined ? 0 : this.syncPeersFromEnvelope(state, message);
        this.send(socket, { type: "peers_synced", to: state.peerId, count, id: message.id ?? randomUUID() });
        return;
      }
      case "peers_list": {
        const allowed = this.allowedPeers(state.peerId ?? "", message.device_id);
        if (allowed === undefined) {
          this.sendError(
            socket,
            "permission_denied",
            `bot ${state.peerId ?? ""} is not paired with messaging enabled`,
            state.peerId,
            message.id,
          );
          return;
        }
        this.send(socket, {
          type: "peers_result",
          to: state.peerId,
          device_id: message.device_id,
          peers: allowed,
          count: allowed.length,
          id: message.id ?? randomUUID(),
        });
        return;
      }
      case "msg_send": {
        this.handleMsgSend(socket, state, message);
        return;
      }
      case "msg_inbox": {
        this.handleMsgInbox(socket, state, message);
        return;
      }
      case "msg_ack": {
        const acked = this.ackMessages(state.peerId ?? "", message.ids);
        this.send(socket, {
          type: "msg_acked",
          to: state.peerId,
          ack_ids: acked,
          count: acked.length,
          id: message.id ?? randomUUID(),
        });
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

  private send(socket: WebSocket, message: Record<string, unknown>): void {
    socket.send(JSON.stringify({ v: 1, ...message }));
  }

  private sendError(
    socket: WebSocket,
    code: string,
    text: string,
    to?: string,
    id?: string,
  ): void {
    this.send(socket, { type: "error", to, id, error: { code, message: text } });
  }

  private syncPeersFromEnvelope(state: ConnectionState, message: Envelope): number {
    const peers = Array.isArray(message.peers) ? message.peers : [];
    const deviceName = typeof message.device_name === "string" ? message.device_name : undefined;
    this.syncPeers(state.peerId ?? "", peers, deviceName);
    return this.devices.get(state.peerId ?? "")?.bots.length ?? 0;
  }

  private canMessage(entry: RelayPeerEntry, botId: string): boolean {
    return entry.peer_id === botId && entry.enabled !== false && entry.can_message !== false;
  }

  /** Mirrors `BotMailbox.listPeersForBot`: denied unless the asking bot is itself paired with ACL. */
  private allowedPeers(botId: string, deviceId?: string): RelayPeerEntry[] | undefined {
    const devices = [...this.devices.values()].filter(
      (device) =>
        (deviceId === undefined || device.deviceId === deviceId) &&
        device.bots.some((entry) => this.canMessage(entry, botId)),
    );
    if (devices.length === 0) return undefined;
    const seen = new Set<string>();
    const peers: RelayPeerEntry[] = [];
    for (const device of devices) {
      for (const entry of device.bots) {
        if (entry.peer_id === botId || entry.enabled === false || seen.has(entry.peer_id)) continue;
        seen.add(entry.peer_id);
        peers.push({
          ...entry,
          device_id: device.deviceId,
          device_name: device.deviceName,
          online: this.peers.has(entry.peer_id),
        });
      }
    }
    return peers;
  }

  private authorizeSend(fromBotId: string, toBotId: string, deviceId?: string): boolean {
    if (fromBotId === toBotId) return false;
    return [...this.devices.values()].some(
      (device) =>
        (deviceId === undefined || device.deviceId === deviceId) &&
        device.bots.some((entry) => this.canMessage(entry, fromBotId)) &&
        device.bots.some((entry) => this.canMessage(entry, toBotId)),
    );
  }

  private parseBotMessage(value: unknown): RelayBotMessage {
    if (typeof value !== "object" || value === null) throw new Error("msg_send needs a message");
    const record = value as Record<string, unknown>;
    const createdAt = record["created_at"];
    if (
      typeof record["id"] !== "string" ||
      typeof record["from"] !== "string" ||
      typeof record["to"] !== "string" ||
      typeof record["payload"] !== "string" ||
      typeof createdAt !== "number" ||
      !Number.isFinite(createdAt)
    ) {
      throw new Error("malformed bot message");
    }
    return {
      id: record["id"],
      from: record["from"],
      to: record["to"],
      created_at: createdAt,
      payload: record["payload"],
      ...(typeof record["from_name"] === "string" ? { from_name: record["from_name"] } : {}),
      ...(typeof record["device_id"] === "string" ? { device_id: record["device_id"] } : {}),
      ...(typeof record["expires_at"] === "number" ? { expires_at: record["expires_at"] } : {}),
    };
  }

  private handleMsgSend(socket: WebSocket, state: ConnectionState, message: Envelope): void {
    let incoming: RelayBotMessage;
    try {
      incoming = this.parseBotMessage(message.message);
    } catch {
      this.sendError(socket, "bad_args", "msg_send needs a well-formed message", state.peerId, message.id);
      return;
    }
    if (incoming.from !== state.peerId || (message.from !== undefined && message.from !== state.peerId)) {
      this.sendError(socket, "unauthorized", "msg_send must come from the authenticated bot", state.peerId, message.id);
      return;
    }
    let payloadBytes: number;
    try {
      payloadBytes = fromBase64Loose(incoming.payload).length;
    } catch {
      this.sendError(socket, "bad_args", "bot message payload is not base64url", state.peerId, message.id);
      return;
    }
    if (payloadBytes > 64 * 1024) {
      this.sendError(socket, "too_large", "bot message exceeds 65536 bytes", state.peerId, message.id);
      return;
    }
    if (!this.authorizeSend(state.peerId ?? "", incoming.to, message.device_id)) {
      this.sendError(socket, "permission_denied", "these bots may not message each other", state.peerId, message.id);
      return;
    }

    const now = Date.now();
    const stored: RelayBotMessage = {
      ...incoming,
      from: state.peerId ?? "",
      device_id: message.device_id ?? incoming.device_id,
      created_at: incoming.created_at > 0 ? incoming.created_at : now,
      expires_at: incoming.expires_at ?? now + 7 * 24 * 60 * 60 * 1000,
    };
    const box = this.mailboxes.get(stored.to) ?? [];
    box.push(stored);
    this.mailboxes.set(stored.to, box);
    this.send(socket, { type: "msg_sent", to: state.peerId, message: stored, id: message.id ?? randomUUID() });

    // The real relay pushes immediately to an online recipient but keeps a copy for reliability.
    const target = this.peers.get(stored.to);
    if (target !== undefined) {
      target.socket.send(
        JSON.stringify({
          v: 1,
          type: "msg_deliver",
          to: stored.to,
          message: stored,
          id: randomUUID(),
        }),
      );
    }
  }

  private purgeExpired(): void {
    const now = Date.now();
    for (const [botId, box] of this.mailboxes) {
      const kept = box.filter((entry) => (entry.expires_at ?? Number.POSITIVE_INFINITY) > now);
      if (kept.length === 0) this.mailboxes.delete(botId);
      else this.mailboxes.set(botId, kept);
    }
  }

  private handleMsgInbox(socket: WebSocket, state: ConnectionState, message: Envelope): void {
    this.purgeExpired();
    const botId = state.peerId ?? "";
    const box = this.mailboxes.get(botId) ?? [];
    const ackIds = Array.isArray(message.ack_ids) ? message.ack_ids : [];
    if (ackIds.length > 0) {
      const remove = new Set(ackIds.filter((id): id is string => typeof id === "string"));
      const remaining = box.filter((entry) => !remove.has(entry.id));
      if (remaining.length === 0) this.mailboxes.delete(botId);
      else this.mailboxes.set(botId, remaining);
      box.length = 0;
      box.push(...remaining);
    }
    const requested = typeof message.limit === "number" ? message.limit : 50;
    const limit = Math.max(1, Math.min(100, Math.trunc(requested)));
    const selected = [...box].sort((a, b) => a.created_at - b.created_at).slice(0, limit);
    if (message.consume === true && selected.length > 0) {
      const taken = new Set(selected.map((entry) => entry.id));
      const remaining = box.filter((entry) => !taken.has(entry.id));
      if (remaining.length === 0) this.mailboxes.delete(botId);
      else this.mailboxes.set(botId, remaining);
    }
    this.send(socket, {
      type: "msg_list",
      to: botId,
      messages: selected,
      count: selected.length,
      id: message.id ?? randomUUID(),
    });
  }

  private ackMessages(botId: string, ids: unknown): string[] {
    this.purgeExpired();
    const box = this.mailboxes.get(botId);
    if (box === undefined) return [];
    const pending = new Set(box.map((entry) => entry.id));
    const acked = (Array.isArray(ids) ? ids : [])
      .filter((id): id is string => typeof id === "string" && pending.has(id))
      .filter((id, index, all) => all.indexOf(id) === index);
    if (acked.length > 0) {
      const remove = new Set(acked);
      const remaining = box.filter((entry) => !remove.has(entry.id));
      if (remaining.length === 0) this.mailboxes.delete(botId);
      else this.mailboxes.set(botId, remaining);
    }
    return acked;
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
    state.role = typeof message.role === "string" ? message.role : "bot";
    const peer: Peer = {
      peerId: message.from,
      role: state.role,
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
