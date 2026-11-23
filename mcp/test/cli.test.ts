import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parseCliArgs, runCli, type CliIo } from "../src/cli/index.js";
import { MockRelay } from "./helpers/mockRelay.js";
import { MockDevice } from "./helpers/mockDevice.js";

function capture(): { io: CliIo; stdout: string[]; stderr: string[] } {
  const stdout: string[] = [];
  const stderr: string[] = [];
  return {
    io: { out: (text) => stdout.push(text), err: (text) => stderr.push(text) },
    stdout,
    stderr,
  };
}

async function tempHome(): Promise<string> {
  return mkdtemp(join(tmpdir(), "phonebridge-cli-"));
}

describe("CLI argument parsing", () => {
  it("splits the command, positionals and flags", () => {
    const parsed = parseCliArgs(["tap", "540", "1200", "--device", "pixel", "--json"]);
    expect(parsed.command).toBe("tap");
    expect(parsed.positionals).toEqual(["540", "1200"]);
    expect(parsed.flags.get("--device")).toBe("pixel");
    expect(parsed.flags.get("--json")).toBe(true);
  });

  it("supports --flag=value and the -- escape hatch", () => {
    const parsed = parseCliArgs(["shell", "--", "ls", "-la", "--color=never"]);
    expect(parsed.command).toBe("shell");
    expect(parsed.positionals).toEqual(["ls", "-la", "--color=never"]);
    expect(parseCliArgs(["screenshot", "--output=shot.png"]).flags.get("--output")).toBe("shot.png");
  });

  it("rejects unknown flags", () => {
    expect(() => parseCliArgs(["status", "--nope"])).toThrowError(/unknown option/);
  });
});

describe("CLI commands", () => {
  it("prints help and version", async () => {
    const help = capture();
    expect(await runCli(["--help"], help.io)).toBe(0);
    expect(help.stdout.join("")).toContain("phonebridge [options] <command>");
    const version = capture();
    expect(await runCli(["--version"], version.io)).toBe(0);
    expect(version.stdout.join("")).toMatch(/^\d+\.\d+\.\d+/);
  });

  it("lists an empty keystore and fails gracefully without a paired device", async () => {
    const home = await tempHome();
    const devices = capture();
    expect(await runCli(["--home", home, "devices"], devices.io)).toBe(0);
    expect((JSON.parse(devices.stdout.join("")) as Record<string, unknown>)["devices"]).toEqual([]);

    const status = capture();
    expect(await runCli(["--home", home, "status"], status.io)).toBe(1);
    expect(status.stderr.join("")).toContain("[not_paired]");
  });

  it("pairs, screenshots to a file and taps against a mock device", async () => {
    const relay = new MockRelay();
    await relay.listen();
    const home = await tempHome();
    const device = await MockDevice.create({ relayUrl: relay.url(), autoPair: true, level: "full" });
    const { link } = await device.createOffer(relay);
    try {
      const pair = capture();
      expect(await runCli(["--home", home, "pair", link, "--level", "full"], pair.io)).toBe(0);
      const paired = JSON.parse(pair.stdout.join("")) as Record<string, unknown>;
      expect(paired["ok"]).toBe(true);
      expect(paired["level"]).toBe("full");

      const shotFile = join(home, "shot.png");
      const shot = capture();
      expect(await runCli(["--home", home, "screenshot", "--output", shotFile], shot.io)).toBe(0);
      expect((await readFile(shotFile)).toString("utf8")).toBe("mock-png-bytes");
      expect((JSON.parse(shot.stdout.join("")) as Record<string, unknown>)["mime"]).toBe("image/png");

      const tap = capture();
      expect(await runCli(["--home", home, "tap", "540", "1200"], tap.io)).toBe(0);
      expect(device.received.some((entry) => entry.cmd === "tap" && entry.args["x"] === 540)).toBe(true);

      const key = capture();
      expect(await runCli(["--home", home, "key", "home"], key.io)).toBe(0);

      const shell = capture();
      expect(await runCli(["--home", home, "shell", "--", "echo", "hi"], shell.io)).toBe(0);
      const shellResult = JSON.parse(shell.stdout.join("")) as Record<string, unknown>;
      expect((shellResult["result"] as Record<string, unknown>)["stdout"]).toBe("ran echo hi");

      const tree = capture();
      expect(await runCli(["--home", home, "ui-tree", "--max-depth", "2"], tree.io)).toBe(0);

      const files = capture();
      expect(await runCli(["--home", home, "files", "list", "/sdcard"], files.io)).toBe(0);
      const filesResult = JSON.parse(files.stdout.join("")) as Record<string, unknown>;
      expect((filesResult["result"] as Record<string, unknown>)["op"]).toBe("list");
    } finally {
      await device.stop();
      await relay.close();
    }
  });

  it("prints the raw base64url screenshot when no output file is given", async () => {
    const relay = new MockRelay();
    await relay.listen();
    const home = await tempHome();
    const device = await MockDevice.create({ relayUrl: relay.url(), autoPair: true });
    const { link } = await device.createOffer(relay);
    try {
      await runCli(["--home", home, "pair", link], capture().io);
      const shot = capture();
      expect(await runCli(["--home", home, "screenshot"], shot.io)).toBe(0);
      expect(Buffer.from(shot.stdout.join("").trim(), "base64url").toString("utf8")).toBe(
        "mock-png-bytes",
      );
    } finally {
      await device.stop();
      await relay.close();
    }
  });

  it("reports usage errors with exit code 2", async () => {
    const home = await tempHome();
    const bad = capture();
    expect(await runCli(["--home", home, "tap", "1"], bad.io)).toBe(2);
    expect(bad.stderr.join("")).toContain("usage: phonebridge tap <x> <y>");

    const unknown = capture();
    expect(await runCli(["--home", home, "frobnicate"], unknown.io)).toBe(2);
    expect(unknown.stderr.join("")).toContain("unknown command");

    const noInput = capture();
    expect(await runCli(["--home", home, "pair"], noInput.io)).toBe(2);
  });
});
