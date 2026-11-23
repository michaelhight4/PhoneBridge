/**
 * Optional streamable-HTTP transport (spec 2025-06-18). Stdio is the default; `--http 8765`
 * serves the same tools at http://127.0.0.1:8765/mcp for clients that cannot spawn a process.
 */
import { createServer, type Server } from "node:http";
import { randomUUID } from "node:crypto";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createPhoneBridgeServer } from "./server.js";
import type { ToolContext } from "./tools.js";

export interface HttpServerOptions {
  port: number;
  host?: string;
  context: ToolContext;
  log?: (message: string) => void;
}

export interface RunningHttpServer {
  port: number;
  url: string;
  close: () => Promise<void>;
}

export async function startHttpServer(options: HttpServerOptions): Promise<RunningHttpServer> {
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: () => randomUUID(),
    enableJsonResponse: true,
  });
  const server = createPhoneBridgeServer(options.context);
  await server.connect(transport);

  const httpServer: Server = createServer((request, response) => {
    void transport.handleRequest(request, response).catch((error: unknown) => {
      if (!response.headersSent) {
        response.writeHead(500, { "content-type": "application/json" });
      }
      response.end(
        JSON.stringify({
          jsonrpc: "2.0",
          error: { code: -32603, message: error instanceof Error ? error.message : String(error) },
          id: null,
        }),
      );
    });
  });

  const host = options.host ?? "127.0.0.1";
  await new Promise<void>((resolve, reject) => {
    httpServer.once("error", reject);
    httpServer.listen(options.port, host, () => resolve());
  });
  const address = httpServer.address();
  const port = typeof address === "object" && address !== null ? address.port : options.port;
  options.log?.(`PhoneBridge MCP server listening on http://${host}:${port}/mcp`);

  return {
    port,
    url: `http://${host}:${port}/mcp`,
    close: async () => {
      await server.close();
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
    },
  };
}
