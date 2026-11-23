import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { Keystore, keystorePath, defaultHome } from "../src/client/keystore.js";
import { PhoneBridgeError } from "../src/client/errors.js";
import { createIdentity, sha256 } from "../src/client/crypto.js";
import { toBase64Url, utf8 } from "../src/client/bytes.js";
import type { StoredDevice } from "../src/client/keystore.js";

async function home(): Promise<string> {
  return mkdtemp(join(tmpdir(), "phonebridge-keystore-"));
}

function deviceRecord(peerId: string, name: string, level: "view" | "control" | "full"): StoredDevice {
  return {
    peerId,
    name,
    publicKey: toBase64Url(sha256(utf8(`device-${name}`))),
    relayUrl: "wss://relay.example.test:8765",
    level,
    pairedAt: new Date("2026-01-01T00:00:00Z").toISOString(),
  };
}

describe("keystore", () => {
  it("creates a keystore with a stable long-term identity", async () => {
    const dir = await home();
    const store = await Keystore.open({ home: dir });
    const identity = await store.identity();
    expect(identity.peerId).toHaveLength(26);
    expect(store.botFingerprint).toMatch(/^[0-9A-Z]{4}-[0-9A-Z]{4}$/);

    const reopened = await Keystore.open({ home: dir });
    const again = await reopened.identity();
    expect(toBase64Url(again.publicKey)).toBe(toBase64Url(identity.publicKey));
    expect(again.peerId).toBe(identity.peerId);

    const raw = JSON.parse(await readFile(keystorePath(dir), "utf8")) as {
      version: number;
      identity: { secretKey: string };
      devices: unknown[];
    };
    expect(raw.version).toBe(1);
    expect(raw.identity.secretKey).toBe(toBase64Url(identity.secretKey));
    expect(raw.devices).toEqual([]);
  });

  it("defaults to $PHONEBRIDGE_HOME when set", async () => {
    const dir = await home();
    const previous = process.env["PHONEBRIDGE_HOME"];
    process.env["PHONEBRIDGE_HOME"] = dir;
    try {
      expect(defaultHome()).toBe(dir);
      expect(keystorePath()).toBe(join(dir, "keystore.json"));
    } finally {
      if (previous === undefined) delete process.env["PHONEBRIDGE_HOME"];
      else process.env["PHONEBRIDGE_HOME"] = previous;
    }
  });

  it("stores, resolves and forgets devices", async () => {
    const dir = await home();
    const store = await Keystore.open({ home: dir });
    const first = deviceRecord("abcdefghijklmnopqrstuvwxyz", "Pixel 9", "control");
    const second = deviceRecord("zyxwvutsrqponmlkjihgfedcba", "Tablet", "view");

    store.upsertDevice(first);
    store.upsertDevice(second);
    await store.save();

    expect(store.resolve()).toMatchObject({ peerId: second.peerId });
    expect(store.resolve("Pixel 9").peerId).toBe(first.peerId);
    expect(store.resolve("pixel 9").peerId).toBe(first.peerId);
    expect(store.resolve("abcdef").peerId).toBe(first.peerId);
    expect(store.resolve("ABCD-EFGH").peerId).toBe(first.peerId);
    expect(() => store.resolve("nope")).toThrowError(PhoneBridgeError);

    store.setCurrent(first.peerId);
    expect(store.resolve().peerId).toBe(first.peerId);
    store.touchDevice(first.peerId, "2026-02-02T00:00:00.000Z");
    expect(store.resolve(first.peerId).lastSeenAt).toBe("2026-02-02T00:00:00.000Z");

    expect(store.removeDevice(first.peerId)).toBe(true);
    expect(store.removeDevice(first.peerId)).toBe(false);
    await store.save();

    const reopened = await Keystore.open({ home: dir });
    expect(reopened.devices().map((device) => device.name)).toEqual(["Tablet"]);
  });

  it("reports ambiguous references instead of guessing", async () => {
    const dir = await home();
    const store = await Keystore.open({ home: dir });
    store.upsertDevice(deviceRecord("aaaaaaaaaaaaaaaaaaaaaaaaaa", "Same name", "view"));
    store.upsertDevice(deviceRecord("bbbbbbbbbbbbbbbbbbbbbbbbbb", "Same name", "view"));
    expect(() => store.resolve("Same name")).toThrowError(/matches several devices/);
    store.upsertDevice(deviceRecord("cccccccccccccccccccccccccc", "Third", "view"));
    // The most recently paired device becomes the default target.
    expect(store.resolve().name).toBe("Third");
  });

  it("says not_paired when nothing is stored", async () => {
    const store = await Keystore.open({ home: await home() });
    expect(() => store.resolve()).toThrowError(/no paired device/);
    try {
      store.resolve();
    } catch (error) {
      expect((error as PhoneBridgeError).code).toBe("not_paired");
    }
  });

  it("refuses a corrupt keystore instead of overwriting it", async () => {
    const dir = await home();
    await writeFile(join(dir, "keystore.json"), JSON.stringify({ version: 9 }), "utf8");
    await expect(Keystore.open({ home: dir })).rejects.toMatchObject({ code: "bad_input" });
  });

  it("keeps the given identity key when the file already has one", async () => {
    const dir = await home();
    const identity = await createIdentity();
    await writeFile(
      join(dir, "keystore.json"),
      JSON.stringify({
        version: 1,
        identity: {
          publicKey: toBase64Url(identity.publicKey),
          secretKey: toBase64Url(identity.secretKey),
          peerId: identity.peerId,
          createdAt: "2026-01-01T00:00:00.000Z",
        },
        devices: [],
      }),
      "utf8",
    );
    const store = await Keystore.open({ home: dir });
    expect((await store.identity()).peerId).toBe(identity.peerId);
  });
});
