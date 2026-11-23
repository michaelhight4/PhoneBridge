import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerPhoneBridgeTools, type ToolContext } from "./tools.js";
import { SERVER_NAME, VERSION } from "./version.js";

const INSTRUCTIONS = `PhoneBridge controls the user's own Android phone over an end-to-end encrypted link.
Typical flow: pb_pair once (short code, link or .pbpair file), pb_devices / pb_status to check the device,
pb_screenshot and pb_ui_tree to look at the screen, then pb_tap / pb_swipe / pb_type / pb_key to act.
Call pb_request_control before driving and pb_release_control when finished; another bot may be queued.
Everything at Full level (pb_shell, pb_files, pb_install_apk, pb_relay_move) only works if the user granted it.
On error the tool result is not ok and carries a protocol error code such as permission_denied, busy or peer_offline.`;

/** Creates the PhoneBridge MCP server (tools only; attach any transport). */
export function createPhoneBridgeServer(context: ToolContext): McpServer {
  const server = new McpServer(
    { name: SERVER_NAME, version: VERSION, title: "PhoneBridge" },
    { instructions: INSTRUCTIONS },
  );
  registerPhoneBridgeTools(server, context);
  return server;
}
