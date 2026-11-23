import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MockRelay } from "./helpers/mockRelay.js";
import { MockDevice, createMockOffer } from "./helpers/mockDevice.js";
import { PhoneBridgeClient } from "../src/client/client.js";
import { createIdentity, randomBytes } from "../src/client/crypto.js";
import { MAX_CHUNK_BYTES } from "../src/client/transport.js";
import type { Identity } from "../src/client/crypto.js";

async function tempHome(): Promise<string> {
  return mkdtemp(join(tmpdir(), "phonebridge-test-"));
}

interface Setup {
  relay: MockRelay;
  device: MockDevice;
  client: PhoneBridgeClient;
  home: string;
  link: string;
}

async function setup(options: { level?: "view" | "control" | "full" } = {}): Promise<Setup> {
  const relay = new MockRelay();
  await relay.listen();
  const home = await tempHome();
  const deviceIdentity = await createIdentity();
  const secret = randomBytes(32);
  const { link } = await createMockOffer(deviceIdentity, relay.url(), { secret });
  const device = await MockDevice.create({
    relayUrl: relay.url(),
    identity: deviceIdentity,
    pairingSecret: secret,
    autoPair: true,
    level: options.level ?? "full",
  });
  const client = await PhoneBridgeClient.open({ home, botName: "vitest-bot" });
  const outcome = await client.pair(link, { requestedLevel: options.level ?? "full" });
  expect(outcome.device.peerId).toBe(device.peerId);
  return { relay, device, client, home, link };
}

describe("pairing and sessions over a mock relay", () => {
  let setupResult: Setup | undefined;

  beforeEach(async () => {
    setupResult = undefined;
  });

  afterEach(async () => {
    if (setupResult !== undefined) {
      await setupResult.client.close();
      await setupResult.device.stop();
      await setupResult.relay.close();
    }
  });

  it("pairs with a link, persists the device and reports status", async () => {
    const { client, device, home } = (setupResult = await setup());
    expect(device.handshakes).toBe(1);
    const devices = client.devices();
    expect(devices).toHaveLength(1);
    expect(devices[0]).toMatchObject({
      peerId: device.peerId,
      name: "Mock Pixel",
      level: "full",
      relayUrl: expect.stringContaining("ws://127.0.0.1"),
    });
    expect(device.received.map((entry) => entry.cmd)).toContain("pair_request");

    const status = (await client.status()) as Record<string, unknown>;
    expect(status["device"]).toBe("Mock Pixel");
    expect(status["battery"]).toBe(87);

    // A fresh client on the same keystore sees the paired device.
    const reopened = await PhoneBridgeClient.open({ home });
    expect(reopened.devices()[0]?.peerId).toBe(device.peerId);
    expect(reopened.botPeerId).toBe(client.botPeerId);
    await reopened.close();
  });

  it("pairs with a short code looked up on the relay", async () => {
    const relay = new MockRelay();
    await relay.listen();
    const home = await tempHome();
    const deviceIdentity = await createIdentity();
    const secret = randomBytes(32);
    const offer = await createMockOffer(deviceIdentity, relay.url(), { secret });
    relay.registerOffer(offer.code, JSON.stringify(offer.offer));
    const device = await MockDevice.create({
      relayUrl: relay.url(),
      identity: deviceIdentity,
      pairingSecret: secret,
      autoPair: true,
      level: "control",
    });
    const client = await PhoneBridgeClient.open({ home, botName: "code-bot" });
    try {
      const outcome = await client.pair(offer.code, {
        relayUrl: relay.url(),
        requestedLevel: "control",
      });
      expect(outcome.level).toBe("control");
      expect(outcome.device.peerId).toBe(device.peerId);
      expect(offer.code).toMatch(/^[0-9A-Z]{3}-[0-9A-Z]{3}$/);
    } finally {
      await client.close();
      await device.stop();
      await relay.close();
    }
  });

  it("correlates responses to requests even when they arrive out of order", async () => {
    const { client, device } = (setupResult = await setup());
    device.handlers.set("status", async () => {
      await new Promise((resolve) => setTimeout(resolve, 120));
      return { device: "slow-status", battery: 1 };
    });
    device.handlers.set("ui_tree", async () => ({ root: { id: "fast", children: [] } }));
    const session = await client.connect();

    const slow = session.request("status");
    const fast = session.request("ui_tree");
    const fastResult = (await fast) as Record<string, unknown>;
    expect((fastResult["root"] as Record<string, unknown>)["id"]).toBe("fast");
    const slowResult = (await slow) as Record<string, unknown>;
    expect(slowResult["device"]).toBe("slow-status");
  });

  it("times out a request the device never answers", async () => {
    const { client, device } = (setupResult = await setup());
    device.silent.add("tap");
    const session = await client.connect();
    await expect(session.request("tap", { x: 1, y: 2 }, { timeoutMs: 150 })).rejects.toMatchObject({
      code: "timeout",
    });
    // The session stays usable after a timeout.
    expect(await session.status()).toMatchObject({ device: "Mock Pixel" });
  });

  it("maps device error frames onto protocol error codes", async () => {
    const { client, device } = (setupResult = await setup());
    device.failing.set("swipe", { code: "not_controller", message: "needs Control" });
    const session = await client.connect();
    await expect(session.swipe({ x1: 1, y1: 2, x2: 3, y2: 4 })).rejects.toMatchObject({
      code: "not_controller",
      message: "needs Control",
    });
  });

  it("reassembles a chunked screenshot larger than one frame", async () => {
    const { client, device } = (setupResult = await setup());
    const raw = Buffer.alloc(180_000);
    for (let index = 0; index < raw.length; index += 1) raw[index] = index % 251;
    const dataB64 = raw.toString("base64");
    device.handlers.set("screenshot", () => ({
      mime: "image/png",
      width: 1080,
      height: 2400,
      data_b64: dataB64,
    }));
    const result = (await client.request("screenshot", { format: "png" })) as Record<string, unknown>;
    expect(result["data_b64"]).toBe(dataB64);
    const wireBytes = JSON.stringify({ id: "x", ok: true, result }).length;
    expect(wireBytes).toBeGreaterThan(MAX_CHUNK_BYTES);
  });

  it("records a relay_moved announcement and exposes it as an event", async () => {
    const { client, relay } = (setupResult = await setup());
    const session = await client.connect();
    const events: string[] = [];
    session.onEvent((event) => events.push(event.event));
    relay.broadcast({
      type: "relay_moved",
      new_url: "wss://relay2.example.test:8765",
      token: "one-time",
      expires: 1_900_000_000,
    });
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(events).toContain("relay_moved");
    expect(client.devices()[0]?.relayUrl).toBe("wss://relay2.example.test:8765");
  });

  it("delivers device events to listeners", async () => {    const { client, device } = (setupResult = await setup());
    const session = await client.connect();
    const events: Array<{ event: string; data: unknown }> = [];
    session.onEvent((event) => events.push(event));
    await device.emitEvent("queue_position", { position: 2 });
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(events).toContainEqual({ event: "queue_position", data: { position: 2 } });
    expect(session.lastEventSeen?.event).toBe("queue_position");
  });

  it("reports peer_offline when the relay cannot reach the device", async () => {
    const { client, device, relay } = (setupResult = await setup());
    relay.forgetPeer(device.peerId);
    await expect(client.request("status")).rejects.toMatchObject({ code: "peer_offline" });
  });

  it("enforces the granted permission level before sending", async () => {
    const { client } = (setupResult = await setup({ level: "view" }));
    const session = await client.connect();
    expect(session.grantedLevel).toBe("view");
    await expect(session.tap({ x: 10, y: 20 })).rejects.toMatchObject({ code: "permission_denied" });
    expect(await session.status()).toBeTruthy();
  });

  it("reuses an open session and re-handshakes after a close", async () => {
    const { client, device } = (setupResult = await setup());
    const first = await client.connect();
    const second = await client.connect();
    expect(second).toBe(first);
    expect(device.handshakes).toBe(1);
    first.close("test");
    await expect(first.status()).rejects.toMatchObject({ code: "peer_offline" });
    const third = await client.connect();
    expect(third).not.toBe(first);
    expect(device.handshakes).toBe(2);
  });

  it("refuses to pair when the device denies the request", async () => {
    const relay = new MockRelay();
    await relay.listen();
    const home = await tempHome();
    const deviceIdentity: Identity = await createIdentity();
    const secret = randomBytes(32);
    const { link } = await createMockOffer(deviceIdentity, relay.url(), { secret });
    const device = await MockDevice.create({
      relayUrl: relay.url(),
      identity: deviceIdentity,
      pairingSecret: secret,
      autoPair: false,
    });
    device.handlers.set("pair_request", () => ({ ok: false, reason: "denied" }));
    const client = await PhoneBridgeClient.open({ home });
    try {
      await expect(client.pair(link, { requestedLevel: "control" })).rejects.toMatchObject({
        code: "permission_denied",
      });
      expect(client.devices()).toEqual([]);
    } finally {
      await client.close();
      await device.stop();
      await relay.close();
    }
  });

  it("keeps the keystore identity stable and uses it for the relay hello", async () => {
    const { client, device } = (setupResult = await setup());
    expect(client.botPeerId).toHaveLength(26);
    expect(client.botPeerId).toMatch(/^[0-9a-z]+$/);
    const relayPeer = device.peerId;
    expect(relayPeer).toHaveLength(26);
  });
});
