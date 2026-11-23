/**
 * MCP tool definitions for PhoneBridge.
 *
 * Every tool is a thin wrapper over the documented JSON commands, so the MCP surface and the CLI
 * surface stay identical (see docs/API.md).
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";
import { PhoneBridgeClient } from "../client/client.js";
import { PhoneBridgeError, isPhoneBridgeError } from "../client/errors.js";
import { PERMISSION_LEVELS, type PermissionLevel } from "../client/keystore.js";

export const TOOL_NAMES = [
  "pb_pair",
  "pb_devices",
  "pb_status",
  "pb_screenshot",
  "pb_ui_tree",
  "pb_tap",
  "pb_swipe",
  "pb_type",
  "pb_key",
  "pb_open_app",
  "pb_install_apk",
  "pb_shell",
  "pb_files",
  "pb_relay_move",
  "pb_request_control",
  "pb_release_control",
  "pb_notifications",
] as const;

export type ToolName = (typeof TOOL_NAMES)[number];

export interface ToolContext {
  client: PhoneBridgeClient;
  /** Default device reference (peer_id, name or fingerprint) when a tool omits `device`. */
  defaultDevice?: string | undefined;
}

type TextContent = { type: "text"; text: string };
type ImageContent = { type: "image"; data: string; mimeType: string };

interface ToolResult {
  content: Array<TextContent | ImageContent>;
  isError?: boolean;
  [key: string]: unknown;
}

const deviceArg = z
  .string()
  .optional()
  .describe("Paired device to target: peer_id, peer_id prefix, name or fingerprint (ABCD-EFGH).");

const KEY_NAMES = [
  "back",
  "home",
  "recents",
  "notifications",
  "quick_settings",
  "lock",
  "enter",
  "power_dialog",
] as const;

function json(value: unknown): ToolResult {
  return { content: [{ type: "text", text: JSON.stringify(value, null, 2) }] };
}

function failure(error: unknown): ToolResult {
  const payload = isPhoneBridgeError(error)
    ? error.toJSON()
    : { code: "internal", message: error instanceof Error ? error.message : String(error) };
  return {
    isError: true,
    content: [{ type: "text", text: JSON.stringify({ ok: false, error: payload }, null, 2) }],
  };
}

export function registerPhoneBridgeTools(server: McpServer, context: ToolContext): void {
  const { client } = context;
  const device = (explicit: string | undefined): string | undefined =>
    explicit ?? context.defaultDevice;

  const run = async (
    cmd: string,
    args: Record<string, unknown>,
    explicitDevice: string | undefined,
  ): Promise<ToolResult> => {
    try {
      const result = await client.request(cmd, args, {
        ...(device(explicitDevice) === undefined ? {} : { device: device(explicitDevice) as string }),
      });
      return json({ ok: true, result });
    } catch (error) {
      return failure(error);
    }
  };

  server.registerTool(
    "pb_pair",
    {
      title: "Pair with a phone",
      description:
        "Pair with an Android phone using a short code (e.g. 7K4-Q9M), a phonebridge://pair link, an " +
        "offer JSON object or a .pbpair file. The phone shows an 'Allow this bot?' prompt; pairing " +
        "succeeds only when the user taps Allow.",
      inputSchema: {
        input: z.string().describe("Short code, phonebridge:// link, offer JSON or path to a .pbpair file."),
        relay_url: z
          .string()
          .optional()
          .describe("Relay URL for the short-code form (e.g. wss://relay.example:8765)."),
        level: z
          .enum(PERMISSION_LEVELS as unknown as [PermissionLevel, ...PermissionLevel[]])
          .optional()
          .describe("Permission level to request: view, control or full. Defaults to control."),
        name: z.string().optional().describe("Bot name shown on the phone's consent prompt."),
      },
    },
    async (args) => {
      try {
        const outcome = await client.pair(args.input, {
          ...(args.relay_url === undefined ? {} : { relayUrl: args.relay_url }),
          ...(args.level === undefined ? {} : { requestedLevel: args.level }),
          ...(args.name === undefined ? {} : { botName: args.name }),
        });
        return json({ ok: true, ...outcome });
      } catch (error) {
        return failure(error);
      }
    },
  );

  server.registerTool(
    "pb_devices",
    {
      title: "List paired devices",
      description: "Lists paired phones with their name, fingerprint, permission level and last seen time.",
      inputSchema: {},
    },
    async () => {
      try {
        return json({
          ok: true,
          bot: { peer_id: client.botPeerId, fingerprint: client.botFingerprint },
          devices: client.devices(),
        });
      } catch (error) {
        return failure(error);
      }
    },
  );

  server.registerTool(
    "pb_status",
    {
      title: "Device status",
      description:
        "Returns device name, battery, screen state, current controller, control queue and Shizuku availability.",
      inputSchema: { device: deviceArg },
    },
    async (args) => run("status", {}, args.device),
  );

  server.registerTool(
    "pb_screenshot",
    {
      title: "Screenshot",
      description:
        "Captures the phone screen and returns it as an MCP image (PNG by default). Large screenshots " +
        "are chunked over the end-to-end channel and reassembled before this tool returns.",
      inputSchema: {
        format: z.enum(["png", "jpeg"]).optional().describe("Image format, defaults to png."),
        quality: z.number().int().min(1).max(100).optional().describe("JPEG quality 1-100."),
        max_width: z.number().int().min(64).optional().describe("Downscale so the width is at most this."),
        device: deviceArg,
      },
    },
    async (args) => {
      try {
        const result = (await client.request(
          "screenshot",
          {
            ...(args.format === undefined ? {} : { format: args.format }),
            ...(args.quality === undefined ? {} : { quality: args.quality }),
            ...(args.max_width === undefined ? {} : { max_width: args.max_width }),
          },
          { ...(device(args.device) === undefined ? {} : { device: device(args.device) as string }) },
        )) as Record<string, unknown>;
        const data = typeof result["data_b64"] === "string" ? result["data_b64"] : "";
        if (data === "") {
          throw new PhoneBridgeError("internal", "device returned a screenshot without data_b64");
        }
        const mime = typeof result["mime"] === "string" ? result["mime"] : "image/png";
        const meta = {
          mime,
          width: result["width"],
          height: result["height"],
          bytes: Buffer.from(data, "base64").length,
        };
        return {
          content: [
            { type: "text" as const, text: JSON.stringify({ ok: true, ...meta }, null, 2) },
            { type: "image" as const, data, mimeType: mime },
          ],
        };
      } catch (error) {
        return failure(error);
      }
    },
  );

  server.registerTool(
    "pb_ui_tree",
    {
      title: "UI tree",
      description:
        "Returns the accessibility tree of the current screen: id, class, text, content description, " +
        "resource id, bounds, clickable/focusable/editable flags and children.",
      inputSchema: {
        max_depth: z.number().int().min(1).max(64).optional(),
        visible_only: z.boolean().optional(),
        device: deviceArg,
      },
    },
    async (args) =>
      run(
        "ui_tree",
        {
          ...(args.max_depth === undefined ? {} : { max_depth: args.max_depth }),
          ...(args.visible_only === undefined ? {} : { visible_only: args.visible_only }),
        },
        args.device,
      ),
  );

  server.registerTool(
    "pb_notifications",
    {
      title: "Notifications",
      description: "Lists notifications posted after `since` (unix ms), or all visible notifications.",
      inputSchema: {
        since: z.number().int().optional().describe("Only notifications newer than this unix timestamp (ms)."),
        device: deviceArg,
      },
    },
    async (args) =>
      run("notifications", { ...(args.since === undefined ? {} : { since: args.since }) }, args.device),
  );

  server.registerTool(
    "pb_tap",
    {
      title: "Tap",
      description: "Taps screen coordinates or the centre of a UI node returned by pb_ui_tree.",
      inputSchema: {
        x: z.number().int().min(0).optional().describe("Horizontal pixel coordinate."),
        y: z.number().int().min(0).optional().describe("Vertical pixel coordinate."),
        node_id: z.string().optional().describe("Node id from pb_ui_tree (alternative to x/y)."),
        device: deviceArg,
      },
    },
    async (args) => {
      if (args.node_id !== undefined) return run("tap", { node_id: args.node_id }, args.device);
      if (args.x === undefined || args.y === undefined) {
        return failure(
          new PhoneBridgeError("bad_args", "pb_tap needs either x and y, or node_id"),
        );
      }
      return run("tap", { x: args.x, y: args.y }, args.device);
    },
  );

  server.registerTool(
    "pb_swipe",
    {
      title: "Swipe",
      description: "Swipes from (x1,y1) to (x2,y2) over `ms` milliseconds (default 300).",
      inputSchema: {
        x1: z.number().int().min(0),
        y1: z.number().int().min(0),
        x2: z.number().int().min(0),
        y2: z.number().int().min(0),
        ms: z.number().int().min(1).max(10_000).optional(),
        device: deviceArg,
      },
    },
    async (args) =>
      run(
        "swipe",
        {
          x1: args.x1,
          y1: args.y1,
          x2: args.x2,
          y2: args.y2,
          ...(args.ms === undefined ? {} : { ms: args.ms }),
        },
        args.device,
      ),
  );

  server.registerTool(
    "pb_type",
    {
      title: "Type text",
      description: "Types text into the focused field, or into the given node (focusing it first).",
      inputSchema: {
        text: z.string(),
        node_id: z.string().optional(),
        device: deviceArg,
      },
    },
    async (args) =>
      run(
        "type",
        { text: args.text, ...(args.node_id === undefined ? {} : { node_id: args.node_id }) },
        args.device,
      ),
  );

  server.registerTool(
    "pb_key",
    {
      title: "Press a key / global action",
      description: "Sends a global action: back, home, recents, notifications, quick_settings, lock, enter, power_dialog.",
      inputSchema: {
        key: z.enum(KEY_NAMES),
        device: deviceArg,
      },
    },
    async (args) => run("key", { key: args.key }, args.device),
  );

  server.registerTool(
    "pb_open_app",
    {
      title: "Open app",
      description: "Launches an app by package name or by the label shown in the launcher.",
      inputSchema: {
        package: z.string().optional(),
        label: z.string().optional(),
        device: deviceArg,
      },
    },
    async (args) => {
      if (args.package === undefined && args.label === undefined) {
        return failure(new PhoneBridgeError("bad_args", "pb_open_app needs package or label"));
      }
      return run(
        "open_app",
        {
          ...(args.package === undefined ? {} : { package: args.package }),
          ...(args.label === undefined ? {} : { label: args.label }),
        },
        args.device,
      );
    },
  );

  server.registerTool(
    "pb_install_apk",
    {
      title: "Install APK (Full)",
      description: "Installs an APK from an HTTPS URL or from base64 data. Requires Full permission and Shizuku.",
      inputSchema: {
        url: z.string().optional().describe("HTTPS URL the phone can download."),
        data_b64: z.string().optional().describe("APK bytes, base64."),
        name: z.string().optional().describe("File name to use when installing from data_b64."),
        device: deviceArg,
      },
    },
    async (args) => {
      if (args.url === undefined && args.data_b64 === undefined) {
        return failure(new PhoneBridgeError("bad_args", "pb_install_apk needs url or data_b64"));
      }
      return run(
        "install_apk",
        {
          ...(args.url === undefined ? {} : { url: args.url }),
          ...(args.data_b64 === undefined ? {} : { data_b64: args.data_b64 }),
          ...(args.name === undefined ? {} : { name: args.name }),
        },
        args.device,
      );
    },
  );

  server.registerTool(
    "pb_shell",
    {
      title: "Shell command (Full)",
      description: "Runs a shell command as the shell user through Shizuku. Requires Full permission.",
      inputSchema: {
        command: z.string().min(1),
        timeout_ms: z.number().int().min(1).max(600_000).optional(),
        device: deviceArg,
      },
    },
    async (args) =>
      run(
        "shell",
        {
          command: args.command,
          ...(args.timeout_ms === undefined ? {} : { timeout_ms: args.timeout_ms }),
        },
        args.device,
      ),
  );

  server.registerTool(
    "pb_files",
    {
      title: "Files (Full)",
      description: "Lists, reads, writes or deletes a file on the phone. Requires Full permission.",
      inputSchema: {
        op: z.enum(["list", "read", "write", "delete"]),
        path: z.string().min(1),
        data_b64: z.string().optional().describe("Base64 bytes, required for write."),
        device: deviceArg,
      },
    },
    async (args) =>
      run(
        "files",
        {
          op: args.op,
          path: args.path,
          ...(args.data_b64 === undefined ? {} : { data_b64: args.data_b64 }),
        },
        args.device,
      ),
  );

  server.registerTool(
    "pb_relay_move",
    {
      title: "Move the relay (Full)",
      description:
        "Moves the relay ('the heart') to this bot, another peer or a URL. Requires Full permission.",
      inputSchema: {
        to: z.string().min(1).describe("this, a peer_id, or a relay URL."),
        device: deviceArg,
      },
    },
    async (args) => run("relay_move", { to: args.to }, args.device),
  );

  server.registerTool(
    "pb_request_control",
    {
      title: "Request control",
      description:
        "Asks for the drive. Grants immediately when free, otherwise queues FIFO and returns the queue position.",
      inputSchema: { device: deviceArg },
    },
    async (args) => run("request_control", {}, args.device),
  );

  server.registerTool(
    "pb_release_control",
    {
      title: "Release control",
      description: "Releases the drive so the next queued bot can take over.",
      inputSchema: { device: deviceArg },
    },
    async (args) => run("release_control", {}, args.device),
  );
}
