import { createHash } from "node:crypto";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  encodePairingFile,
  encodePairingLink,
  normalizeRelayUrl,
  parsePairingInput,
  type RawPairingOffer,
} from "../src/client/pairing.js";
import { identityFromSecret, sha256 } from "../src/client/crypto.js";
import { toBase64Url, utf8 } from "../src/client/bytes.js";
import { makeCode, parseCode } from "../src/client/codes.js";
import { PhoneBridgeError } from "../src/client/errors.js";

const NOW = 1_900_000_000_000; // fixed clock for expiry checks
const RELAY = "wss://relay.example.test:8765";

async function testOffer(overrides: Partial<RawPairingOffer> = {}): Promise<RawPairingOffer> {
  const identity = await identityFromSecret(sha256(utf8("pairing-test/device")));
  return {
    v: 1,
    relay: RELAY,
    device_id: identity.peerId,
    device_pub: toBase64Url(identity.publicKey),
    secret: toBase64Url(sha256(utf8("pairing-test/secret"))),
    code: makeCode("7K4Q9"),
    exp: Math.floor((NOW + 5 * 60_000) / 1000),
    ...overrides,
  };
}

describe("pairing short codes", () => {
  it("formats and validates 3-3 Crockford codes", () => {
    const code = makeCode("7K4Q9");
    expect(code).toBe("7K4-Q9Y");
    expect(parseCode(code.toLowerCase()).code).toBe(code);
    expect(parseCode("7K4Q9Y").compact).toBe("7K4Q9Y");
  });

  it("rejects a code with a bad check symbol", () => {
    expect(() => parseCode("7K4-Q9M")).toThrowError(PhoneBridgeError);
    try {
      parseCode("7K4-Q9M");
    } catch (error) {
      expect((error as PhoneBridgeError).code).toBe("bad_code");
    }
  });

  it("rejects codes with illegal symbols", () => {
    expect(() => parseCode("7K4-Q9U")).toThrowError(/invalid pairing code|checksum/);
  });
});

describe("pairing input parsing", () => {
  it("parses a phonebridge:// link", async () => {
    const offer = await testOffer();
    const parsed = await parsePairingInput(encodePairingLink(offer), { now: NOW });
    expect(parsed.kind).toBe("offer");
    if (parsed.kind !== "offer") return;
    expect(parsed.source).toBe("link");
    expect(parsed.offer.relay).toBe(RELAY);
    expect(parsed.offer.deviceId).toBe(offer.device_id);
    expect(parsed.offer.code).toBe(offer.code);
    expect(parsed.offer.secret).toHaveLength(32);
    expect(parsed.offer.expiresAt).toBe(offer.exp * 1000);
  });

  it("parses raw offer JSON and a wrapped {offer} object", async () => {
    const offer = await testOffer();
    const direct = await parsePairingInput(JSON.stringify(offer), { now: NOW });
    expect(direct.kind).toBe("offer");
    if (direct.kind === "offer") expect(direct.source).toBe("json");

    const wrapped = await parsePairingInput(JSON.stringify({ offer }), { now: NOW });
    expect(wrapped.kind).toBe("offer");
  });

  it("reads a .pbpair file", async () => {
    const offer = await testOffer();
    const dir = await mkdtemp(join(tmpdir(), "pbpair-"));
    const file = join(dir, "phone.pbpair");
    await writeFile(file, encodePairingFile(offer), "utf8");
    const parsed = await parsePairingInput(file, { now: NOW });
    expect(parsed.kind).toBe("offer");
    if (parsed.kind !== "offer") return;
    expect(parsed.source).toBe("file");
    expect(parsed.offer.deviceId).toBe(offer.device_id);
  });

  it("parses a short code with an explicit relay URL", async () => {
    const parsed = await parsePairingInput("7K4-Q9Y", { relayUrl: RELAY });
    expect(parsed.kind).toBe("code");
    if (parsed.kind !== "code") return;
    expect(parsed.code).toBe("7K4-Q9Y");
    expect(parsed.relayUrl).toBe(RELAY);
  });

  it("falls back to $PHONEBRIDGE_RELAY for a short code", async () => {
    const previous = process.env["PHONEBRIDGE_RELAY"];
    process.env["PHONEBRIDGE_RELAY"] = RELAY;
    try {
      const parsed = await parsePairingInput("7K4-Q9Y");
      expect(parsed.kind).toBe("code");
      if (parsed.kind === "code") expect(parsed.relayUrl).toBe(RELAY);
    } finally {
      if (previous === undefined) delete process.env["PHONEBRIDGE_RELAY"];
      else process.env["PHONEBRIDGE_RELAY"] = previous;
    }
  });

  it("rejects an expired offer with code expired", async () => {
    const offer = await testOffer();
    const stale = { ...offer, exp: Math.floor((NOW - 60_000) / 1000) };
    await expect(parsePairingInput(JSON.stringify(stale), { now: NOW })).rejects.toMatchObject({
      code: "expired",
    });
  });

  it("rejects an offer whose device_id does not hash to device_pub", async () => {
    const offer = await testOffer();
    const other = await identityFromSecret(sha256(utf8("pairing-test/other-device")));
    await expect(
      parsePairingInput(JSON.stringify({ ...offer, device_id: other.peerId }), { now: NOW }),
    ).rejects.toMatchObject({ code: "bad_offer" });
  });

  it("rejects an unsupported offer version and broken links", async () => {
    const offer = await testOffer();
    await expect(
      parsePairingInput(JSON.stringify({ ...offer, v: 2 }), { now: NOW }),
    ).rejects.toMatchObject({ code: "bad_offer" });
    await expect(parsePairingInput("phonebridge://pair", { now: NOW })).rejects.toMatchObject({
      code: "bad_input",
    });
    await expect(parsePairingInput("not a pairing input", { now: NOW })).rejects.toMatchObject({
      code: "bad_input",
    });
  });

  it("rejects a secret that is not 32 bytes", async () => {
    const offer = await testOffer();
    const bad = { ...offer, secret: Buffer.from("short").toString("base64url") };
    await expect(parsePairingInput(JSON.stringify(bad), { now: NOW })).rejects.toMatchObject({
      code: "bad_offer",
    });
  });

  it("accepts ws:// on loopback but refuses it for remote hosts", async () => {
    expect(normalizeRelayUrl("ws://127.0.0.1:8765")).toBe("ws://127.0.0.1:8765");
    expect(normalizeRelayUrl("ws://localhost:8765")).toBe("ws://localhost:8765");
    expect(() => normalizeRelayUrl("ws://relay.example.test:8765")).toThrowError(PhoneBridgeError);
    expect(normalizeRelayUrl("ws://relay.example.test:8765", true)).toBe(
      "ws://relay.example.test:8765",
    );
    expect(() => normalizeRelayUrl("https://relay.example.test")).toThrowError(PhoneBridgeError);
  });

  it("rejects an offer with a non-wss relay on a remote host", async () => {
    const offer = await testOffer({ relay: "ws://relay.example.test:8765" });
    await expect(parsePairingInput(JSON.stringify(offer), { now: NOW })).rejects.toMatchObject({
      code: "bad_input",
    });
  });

  it("produces a stable link payload", async () => {
    const offer = await testOffer();
    const link = encodePairingLink(offer);
    const payload = link.slice("phonebridge://pair?d=".length);
    const decoded = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as RawPairingOffer;
    expect(decoded).toEqual(offer);
    expect(createHash("sha256").update(encodePairingFile(offer)).digest("hex")).toHaveLength(64);
  });
});
