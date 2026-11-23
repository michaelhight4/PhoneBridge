import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { PhoneBridgeClient } from "../src/client/client.js";
import { createPhoneBridgeServer } from "../src/server/server.js";
import { TOOL_NAMES } from "../src/server/tools.js";
import { MockRelay } from "./helpers/mockRelay.js";
import { MockDevice, createMockOffer } from "./helpers/mockDevice.js";
import { createIdentity, randomBytes } from "../src/client/crypto.js";

interface TextContent {
  type: "text";
  text: string;
}

interface ImageContent {
  type: "image";
  data: string;
  mimeType: string;
}

interface CallResult {
  content: Array<TextContent | ImageContent | { type: string }>;
  isError?: boolean;
}

function payload(result: CallResult): Record<string, unknown> {
  const first = result.content[0];
  if (first === undefined || first.type !== "text") {
    throw new Error("expected a text content block");
  }
  return JSON.parse((first as TextContent).text) as Record<string, unknown>;
}

async function connectPair(
  server: ReturnType<typeof createPhoneBridgeServer>,
): Promise<Client> {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const mcpClient = new Client({ name: "vitest", version: "0.0.0" }, { capabilities: {} });
  await Promise.all([mcpClient.connect(clientTransport), server.connect(serverTransport)]);
  return mcpClient;
}

async function tempClient(): Promise<PhoneBridgeClient> {
  const home = await mkdtemp(join(tmpdir(), "phonebridge-mcp-"));
  return PhoneBridgeClient.open({ home, botName: "mcp-test-bot" });
}

describe("MCP tool surface", () => {
  it("lists every documented tool with a schema", async () => {
    const client = await tempClient();
    const server = createPhoneBridgeServer({ client });
    const mcpClient = await connectPair(server);
    try {
      const listed = await mcpClient.listTools();
      const names = listed.tools.map((tool) => tool.name);
      for (const expected of TOOL_NAMES) {
        expect(names, `missing tool ${expected}`).toContain(expected);
      }
      expect(new Set(names).size).toBe(names.length);
      for (const tool of listed.tools) {
        expect(tool.description ?? "").not.toBe("");
        expect(tool.inputSchema).toBeTruthy();
      }
      const tap = listed.tools.find((tool) => tool.name === "pb_tap");
      expect(Object.keys(tap?.inputSchema.properties ?? {})).toEqual(
        expect.arrayContaining(["x", "y", "node_id", "device"]),
      );
      const files = listed.tools.find((tool) => tool.name === "pb_files");
      expect(JSON.stringify(files?.inputSchema)).toContain('"list"');
    } finally {
      await mcpClient.close();
      await client.close();
    }
  });

  it("returns an empty device list and rejects bad tap arguments", async () => {
    const client = await tempClient();
    const server = createPhoneBridgeServer({ client });
    const mcpClient = await connectPair(server);
    try {
      const devices = payload((await mcpClient.callTool({ name: "pb_devices", arguments: {} })) as CallResult);
      expect(devices["ok"]).toBe(true);
      expect(devices["devices"]).toEqual([]);
      expect(devices["bot"]).toMatchObject({ peer_id: client.botPeerId });

      const badTap = (await mcpClient.callTool({
        name: "pb_tap",
        arguments: { x: 10 },
      })) as CallResult;
      expect(badTap.isError).toBe(true);
      expect(payload(badTap)["error"]).toMatchObject({ code: "bad_args" });
    } finally {
      await mcpClient.close();
      await client.close();
    }
  });

  it("pairs, screenshots and taps through MCP tools against a mock device", async () => {
    const relay = new MockRelay();
    await relay.listen();
    const home = await mkdtemp(join(tmpdir(), "phonebridge-mcp-"));
    const deviceIdentity = await createIdentity();
    const secret = randomBytes(32);
    const { link } = await createMockOffer(deviceIdentity, relay.url(), { secret });
    const device = await MockDevice.create({
      relayUrl: relay.url(),
      identity: deviceIdentity,
      pairingSecret: secret,
      autoPair: true,
      level: "full",
    });
    const client = await PhoneBridgeClient.open({ home, botName: "mcp-e2e-bot" });
    const server = createPhoneBridgeServer({ client, defaultDevice: device.peerId });
    const mcpClient = await connectPair(server);

    try {
      const paired = payload(
        (await mcpClient.callTool({
          name: "pb_pair",
          arguments: { input: link, level: "full", name: "mcp-e2e-bot" },
        })) as CallResult,
      );
      expect(paired["ok"]).toBe(true);
      expect(paired["level"]).toBe("full");

      const status = payload(
        (await mcpClient.callTool({ name: "pb_status", arguments: {} })) as CallResult,
      );
      expect(status["ok"]).toBe(true);
      expect((status["result"] as Record<string, unknown>)["device"]).toBe("Mock Pixel");

      const tree = payload(
        (await mcpClient.callTool({ name: "pb_ui_tree", arguments: { max_depth: 3 } })) as CallResult,
      );
      expect(tree["ok"]).toBe(true);

      const shot = (await mcpClient.callTool({
        name: "pb_screenshot",
        arguments: { format: "png" },
      })) as CallResult;
      expect(shot.isError).toBeFalsy();
      const image = shot.content.find((item) => item.type === "image") as ImageContent | undefined;
      expect(image).toBeDefined();
      expect(image?.mimeType).toBe("image/png");
      expect(Buffer.from(image?.data ?? "", "base64").toString("utf8")).toBe("mock-png-bytes");

      const tap = payload(
        (await mcpClient.callTool({ name: "pb_tap", arguments: { x: 540, y: 1200 } })) as CallResult,
      );
      expect(tap["ok"]).toBe(true);
      const tapRequest = device.received.find((entry) => entry.cmd === "tap");
      expect(tapRequest?.args).toEqual({ x: 540, y: 1200 });

      const shell = payload(
        (await mcpClient.callTool({ name: "pb_shell", arguments: { command: "echo hi" } })) as CallResult,
      );
      expect((shell["result"] as Record<string, unknown>)["stdout"]).toBe("ran echo hi");

      const devices = payload(
        (await mcpClient.callTool({ name: "pb_devices", arguments: {} })) as CallResult,
      );
      expect((devices["devices"] as unknown[]).length).toBe(1);
      const keystoreRaw = await readFile(join(home, "keystore.json"), "utf8");
      expect(keystoreRaw).toContain(device.peerId);
    } finally {
      await mcpClient.close();
      await client.close();
      await device.stop();
      await relay.close();
    }
  });
});
