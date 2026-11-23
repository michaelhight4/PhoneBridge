/**
 * MCP server entry point.
 *
 *   node dist/server/index.js                          # stdio (OpenCode, Claude Desktop/Code, …)
 *   node dist/server/index.js --http 8765              # streamable HTTP at /mcp
 *
 * Everything that writes to stdout is protocol traffic, so diagnostics go to stderr only.
 */
import { pathToFileURL } from "node:url";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { PhoneBridgeClient } from "../client/client.js";
import { startHttpServer, type RunningHttpServer } from "./http.js";
import { createPhoneBridgeServer } from "./server.js";
import { VERSION } from "./version.js";

export interface ServerCliOptions {
  httpPort?: number;
  host?: string;
  home?: string;
  botName?: string;
  device?: string;
  insecureDev?: boolean;
  help?: boolean;
}

export function parseServerArgs(argv: string[]): ServerCliOptions {
  const options: ServerCliOptions = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = (): string => {
      const value = argv[index + 1];
      if (value === undefined) throw new Error(`missing value for ${String(arg)}`);
      index += 1;
      return value;
    };
    switch (arg) {
      case "--http": {
        const raw = next();
        const port = Number(raw);
        if (!Number.isInteger(port) || port < 0 || port > 65535) {
          throw new Error(`invalid --http port: ${raw}`);
        }
        options.httpPort = port;
        break;
      }
      case "--host":
        options.host = next();
        break;
      case "--home":
        options.home = next();
        break;
      case "--bot-name":
        options.botName = next();
        break;
      case "--device":
        options.device = next();
        break;
      case "--insecure-dev":
        options.insecureDev = true;
        break;
      case "--version":
        process.stderr.write(`${VERSION}\n`);
        process.exit(0);
        break;
      case "--help":
      case "-h":
        options.help = true;
        break;
      default:
        throw new Error(`unknown argument: ${String(arg)}`);
    }
  }
  return options;
}

const USAGE = `PhoneBridge MCP server ${VERSION}

Usage: phonebridge-mcp [options]

  --http <port>       Serve streamable HTTP on /mcp instead of stdio
  --host <host>       HTTP bind host (default 127.0.0.1)
  --home <dir>        Keystore directory (default $PHONEBRIDGE_HOME or ~/.phonebridge)
  --bot-name <name>   Name shown on the phone when pairing
  --device <ref>      Default device (peer_id, prefix, name or fingerprint)
  --insecure-dev      Allow plain ws:// relays (loopback only by default)
  --version           Print the version
  --help              Print this help
`;

export async function main(argv = process.argv.slice(2)): Promise<void> {
  const options = parseServerArgs(argv);
  if (options.help === true) {
    process.stderr.write(USAGE);
    return;
  }

  const client = await PhoneBridgeClient.open({
    ...(options.home === undefined ? {} : { home: options.home }),
    ...(options.botName === undefined ? {} : { botName: options.botName }),
    ...(options.insecureDev === undefined ? {} : { insecureDev: options.insecureDev }),
  });

  const context = {
    client,
    ...(options.device === undefined ? {} : { defaultDevice: options.device }),
  };

  let httpServer: RunningHttpServer | undefined;
  if (options.httpPort !== undefined) {
    httpServer = await startHttpServer({
      port: options.httpPort,
      ...(options.host === undefined ? {} : { host: options.host }),
      context,
      log: (message) => process.stderr.write(`${message}\n`),
    });
  } else {
    const server = createPhoneBridgeServer(context);
    await server.connect(new StdioServerTransport());
    process.stderr.write(`PhoneBridge MCP server ${VERSION} ready on stdio\n`);
  }

  const shutdown = async (): Promise<void> => {
    await httpServer?.close();
    await client.close();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown());
  process.on("SIGTERM", () => void shutdown());
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  main().catch((error: unknown) => {
    process.stderr.write(`PhoneBridge MCP server failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  });
}
