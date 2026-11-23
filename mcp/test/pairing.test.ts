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
import { makeCode, normalizeCode, parseCode, isValidCode } from "../src/client/codes.js";
import { PhoneBridgeError } from "../src/client/errors.js";

const NOW = 1_900_000_000_000;
const RELAY = "wss://relay.example.test:8765";
const RELAY_PIN = toBase64Url(sha256(utf8("relay-certificate")));

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
    device_name: "Pixel 9",
    ...overrides,
  };
}

describe("pairing short codes (CRC-5, as in :core)", () => {
  it("matches the documented examples", () => {
    expect(makeCode("7K4Q9")).toBe("7K4-Q9B");
    expect(makeCode("ZZZZZ")).toBe("ZZZ-ZZQ");
    expect(makeCode("00000")).toBe("000-00T");
    expect(parseCode("7k4-q9b").code).toBe("7K4-Q9B");
    expect(parseCode("7K4Q9B").compact).toBe("7K4Q9B");
  });

  it("applies Crockford ambiguity fixes and drops separators", () => {
    expect(normalizeCode(" 7k4 q9b ")).toBe("7K4Q9B");
    expect(normalizeCode("0O1IL")).toBe("00111");
    expect(normalizeCode("7K4-Q9B")).toBe("7K4Q9B");
  });

  it("rejects a wrong check symbol and illegal symbols", () => {
    expect(isValidCode("7K4-Q9B")).toBe(true);
    expect(isValidCode("7K4-Q9M")).toBe(false);
    expect(() => parseCode("7K4-Q9M")).toThrowError(PhoneBridgeError);
    try {
      parseCode("7K4-Q9M");
    } catch (error) {
      expect((error as PhoneBridgeError).code).toBe("bad_code");
    }
    // U is not part of the Crockford alphabet.
    expect(isValidCode("7K4-Q9U")).toBe(false);
    expect(parseCode("7K4Q9B").compact).toHaveLength(6);
  });
});

describe("pairing input parsing", () => {
  it("parses a phonebridge:// link", async () => {
    const offer = await testOffer({ relay_pin: RELAY_PIN });
    const parsed = await parsePairingInput(encodePairingLink(offer), { now: NOW });
    expect(parsed.kind).toBe("offer");
    if (parsed.kind !== "offer") return;
    expect(parsed.source).toBe("link");
    expect(parsed.offer.relay).toBe(RELAY);
    expect(parsed.offer.deviceId).toBe(offer.device_id);
    expect(parsed.offer.code).toBe(offer.code);
    expect(parsed.offer.secret).toHaveLength(32);
    expect(parsed.offer.relayPin).toBe(RELAY_PIN);
    expect(parsed.offer.expiresAt).toBe(offer.exp * 1000);
    expect(parsed.offer.deviceName).toBe("Pixel 9");
  });

  it("parses raw offer JSON and a wrapped {offer} object", async () => {
    const offer = await testOffer();
    const direct = await parsePairingInput(JSON.stringify(offer), { now: NOW });
    expect(direct.kind).toBe("offer");
    if (direct.kind === "offer") expect(direct.source).toBe("json");
    const wrapped = await parsePairingInput(JSON.stringify({ offer }), { now: NOW });
    expect(wrapped.kind).toBe("offer");
  });

  it("reads a .pbpair file, including a UTF-8 BOM", async () => {
    const offer = await testOffer();
    const dir = await mkdtemp(join(tmpdir(), "pbpair-"));
    const file = join(dir, "phone.pbpair");
    await writeFile(file, `\uFEFF${encodePairingFile(offer)}`, "utf8");
    const parsed = await parsePairingInput(file, { now: NOW });
    expect(parsed.kind).toBe("offer");
    if (parsed.kind !== "offer") return;
    expect(parsed.source).toBe("file");
    expect(parsed.offer.deviceId).toBe(offer.device_id);
  });

  it("parses a short code with an explicit relay URL", async () => {
    const parsed = await parsePairingInput("7K4-Q9B", { relayUrl: RELAY });
    expect(parsed.kind).toBe("code");
    if (parsed.kind !== "code") return;
    expect(parsed.code).toBe("7K4-Q9B");
    expect(parsed.relayUrl).toBe(RELAY);
  });

  it("falls back to $PHONEBRIDGE_RELAY for a short code", async () => {
    const previous = process.env["PHONEBRIDGE_RELAY"];
    process.env["PHONEBRIDGE_RELAY"] = RELAY;
    try {
      const parsed = await parsePairingInput("7K4-Q9B");
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

  it("rejects unsupported versions, broken links and bad keys", async () => {
    const offer = await testOffer();
    await expect(parsePairingInput(JSON.stringify({ ...offer, v: 2 }), { now: NOW })).rejects.toMatchObject({
      code: "bad_offer",
    });
    await expect(parsePairingInput("phonebridge://pair", { now: NOW })).rejects.toMatchObject({
      code: "bad_input",
    });
    await expect(parsePairingInput("not a pairing input", { now: NOW })).rejects.toMatchObject({
      code: "bad_input",
    });
    await expect(
      parsePairingInput(
        JSON.stringify({ ...offer, secret: Buffer.from("short").toString("base64url") }),
        { now: NOW },
      ),
    ).rejects.toMatchObject({ code: "bad_offer" });
    await expect(
      parsePairingInput(
        JSON.stringify({ ...offer, relay_pin: Buffer.from("short").toString("base64url") }),
        { now: NOW },
      ),
    ).rejects.toMatchObject({ code: "bad_offer" });
    const noCode = { ...offer } as Partial<RawPairingOffer>;
    delete noCode.code;
    await expect(parsePairingInput(JSON.stringify(noCode), { now: NOW })).rejects.toMatchObject({
      code: "bad_offer",
    });
  });

  it("accepts ws:// on loopback but refuses it for remote hosts", async () => {
    expect(normalizeRelayUrl("ws://127.0.0.1:8765")).toBe("ws://127.0.0.1:8765");
    expect(normalizeRelayUrl("ws://localhost:8765")).toBe("ws://localhost:8765");
    expect(() => normalizeRelayUrl("ws://relay.example.test:8765")).toThrowError(PhoneBridgeError);
    expect(normalizeRelayUrl("ws://relay.example.test:8765", true)).toBe("ws://relay.example.test:8765");
    expect(() => normalizeRelayUrl("https://relay.example.test")).toThrowError(PhoneBridgeError);
  });

  it("encodes a stable link payload and file body", async () => {
    const offer = await testOffer();
    const link = encodePairingLink(offer);
    const payload = link.slice("phonebridge://pair?d=".length);
    const decoded = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as RawPairingOffer;
    expect(decoded).toEqual(offer);
    expect(JSON.parse(encodePairingFile(offer))).toEqual(offer);
  });
});
