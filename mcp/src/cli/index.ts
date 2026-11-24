#!/usr/bin/env node
/**
 * `phonebridge` CLI — the same commands as the MCP server, for scripts and bots that can only run
 * shell commands.
 *
 *   phonebridge pair 7K4-Q9M --relay wss://relay.example:8765
 *   phonebridge tap 540 1200
 *   phonebridge screenshot -o shot.png
 *   phonebridge shell -- "pm list packages"
 */
import { writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { PhoneBridgeClient } from "../client/client.js";
import { PhoneBridgeError, isPhoneBridgeError } from "../client/errors.js";
import { fromBase64Loose, toBase64Url } from "../client/bytes.js";
import { normalizeLevel, type PermissionLevel } from "../client/levels.js";
import { PERMISSION_LEVELS } from "../client/keystore.js";
import { InboxStateStore } from "../client/inboxState.js";
import { VERSION } from "../server/version.js";

export interface CliIo {
  out: (text: string) => void;
  err: (text: string) => void;
}

const VALUE_FLAGS = new Set([
  "--home",
  "--device",
  "-d",
  "--relay",
  "-r",
  "--timeout",
  "--level",
  "--name",
  "--format",
  "--quality",
  "--max-width",
  "--max-depth",
  "--ms",
  "--node",
  "--label",
  "--since",
  "--data-b64",
  "--file",
  "--output",
  "-o",
  "--bot-name",
  "--reply-to",
  "--limit",
  "--to",
]);

const BOOLEAN_FLAGS = new Set([
  "--json",
  "--visible-only",
  "--insecure-dev",
  "--wait",
  "--keep",
  "--help",
  "-h",
  "--version",
  "-v",
]);

export interface ParsedCli {
  command: string | undefined;
  positionals: string[];
  flags: Map<string, string | true>;
}

export function parseCliArgs(argv: string[]): ParsedCli {
  const positionals: string[] = [];
  const flags = new Map<string, string | true>();
  let command: string | undefined;
  let raw = false;

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index] as string;
    if (raw) {
      positionals.push(token);
      continue;
    }
    if (token === "--") {
      raw = true;
      continue;
    }
    if (token.startsWith("-") && token !== "-") {
      const [name, inline] = splitFlag(token);
      if (BOOLEAN_FLAGS.has(name)) {
        flags.set(name, true);
        continue;
      }
      if (!VALUE_FLAGS.has(name)) {
        throw new PhoneBridgeError("bad_input", `unknown option: ${name}`);
      }
      if (inline !== undefined) {
        flags.set(name, inline);
        continue;
      }
      const value = argv[index + 1];
      if (value === undefined) throw new PhoneBridgeError("bad_input", `missing value for ${name}`);
      index += 1;
      flags.set(name, value);
      continue;
    }
    if (command === undefined) {
      command = token;
    } else {
      positionals.push(token);
    }
  }

  return { command, positionals, flags };
}

function splitFlag(token: string): [string, string | undefined] {
  const equals = token.indexOf("=");
  if (equals < 0) return [token, undefined];
  return [token.slice(0, equals), token.slice(equals + 1)];
}

const USAGE = `PhoneBridge CLI ${VERSION}

Usage: phonebridge [options] <command> [args]

Commands:
  pair <code|link|file>                 Pair with a phone (short code, phonebridge:// link, .pbpair)
  devices                               List paired devices
  peers                                 List the bots this bot may message
  send <peer> <text…>                   Send an encrypted bot message
  inbox [--wait] [--keep] [--limit N]   Read bot messages
  reply [peer|msg_id] <text…>           Reply to a bot or the latest cached message
  status                                Device status (battery, screen, controller)
  screenshot [-o FILE]                  Save a screenshot (default: base64 on stdout)
  ui-tree                               Accessibility tree of the current screen
  notifications                         Recent notifications
  tap <x> <y> | tap --node <id>         Tap coordinates or a UI node
  swipe <x1> <y1> <x2> <y2> [--ms N]    Swipe gesture
  long-press <x> <y> [--ms N]           Long press
  type <text…>                          Type text into the focused field
  key <name>                            back|home|recents|notifications|quick_settings|lock|enter|power_dialog
  open-app <package>                    Launch an app by package (or --label NAME)
  install-apk <url> | --file <apk>      Install an APK (Full)
  shell -- <command>                    Run a shell command (Full)
  files <list|read|write|delete> <path> [--data-b64 DATA]   Files (Full)
  settings <ns> <key> [value]           Read or write a system setting (Full)
  relay-move <this|peer_id|url>         Move the relay (Full)
  request-control                       Ask for the drive
  release-control                       Release the drive

Options:
  -d, --device <ref>     Paired device: peer_id, prefix, name or fingerprint
      --home <dir>       Keystore directory (default $PHONEBRIDGE_HOME or ~/.phonebridge)
  -r, --relay <url>      Relay URL to reach the device: needed with a short code, and overrides a link/file relay (e.g. a public tunnel URL)
      --level <lvl>      Requested level when pairing: view|control|full
      --name <name>      Bot name shown on the phone
      --timeout <ms>     Request timeout
      --reply-to <id>    Mark a bot message as a reply
      --to <peer>        Reply to this bot instead of the cached sender
      --limit <n>        Maximum number of inbox messages
      --wait             Wait for a live bot message
      --keep             Do not consume inbox messages
      --json             Print raw JSON (default for most commands)
      --insecure-dev     Allow plain ws:// relays
  -h, --help             Show this help
  -v, --version          Show the version
`;

export async function runCli(argv: string[], io: CliIo): Promise<number> {
  let parsed: ParsedCli;
  try {
    parsed = parseCliArgs(argv);
  } catch (error) {
    return reportError(error, io);
  }

  const flags = parsed.flags;
  if (flags.has("--version") || flags.has("-v")) {
    io.out(`${VERSION}\n`);
    return 0;
  }
  const command = parsed.command;
  if (command === undefined || flags.has("--help") || flags.has("-h") || command === "help") {
    io.out(USAGE);
    return command === undefined && !flags.has("--help") && !flags.has("-h") ? 1 : 0;
  }

  const home = stringFlag(flags, "--home");
  const botName = stringFlag(flags, "--bot-name") ?? stringFlag(flags, "--name");
  const device = stringFlag(flags, "--device") ?? stringFlag(flags, "-d");
  const insecureDev = flags.has("--insecure-dev");
  const timeoutMs = numberFlag(flags, "--timeout");

  let client: PhoneBridgeClient;
  try {
    client = await PhoneBridgeClient.open({
      ...(home === undefined ? {} : { home }),
      ...(botName === undefined ? {} : { botName }),
      ...(insecureDev ? { insecureDev: true } : {}),
    });
  } catch (error) {
    return reportError(error, io);
  }

  try {
    switch (command) {
      case "pair": {
        const input = parsed.positionals.join(" ").trim();
        if (input === "") throw new PhoneBridgeError("bad_input", "usage: phonebridge pair <code|link|file>");
        const levelFlag = stringFlag(flags, "--level");
        const requestedLevel: PermissionLevel =
          levelFlag === undefined ? "control" : parseLevel(levelFlag);
        const relay = stringFlag(flags, "--relay") ?? stringFlag(flags, "-r");
        const outcome = await client.pair(input, {
          ...(relay === undefined ? {} : { relayUrl: relay }),
          ...(levelFlag === undefined ? {} : { requestedLevel }),
          ...(botName === undefined ? {} : { botName }),
        });
        io.out(
          `${JSON.stringify(
            {
              ok: true,
              device: outcome.device.name,
              peer_id: outcome.device.peerId,
              fingerprint: outcome.fingerprint,
              level: outcome.level,
              relay: outcome.device.relayUrl,
              keystore: client.keystore.path,
            },
            null,
            2,
          )}\n`,
        );
        return 0;
      }
      case "devices": {
        const devices = client.devices();
        io.out(
          `${JSON.stringify(
            {
              ok: true,
              bot: { peer_id: client.botPeerId, fingerprint: client.botFingerprint },
              devices,
            },
            null,
            2,
          )}\n`,
        );
        return 0;
      }
      case "peers": {
        const peers = await client.listPeers({
          ...(device === undefined ? {} : { device }),
          ...(relayOverride(flags) === undefined ? {} : { relayUrl: relayOverride(flags) }),
          ...(timeoutMs === undefined ? {} : { timeoutMs }),
        });
        io.out(`${JSON.stringify({ ok: true, peers }, null, 2)}\n`);
        return 0;
      }
      case "send": {
        const [peer, ...textParts] = parsed.positionals;
        if (peer === undefined || textParts.length === 0) {
          throw new PhoneBridgeError("bad_input", "usage: phonebridge send <peer> <text…>");
        }
        const message = await client.sendMessage(peer, textParts.join(" "), {
          ...(stringFlag(flags, "--reply-to") === undefined
            ? {}
            : { replyTo: stringFlag(flags, "--reply-to") as string }),
          ...(device === undefined ? {} : { device }),
          ...(relayOverride(flags) === undefined ? {} : { relayUrl: relayOverride(flags) }),
          ...(timeoutMs === undefined ? {} : { timeoutMs }),
        });
        io.out(`${JSON.stringify({ ok: true, message }, null, 2)}\n`);
        return 0;
      }
      case "inbox": {
        const messages = await client.readInbox({
          ...(flags.has("--wait") ? { wait: true } : {}),
          ...(flags.has("--keep") ? { keep: true } : {}),
          ...(numberFlag(flags, "--limit") === undefined ? {} : { limit: numberFlag(flags, "--limit") }),
          ...(device === undefined ? {} : { device }),
          ...(relayOverride(flags) === undefined ? {} : { relayUrl: relayOverride(flags) }),
          ...(timeoutMs === undefined ? {} : { timeoutMs }),
        });
        io.out(`${JSON.stringify({ ok: true, messages }, null, 2)}\n`);
        return 0;
      }
      case "reply": {
        const explicitTo = stringFlag(flags, "--to");
        const replyTo = stringFlag(flags, "--reply-to");
        const positionals = parsed.positionals;
        if (explicitTo === undefined && positionals.length === 0) {
          throw new PhoneBridgeError(
            "bad_input",
            "usage: phonebridge reply [peer|msg_id] <text…> | --to <peer> <text…>",
          );
        }
        let target: string | undefined = explicitTo;
        let text = positionals.join(" ");
        if (explicitTo === undefined && positionals.length >= 2) {
          const first = positionals[0] as string;
          const cached = (await InboxStateStore.open({ home })).findById(first);
          const isPeer =
            cached === undefined &&
            (await matchesBotPeer(client, first, device, relayOverride(flags), timeoutMs));
          if (cached !== undefined || isPeer) {
            target = first;
            text = positionals.slice(1).join(" ");
          }
        }
        const message = await client.reply(text, {
          ...(target === undefined ? {} : { target }),
          ...(replyTo === undefined ? {} : { replyTo }),
          ...(device === undefined ? {} : { device }),
          ...(relayOverride(flags) === undefined ? {} : { relayUrl: relayOverride(flags) }),
          ...(timeoutMs === undefined ? {} : { timeoutMs }),
        });
        io.out(`${JSON.stringify({ ok: true, message }, null, 2)}\n`);
        return 0;
      }
      case "status":
        return printResult(await client.request("status", {}, requestOptions(device, timeoutMs)), io);
      case "screenshot": {
        const result = asRecord(
          await client.request(
            "screenshot",
            {
              ...(stringFlag(flags, "--format") === undefined
                ? {}
                : { format: stringFlag(flags, "--format") }),
              ...(numberFlag(flags, "--quality") === undefined
                ? {}
                : { quality: numberFlag(flags, "--quality") }),
              ...(numberFlag(flags, "--max-width") === undefined
                ? {}
                : { max_width: numberFlag(flags, "--max-width") }),
            },
            requestOptions(device, timeoutMs),
          ),
        );
        const data = typeof result["data_b64"] === "string" ? result["data_b64"] : "";
        if (data === "") throw new PhoneBridgeError("internal", "screenshot response has no data_b64");
        const mime = typeof result["mime"] === "string" ? result["mime"] : "image/png";
        const output = stringFlag(flags, "--output") ?? stringFlag(flags, "-o");
        if (output !== undefined) {
          const bytes = Buffer.from(fromBase64Loose(data));
          await writeFile(output, bytes);
          io.out(
            `${JSON.stringify(
              { ok: true, path: output, mime, width: result["width"], height: result["height"], bytes: bytes.length },
              null,
              2,
            )}\n`,
          );
        } else if (flags.has("--json")) {
          io.out(`${JSON.stringify({ ok: true, ...result }, null, 2)}\n`);
        } else {
          io.out(`${data}\n`);
        }
        return 0;
      }
      case "ui-tree":
      case "ui_tree":
        return printResult(
          await client.request(
            "ui_tree",
            {
              ...(numberFlag(flags, "--max-depth") === undefined
                ? {}
                : { max_depth: numberFlag(flags, "--max-depth") }),
              ...(flags.has("--visible-only") ? { visible_only: true } : {}),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      case "notifications":
        return printResult(
          await client.request(
            "notifications",
            {
              ...(numberFlag(flags, "--since") === undefined
                ? {}
                : { since: numberFlag(flags, "--since") }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      case "tap": {
        const node = stringFlag(flags, "--node");
        if (node !== undefined) {
          return printResult(
            await client.request("tap", { node_id: node }, requestOptions(device, timeoutMs)),
            io,
          );
        }
        const [x, y] = positionalNumbers(parsed.positionals, 2, "tap <x> <y>");
        return printResult(
          await client.request("tap", { x, y }, requestOptions(device, timeoutMs)),
          io,
        );
      }
      case "swipe": {
        const [x1, y1, x2, y2] = positionalNumbers(parsed.positionals, 4, "swipe <x1> <y1> <x2> <y2>");
        return printResult(
          await client.request(
            "swipe",
            {
              x1,
              y1,
              x2,
              y2,
              ...(numberFlag(flags, "--ms") === undefined ? {} : { ms: numberFlag(flags, "--ms") }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "long-press":
      case "long_press": {
        const [x, y] = positionalNumbers(parsed.positionals, 2, "long-press <x> <y>");
        return printResult(
          await client.request(
            "long_press",
            {
              x,
              y,
              ...(numberFlag(flags, "--ms") === undefined ? {} : { ms: numberFlag(flags, "--ms") }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "settings": {
        const [namespace, key, value] = parsed.positionals;
        if (namespace === undefined || key === undefined) {
          throw new PhoneBridgeError(
            "bad_input",
            "usage: phonebridge settings <system|secure|global> <key> [value]",
          );
        }
        return printResult(
          await client.request(
            "settings",
            { namespace, key, ...(value === undefined ? {} : { value }) },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "type": {
        const text = parsed.positionals.join(" ");
        if (text === "") throw new PhoneBridgeError("bad_input", "usage: phonebridge type <text>");
        return printResult(
          await client.request(
            "type",
            {
              text,
              ...(stringFlag(flags, "--node") === undefined ? {} : { node_id: stringFlag(flags, "--node") }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "key": {
        const key = parsed.positionals[0];
        if (key === undefined) throw new PhoneBridgeError("bad_input", "usage: phonebridge key <name>");
        return printResult(
          await client.request("key", { key }, requestOptions(device, timeoutMs)),
          io,
        );
      }
      case "open-app":
      case "open_app": {
        const pkg = parsed.positionals[0];
        const label = stringFlag(flags, "--label");
        if (pkg === undefined && label === undefined) {
          throw new PhoneBridgeError("bad_input", "usage: phonebridge open-app <package> | --label NAME");
        }
        return printResult(
          await client.request(
            "open_app",
            {
              ...(pkg === undefined ? {} : { package: pkg }),
              ...(label === undefined ? {} : { label }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "install-apk":
      case "install_apk": {
        const url = parsed.positionals[0];
        const file = stringFlag(flags, "--file");
        if (url === undefined && file === undefined) {
          throw new PhoneBridgeError("bad_input", "usage: phonebridge install-apk <url> | --file <apk>");
        }
        const args: Record<string, unknown> = {};
        if (url !== undefined) {
          args["url"] = url;
        } else if (file !== undefined) {
          const { readFile } = await import("node:fs/promises");
          const bytes = await readFile(file);
          args["data_b64"] = toBase64Url(bytes);
          args["name"] = file.split(/[\\/]/).pop() ?? "app.apk";
        }
        return printResult(
          await client.request("install_apk", args, {
            ...requestOptions(device, timeoutMs),
            timeoutMs: timeoutMs ?? 300_000,
          }),
          io,
        );
      }
      case "shell": {
        const commandText = parsed.positionals.join(" ").trim();
        if (commandText === "") {
          throw new PhoneBridgeError("bad_input", 'usage: phonebridge shell -- "command"');
        }
        return printResult(
          await client.request(
            "shell",
            {
              command: commandText,
              ...(timeoutMs === undefined ? {} : { timeout_ms: timeoutMs }),
            },
            requestOptions(device, timeoutMs),
          ),
          io,
        );
      }
      case "files": {
        const [op, path] = parsed.positionals;
        if (op === undefined || path === undefined) {
          throw new PhoneBridgeError("bad_input", "usage: phonebridge files <list|read|write|delete> <path>");
        }
        return printResult(
          await client.request(
            "files",
            {
              op,
              path,
              ...(stringFlag(flags, "--data-b64") === undefined
                ? {}
                : { data_b64: toBase64Url(fromBase64Loose(stringFlag(flags, "--data-b64") as string)) }),
            },
            { ...requestOptions(device, timeoutMs), timeoutMs: timeoutMs ?? 60_000 },
          ),
          io,
        );
      }
      case "relay-move":
      case "relay_move": {
        const to = parsed.positionals[0];
        if (to === undefined) throw new PhoneBridgeError("bad_input", "usage: phonebridge relay-move <this|peer|url>");
        return printResult(
          await client.request("relay_move", { to }, requestOptions(device, timeoutMs)),
          io,
        );
      }
      case "request-control":
      case "request_control":
        return printResult(
          await client.request("request_control", {}, requestOptions(device, timeoutMs)),
          io,
        );
      case "release-control":
      case "release_control":
        return printResult(
          await client.request("release_control", {}, requestOptions(device, timeoutMs)),
          io,
        );
      default:
        throw new PhoneBridgeError("bad_input", `unknown command: ${command}`);
    }
  } catch (error) {
    return reportError(error, io);
  } finally {
    await client.close();
  }
}

function requestOptions(device: string | undefined, timeoutMs: number | undefined): {
  device?: string;
  timeoutMs?: number;
} {
  return {
    ...(device === undefined ? {} : { device }),
    ...(timeoutMs === undefined ? {} : { timeoutMs }),
  };
}

function relayOverride(flags: Map<string, string | true>): string | undefined {
  return stringFlag(flags, "--relay") ?? stringFlag(flags, "-r");
}

async function matchesBotPeer(
  client: PhoneBridgeClient,
  ref: string,
  device: string | undefined,
  relayUrl: string | undefined,
  timeoutMs: number | undefined,
): Promise<boolean> {
  try {
    const peers = await client.listPeers({
      ...(device === undefined ? {} : { device }),
      ...(relayUrl === undefined ? {} : { relayUrl }),
      ...(timeoutMs === undefined ? {} : { timeoutMs }),
    });
    const wanted = ref.trim().toLowerCase();
    const fingerprint = ref.replace(/[^0-9a-zA-Z]/g, "").toLowerCase();
    return peers.some((peer) => {
      if (peer.peerId === wanted) return true;
      if (wanted.length >= 6 && peer.peerId.startsWith(wanted)) return true;
      if (peer.name.toLowerCase() === wanted) return true;
      return (
        fingerprint.length >= 8 &&
        peer.fingerprint.replace(/[^0-9a-zA-Z]/g, "").toLowerCase() === fingerprint
      );
    });
  } catch {
    return false;
  }
}

function stringFlag(flags: Map<string, string | true>, name: string): string | undefined {
  const value = flags.get(name);
  return typeof value === "string" ? value : undefined;
}

function numberFlag(flags: Map<string, string | true>, name: string): number | undefined {
  const value = stringFlag(flags, name);
  if (value === undefined) return undefined;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new PhoneBridgeError("bad_input", `${name} must be a number`);
  return parsed;
}

function parseLevel(value: string): PermissionLevel {
  const normalized = value.trim().toLowerCase();
  if (!(PERMISSION_LEVELS as readonly string[]).includes(normalized)) {
    throw new PhoneBridgeError("bad_input", `level must be one of ${PERMISSION_LEVELS.join(", ")}`);
  }
  return normalizeLevel(normalized);
}

function positionalNumbers(values: string[], count: number, usage: string): number[] {
  if (values.length < count) throw new PhoneBridgeError("bad_input", `usage: phonebridge ${usage}`);
  const numbers = values.slice(0, count).map((value) => Number(value));
  if (numbers.some((value) => !Number.isFinite(value))) {
    throw new PhoneBridgeError("bad_input", `usage: phonebridge ${usage} (numbers required)`);
  }
  return numbers;
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null) {
    throw new PhoneBridgeError("internal", "device returned a non-object result");
  }
  return value as Record<string, unknown>;
}

function printResult(value: unknown, io: CliIo): number {
  io.out(`${JSON.stringify({ ok: true, result: value }, null, 2)}\n`);
  return 0;
}

function reportError(error: unknown, io: CliIo): number {
  if (isPhoneBridgeError(error)) {
    io.err(`error [${error.code}]: ${error.message}\n`);
    return error.code === "bad_input" ? 2 : 1;
  }
  io.err(`error [internal]: ${error instanceof Error ? error.message : String(error)}\n`);
  return 1;
}

export async function main(argv = process.argv.slice(2)): Promise<void> {
  const code = await runCli(argv, {
    out: (text) => process.stdout.write(text),
    err: (text) => process.stderr.write(text),
  });
  process.exitCode = code;
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  void main();
}
