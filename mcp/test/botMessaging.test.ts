import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it, vi } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import {
  aeadOpen,
  createIdentity,
  fingerprintForPeerId,
  hkdf,
  identityFromSecret,
  keyPairFromSecret,
  sha256,
  x25519,
  type Identity,
} from "../src/client/crypto.js";
import { concatBytes, fromUtf8, toBase64Url, utf8 } from "../src/client/bytes.js";
import { openBotMessage, sealBotMessage } from "../src/client/botMessageCrypto.js";
import { InboxStateStore, MAX_RECENT_INBOX_MESSAGES, inboxStatePath } from "../src/client/inboxState.js";
import { PhoneBridgeClient } from "../src/client/client.js";
import { RelayConnection, type RelayPeerEntry } from "../src/client/relay.js";
import { runCli, type CliIo } from "../src/cli/index.js";
import { createPhoneBridgeServer } from "../src/server/server.js";
import { MockRelay } from "./helpers/mockRelay.js";

function capture(): { io: CliIo; stdout: string[]; stderr: string[] } {
  const stdout: string[] = [];
  const stderr: string[] = [];
  return {
    io: { out: (text) => stdout.push(text), err: (text) => stderr.push(text) },
    stdout,
    stderr,
  };
}

async function makeBot(relayUrl: string, device: Identity, name: string): Promise<PhoneBridgeClient> {
  const home = await mkdtemp(join(tmpdir(), "pb-bot-msg-"));
  const client = await PhoneBridgeClient.open({ home, botName: name });
  await client.keystore.upsertDevice({
    peerId: device.peerId,
    name: "Test phone",
    publicKey: toBase64Url(device.publicKey),
    relayUrl,
    level: "control",
    pairedAt: new Date().toISOString(),
  });
  await client.keystore.save();
  return client;
}

async function peerEntry(client: PhoneBridgeClient, name: string): Promise<RelayPeerEntry> {
  const identity = await client.keystore.identity();
  return {
    peer_id: client.botPeerId,
    name,
    public_key: toBase64Url(identity.publicKey),
    fingerprint: fingerprintForPeerId(client.botPeerId),
    enabled: true,
    can_message: true,
  };
}

interface Fixture {
  relay: MockRelay;
  device: Identity;
  alice: PhoneBridgeClient;
  bob: PhoneBridgeClient;
  entries: RelayPeerEntry[];
}

async function botFixture(): Promise<Fixture> {
  const relay = new MockRelay();
  await relay.listen();
  const device = await createIdentity();
  const alice = await makeBot(relay.url(), device, "Alice");
  const bob = await makeBot(relay.url(), device, "Bob");
  const entries = [await peerEntry(alice, "Alice"), await peerEntry(bob, "Bob")];
  relay.syncPeers(device.peerId, entries, "Test phone");
  return { relay, device, alice, bob, entries };
}

async function closeFixture(fixture: Fixture): Promise<void> {
  await fixture.alice.close();
  await fixture.bob.close();
  await fixture.relay.close();
}

async function connectMcp(client: PhoneBridgeClient): Promise<Client> {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const mcp = new Client({ name: "vitest-bot-msg", version: "0.0.0" }, { capabilities: {} });
  await Promise.all([
    mcp.connect(clientTransport),
    createPhoneBridgeServer({ client }).connect(serverTransport),
  ]);
  return mcp;
}

function toolPayload(result: Awaited<ReturnType<Client["callTool"]>>): Record<string, unknown> {
  const content = result.content as Array<{ type: string; text?: string }>;
  const first = content[0];
  if (first === undefined || first.type !== "text" || first.text === undefined) {
    throw new Error("expected a text tool result");
  }
  return JSON.parse(first.text) as Record<string, unknown>;
}

describe("bot message frames (:core BotMessageCrypto)", () => {
  it("matches the Kotlin frame layout, HKDF inputs, AAD and plaintext JSON", async () => {
    const sender = await identityFromSecret(sha256(utf8("bot-msg-test/sender")));
    const recipient = await identityFromSecret(sha256(utf8("bot-msg-test/recipient")));
    const from = sender.peerId;
    const to = recipient.peerId;
    const id = "msg-1";
    let calls = 0;
    const random = (length: number): Uint8Array => {
      calls += 1;
      return new Uint8Array(length).fill(calls);
    };

    const frame = await sealBotMessage(sender, recipient.publicKey, from, to, id, "hello", "parent", random);
    expect(calls).toBe(2);
    expect(frame[0]).toBe(0x01);
    const ephemeral = await keyPairFromSecret(new Uint8Array(32).fill(1));
    expect(frame.subarray(1, 33)).toEqual(ephemeral.publicKey);
    expect(frame.subarray(33, 65)).toEqual(sender.publicKey);
    expect(frame.subarray(65, 77)).toEqual(new Uint8Array(12).fill(2));

    const expectedKey = hkdf(
      concatBytes(
        await x25519(sender.secretKey, recipient.publicKey),
        await x25519(ephemeral.secretKey, recipient.publicKey),
      ),
      utf8("PhoneBridge/1/bot-msg"),
      utf8("aead-key"),
      32,
    );
    const decoded = await aeadOpen(
      expectedKey,
      frame.subarray(65, 77),
      utf8(`PhoneBridge/1/bot-msg|v1|${from}|${to}|${id}`),
      frame.subarray(77),
    );
    expect(JSON.parse(fromUtf8(decoded))).toEqual({ v: 1, text: "hello", reply_to: "parent" });
    expect(await openBotMessage(recipient, from, to, id, frame)).toEqual({ v: 1, text: "hello", replyTo: "parent" });
  });

  it("rejects tampered ids, wrong sender keys, short frames and unknown versions", async () => {
    const sender = await identityFromSecret(sha256(utf8("bot-msg-test/sender-2")));
    const recipient = await identityFromSecret(sha256(utf8("bot-msg-test/recipient-2")));
    const frame = await sealBotMessage(sender, recipient.publicKey, sender.peerId, recipient.peerId, "m2", "secret");
    await expect(openBotMessage(recipient, sender.peerId, recipient.peerId, "m3", frame)).rejects.toMatchObject({
      code: "handshake_failed",
    });
    const wrongKey = await createIdentity();
    await expect(
      openBotMessage(recipient, sender.peerId, recipient.peerId, "m2", frame, wrongKey.publicKey),
    ).rejects.toMatchObject({ code: "bad_input" });
    await expect(openBotMessage(recipient, sender.peerId, recipient.peerId, "m2", frame.subarray(0, 20))).rejects.toMatchObject({
      code: "bad_input",
    });
    const wrongVersion = new Uint8Array(frame);
    wrongVersion[0] = 2;
    await expect(openBotMessage(recipient, sender.peerId, recipient.peerId, "m2", wrongVersion)).rejects.toMatchObject({
      code: "unsupported",
    });
  });
});

describe("inbox state cache", () => {
  it("keeps the newest 200 messages and persists lastReceived atomically", async () => {
    const home = await mkdtemp(join(tmpdir(), "pb-inbox-state-"));
    const store = await InboxStateStore.open({ home });
    expect(store.lastReceived).toBeUndefined();
    for (let index = 0; index < MAX_RECENT_INBOX_MESSAGES + 5; index += 1) {
      store.note({ id: `m${index}`, from: "bob", text: `text-${index}`, createdAt: index });
    }
    await store.save();

    expect(store.all()).toHaveLength(MAX_RECENT_INBOX_MESSAGES);
    expect(store.all()[0]?.id).toBe("m5");
    const reloaded = await InboxStateStore.open({ home });
    expect(reloaded.lastReceived).toMatchObject({ id: `m${MAX_RECENT_INBOX_MESSAGES + 4}`, text: `text-${MAX_RECENT_INBOX_MESSAGES + 4}` });
    expect(reloaded.findById("m100")).toMatchObject({ from: "bob", createdAt: 100 });
    expect(reloaded.latestFrom("bob")?.id).toBe(`m${MAX_RECENT_INBOX_MESSAGES + 4}`);
    const raw = JSON.parse(await readFile(inboxStatePath(home), "utf8")) as {
      version: number;
      recent: Record<string, unknown>;
    };
    expect(raw.version).toBe(1);
    expect(Object.keys(raw.recent)).toHaveLength(MAX_RECENT_INBOX_MESSAGES);
  });
});

describe("end-to-end bot messaging", () => {
  it("lists peers and stores messages for an offline recipient", async () => {
    const fixture = await botFixture();
    try {
      const peers = await fixture.alice.listPeers();
      expect(peers.map((peer) => peer.peerId)).toEqual([fixture.bob.botPeerId]);
      expect(peers[0]).toMatchObject({
        name: "Bob",
        fingerprint: fingerprintForPeerId(fixture.bob.botPeerId),
        deviceId: fixture.device.peerId,
        deviceName: "Test phone",
        online: false,
      });

      const sent = await fixture.alice.sendMessage("Bob", "hello bob");
      expect(sent).toMatchObject({ to: fixture.bob.botPeerId, toName: "Bob" });
      expect(fixture.relay.mailbox(fixture.bob.botPeerId)).toHaveLength(1);

      const inbox = await fixture.bob.readInbox();
      expect(inbox).toMatchObject([{ from: fixture.alice.botPeerId, fromName: "Alice", text: "hello bob" }]);
      expect(fixture.relay.mailbox(fixture.bob.botPeerId)).toHaveLength(0);
      expect(await fixture.bob.readInbox()).toEqual([]);

      const cached = JSON.parse(
        await readFile(join(fixture.bob.keystore.home, "inbox-state.json"), "utf8"),
      ) as { lastReceived: { id: string } };
      expect(cached.lastReceived.id).toBe(sent.id);
    } finally {
      await closeFixture(fixture);
    }
  });

  it("pushes live messages to a waiting inbox and supports replies", async () => {
    const fixture = await botFixture();
    try {
      const reading = fixture.bob.readInbox({ wait: true, timeoutMs: 3000 });
      await fixture.relay.waitForPeer((peer) => peer.peerId === fixture.bob.botPeerId, 3000);
      const sent = await fixture.alice.sendMessage(fixture.bob.botPeerId, "live hello");
      expect(await reading).toMatchObject([{ text: "live hello" }]);

      const reply = await fixture.bob.reply("hi back");
      expect(reply).toMatchObject({ to: fixture.alice.botPeerId, replyTo: sent.id });
      const inbox = await fixture.alice.readInbox();
      expect(inbox).toMatchObject([{ from: fixture.bob.botPeerId, text: "hi back", replyTo: sent.id }]);
    } finally {
      await closeFixture(fixture);
    }
  });

  it("returns an empty array when --wait times out", async () => {
    const fixture = await botFixture();
    try {
      expect(await fixture.bob.readInbox({ wait: true, timeoutMs: 120 })).toEqual([]);
    } finally {
      await closeFixture(fixture);
    }
  });

  it("accepts a device's peers_sync envelope and applies its ACL", async () => {
    const fixture = await botFixture();
    const deviceRelay = new RelayConnection({
      url: fixture.relay.url(),
      role: "device",
      identity: fixture.device,
    });
    try {
      await deviceRelay.connect();
      const disabledBob: RelayPeerEntry = { ...(fixture.entries[1] as RelayPeerEntry), can_message: false };
      deviceRelay.sendControl({
        type: "peers_sync",
        peers: [fixture.entries[0] as RelayPeerEntry, disabledBob],
        device_name: "Test phone",
      });
      await vi.waitFor(
        async () => {
          await expect(fixture.bob.listPeers()).rejects.toMatchObject({ code: "permission_denied" });
        },
        { timeout: 2000, interval: 25 },
      );
      const peers = await fixture.alice.listPeers();
      expect(peers[0]).toMatchObject({ peerId: fixture.bob.botPeerId, canMessage: false });
    } finally {
      deviceRelay.close();
      await closeFixture(fixture);
    }
  });

  it("denies messaging when the device ACL does not allow the recipient", async () => {
    const fixture = await botFixture();
    try {
      const disabledBob: RelayPeerEntry = { ...(fixture.entries[1] as RelayPeerEntry), can_message: false };
      fixture.relay.syncPeers(fixture.device.peerId, [fixture.entries[0] as RelayPeerEntry, disabledBob], "Test phone");
      await expect(fixture.alice.sendMessage("Bob", "not allowed")).rejects.toMatchObject({
        code: "permission_denied",
      });

      const disabledAlice: RelayPeerEntry = { ...(fixture.entries[0] as RelayPeerEntry), can_message: false };
      fixture.relay.syncPeers(fixture.device.peerId, [disabledAlice, disabledBob], "Test phone");
      await expect(fixture.bob.listPeers()).rejects.toMatchObject({ code: "permission_denied" });
      await expect(fixture.alice.sendMessage("Bob", "still not allowed")).rejects.toMatchObject({
        code: "permission_denied",
      });
    } finally {
      await closeFixture(fixture);
    }
  });

  it("exposes peers, send, inbox and reply through the CLI", async () => {
    const fixture = await botFixture();
    const aliceHome = fixture.alice.keystore.home;
    const bobHome = fixture.bob.keystore.home;
    try {
      const peers = capture();
      expect(await runCli(["--home", aliceHome, "peers"], peers.io)).toBe(0);
      const peerList = (JSON.parse(peers.stdout.join("")) as { peers: Array<{ peerId: string }> }).peers;
      expect(peerList.map((peer) => peer.peerId)).toEqual([fixture.bob.botPeerId]);

      const send = capture();
      expect(await runCli(["--home", aliceHome, "send", "Bob", "cli hello"], send.io)).toBe(0);
      const sentId = (
        JSON.parse(send.stdout.join("")) as { message: { id: string } }
      ).message.id;

      const inbox = capture();
      expect(await runCli(["--home", bobHome, "inbox"], inbox.io)).toBe(0);
      const messages = (JSON.parse(inbox.stdout.join("")) as { messages: Array<{ text: string; id: string }> }).messages;
      expect(messages).toMatchObject([{ text: "cli hello", id: sentId }]);

      const reply = capture();
      expect(
        await runCli(
          [
            "--home",
            bobHome,
            "reply",
            "--to",
            fixture.alice.botPeerId,
            "--reply-to",
            sentId,
            "cli reply",
          ],
          reply.io,
        ),
      ).toBe(0);
      const replyInbox = capture();
      expect(await runCli(["--home", aliceHome, "inbox"], replyInbox.io)).toBe(0);
      expect(
        (JSON.parse(replyInbox.stdout.join("")) as { messages: Array<{ text: string; replyTo: string }> }).messages,
      ).toMatchObject([{ text: "cli reply", replyTo: sentId }]);

      const byMessageId = capture();
      expect(await runCli(["--home", bobHome, "reply", sentId, "by message id"], byMessageId.io)).toBe(0);
      const idInbox = capture();
      expect(await runCli(["--home", aliceHome, "inbox"], idInbox.io)).toBe(0);
      expect(
        (JSON.parse(idInbox.stdout.join("")) as { messages: Array<{ text: string; replyTo: string }> }).messages,
      ).toMatchObject([{ text: "by message id", replyTo: sentId }]);

      const byPeer = capture();
      expect(
        await runCli(["--home", bobHome, "reply", fixture.alice.botPeerId, "by peer"], byPeer.io),
      ).toBe(0);
      const peerInbox = capture();
      expect(await runCli(["--home", aliceHome, "inbox"], peerInbox.io)).toBe(0);
      expect(
        (JSON.parse(peerInbox.stdout.join("")) as { messages: Array<{ text: string }> }).messages,
      ).toMatchObject([{ text: "by peer" }]);

      const waiting = capture();
      expect(await runCli(["--home", aliceHome, "inbox", "--wait", "--timeout", "100"], waiting.io)).toBe(0);
      expect((JSON.parse(waiting.stdout.join("")) as { messages: unknown[] }).messages).toEqual([]);
    } finally {
      await closeFixture(fixture);
    }
  });

  it("serves bot messaging through MCP tools", async () => {
    const fixture = await botFixture();
    const aliceMcp = await connectMcp(fixture.alice);
    const bobMcp = await connectMcp(fixture.bob);
    try {
      const listed = await aliceMcp.listTools();
      const names = listed.tools.map((tool) => tool.name);
      for (const name of ["pb_list_peers", "pb_send_message", "pb_read_inbox"]) expect(names).toContain(name);

      const peers = toolPayload(await aliceMcp.callTool({ name: "pb_list_peers", arguments: {} }));
      expect(peers["ok"]).toBe(true);
      expect((peers["peers"] as Array<{ peerId: string }>).map((peer) => peer.peerId)).toEqual([
        fixture.bob.botPeerId,
      ]);

      const sent = toolPayload(
        await aliceMcp.callTool({ name: "pb_send_message", arguments: { to: "Bob", text: "mcp hello" } }),
      );
      expect(sent["ok"]).toBe(true);
      const inbox = toolPayload(await bobMcp.callTool({ name: "pb_read_inbox", arguments: {} }));
      expect(inbox["ok"]).toBe(true);
      expect(inbox["messages"]).toMatchObject([{ text: "mcp hello" }]);
    } finally {
      await aliceMcp.close();
      await bobMcp.close();
      await closeFixture(fixture);
    }
  });
});
