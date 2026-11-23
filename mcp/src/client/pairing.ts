/**
 * Pairing offer parsing and the three transfer forms, per docs/PROTOCOL.md section 5.
 *
 *   link: phonebridge://pair?d=<base64url(UTF-8 offer JSON)>
 *   file: .pbpair, UTF-8 offer JSON (BOM tolerated)
 *   JSON: the offer object itself
 *   code: `7K4-Q9B` plus a relay URL (the bot asks the relay for the offer)
 */
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { fromBase64Loose, toBase64Url, utf8 } from "./bytes.js";
import { peerIdForPublicKey } from "./crypto.js";
import { formatCode, parseCode } from "./codes.js";
import { PhoneBridgeError } from "./errors.js";

export const OFFER_VERSION = 1;
export const OFFER_TTL_MS = 10 * 60 * 1000;

export interface RawPairingOffer {
  v: number;
  relay: string;
  device_id: string;
  device_pub: string;
  secret: string;
  code: string;
  exp: number;
  device_name?: string;
  /** Base64url SHA-256 of the relay's DER certificate (relay/PROTOCOL-RELAY.md section 0). */
  relay_pin?: string;
}

export interface PairingOffer {
  relay: string;
  deviceId: string;
  devicePublicKey: Uint8Array;
  secret: Uint8Array;
  code: string;
  expiresAt: number;
  relayPin: string | undefined;
  deviceName: string | undefined;
  raw: RawPairingOffer;
}

export type PairingInputSource = "link" | "json" | "file" | "code";

export type ParsedPairingInput =
  | { kind: "offer"; offer: PairingOffer; source: Exclude<PairingInputSource, "code"> }
  | { kind: "code"; code: string; relayUrl: string; source: "code" };

export interface ParseOptions {
  relayUrl?: string;
  insecureDev?: boolean;
  /** Test hook: treat this as "now" (ms since epoch). */
  now?: number;
  /** Test hook: skip the expiry check. */
  allowExpired?: boolean;
}

const LINK_PREFIX = "phonebridge://";

export function encodePairingLink(offer: RawPairingOffer): string {
  return `${LINK_PREFIX}pair?d=${toBase64Url(utf8(JSON.stringify(offer)))}`;
}

export function encodePairingFile(offer: RawPairingOffer): string {
  return `${JSON.stringify(offer, null, 2)}\n`;
}

export async function parsePairingInput(
  input: string,
  options: ParseOptions = {},
): Promise<ParsedPairingInput> {
  if (typeof input !== "string" || input.trim() === "") {
    throw new PhoneBridgeError("bad_input", "pairing input is empty");
  }
  const value = input.trim();

  if (value.toLowerCase().startsWith(LINK_PREFIX)) {
    return { kind: "offer", offer: await parseLink(value, options), source: "link" };
  }
  if (value.startsWith("{")) {
    return { kind: "offer", offer: await parseOfferJson(value, options), source: "json" };
  }
  if (looksLikeFile(value) && existsSync(value)) {
    const contents = await readFile(value, "utf8");
    return { kind: "offer", offer: await parseOfferJson(contents, options), source: "file" };
  }
  if (!value.includes("/") && !value.includes("\\") && !value.includes(".") && !/\s/.test(value)) {
    return parseShortCode(value, options);
  }
  throw new PhoneBridgeError(
    "bad_input",
    "pairing input is not a phonebridge:// link, a .pbpair file, an offer JSON object or a short code",
    { input: redact(value) },
  );
}

function looksLikeFile(value: string): boolean {
  return value.endsWith(".pbpair") || value.includes("/") || value.includes("\\");
}

async function parseLink(link: string, options: ParseOptions): Promise<PairingOffer> {
  let url: URL;
  try {
    url = new URL(link);
  } catch {
    throw new PhoneBridgeError("bad_input", "pairing link is not a valid URL");
  }
  if (url.protocol !== "phonebridge:") {
    throw new PhoneBridgeError("bad_input", `unexpected pairing link scheme: ${url.protocol}`);
  }
  const payload = url.searchParams.get("d");
  if (payload === null || payload === "") {
    throw new PhoneBridgeError("bad_input", "pairing link has no d= payload");
  }
  let json: string;
  try {
    json = Buffer.from(fromBase64Loose(payload)).toString("utf8");
  } catch {
    throw new PhoneBridgeError("bad_input", "pairing link payload is not base64url");
  }
  return parseOfferJson(json, options);
}

export async function parseOfferJson(json: string, options: ParseOptions = {}): Promise<PairingOffer> {
  // `.pbpair` files created on Windows may start with a UTF-8 BOM.
  const text = json.replace(/^\uFEFF/, "");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch {
    throw new PhoneBridgeError("bad_input", "pairing offer is not valid JSON");
  }
  if (typeof parsed !== "object" || parsed === null) {
    throw new PhoneBridgeError("bad_offer", "pairing offer must be a JSON object");
  }
  const wrapper = parsed as Record<string, unknown>;
  const candidate =
    typeof wrapper["offer"] === "object" && wrapper["offer"] !== null
      ? (wrapper["offer"] as Record<string, unknown>)
      : wrapper;

  if (candidate["v"] !== OFFER_VERSION) {
    throw new PhoneBridgeError("bad_offer", `unsupported pairing offer version: ${String(candidate["v"])}`, {
      supported: OFFER_VERSION,
    });
  }

  const relay = requireString(candidate, "relay");
  const deviceId = requireString(candidate, "device_id").toLowerCase();
  const devicePublicKey = decodeKey(candidate["device_pub"], "device_pub");
  const secret = decodeKey(candidate["secret"], "secret");
  const expiresAt = requireExpiry(candidate["exp"]);
  const relayPin = optionalString(candidate["relay_pin"]);
  const deviceName = optionalString(candidate["device_name"]);

  const codeValue = optionalString(candidate["code"]);
  if (codeValue === undefined) {
    throw new PhoneBridgeError("bad_offer", "pairing offer is missing code");
  }
  const code = parseCode(codeValue).code;

  if (options.now !== undefined && options.allowExpired !== true && expiresAt <= options.now) {
    throw new PhoneBridgeError("expired", "pairing offer has expired; create a new one on the phone", {
      expiresAt,
    });
  }

  const expectedId = peerIdForPublicKey(devicePublicKey);
  if (deviceId !== expectedId) {
    throw new PhoneBridgeError("bad_offer", "pairing offer device_id does not match the hash of device_pub", {
      expected: expectedId,
    });
  }

  normalizeRelayUrl(relay, options.insecureDev);

  if (relayPin !== undefined) {
    const pin = fromBase64Loose(relayPin);
    if (pin.length !== 32) {
      throw new PhoneBridgeError("bad_offer", "relay_pin must be a SHA-256 fingerprint", {
        length: pin.length,
      });
    }
  }

  const raw: RawPairingOffer = {
    v: OFFER_VERSION,
    relay,
    device_id: deviceId,
    device_pub: toBase64Url(devicePublicKey),
    secret: toBase64Url(secret),
    code,
    exp: Math.floor(expiresAt / 1000),
    ...(deviceName === undefined ? {} : { device_name: deviceName }),
    ...(relayPin === undefined ? {} : { relay_pin: relayPin }),
  };

  return {
    relay,
    deviceId,
    devicePublicKey,
    secret,
    code,
    expiresAt,
    relayPin,
    deviceName,
    raw,
  };
}

function parseShortCode(value: string, options: ParseOptions): ParsedPairingInput {
  const { code } = parseCode(value);
  const relayUrl =
    options.relayUrl ?? process.env["PHONEBRIDGE_RELAY"] ?? "wss://relay.phonebridge.local:8765";
  normalizeRelayUrl(relayUrl, options.insecureDev);
  return { kind: "code", code, relayUrl, source: "code" };
}

export function requireString(record: Record<string, unknown>, field: string): string {
  const value = record[field];
  if (typeof value !== "string" || value.trim() === "") {
    throw new PhoneBridgeError("bad_offer", `pairing offer is missing ${field}`);
  }
  return value;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() !== "" ? value : undefined;
}

function requireExpiry(value: unknown): number {
  const seconds = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(seconds) || seconds <= 0) {
    throw new PhoneBridgeError("bad_offer", "pairing offer is missing a valid exp (unix seconds)");
  }
  return seconds < 1e12 ? seconds * 1000 : seconds;
}

function decodeKey(value: unknown, field: string): Uint8Array {
  if (typeof value !== "string" || value === "") {
    throw new PhoneBridgeError("bad_offer", `pairing offer is missing ${field}`);
  }
  let bytes: Uint8Array;
  try {
    bytes = fromBase64Loose(value);
  } catch {
    throw new PhoneBridgeError("bad_offer", `${field} is not valid base64`);
  }
  if (bytes.length !== 32) {
    throw new PhoneBridgeError("bad_offer", `${field} must be 32 bytes`, { length: bytes.length });
  }
  return bytes;
}

export function normalizeRelayUrl(url: string, insecureDev = false): string {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new PhoneBridgeError("bad_input", `relay URL is not valid: ${redact(url)}`);
  }
  if (parsed.protocol === "wss:") return url;
  if (parsed.protocol === "ws:") {
    const host = parsed.hostname.replace(/^\[|\]$/g, "").toLowerCase();
    const loopback = host === "localhost" || host === "127.0.0.1" || host === "::1";
    if (loopback || insecureDev) return url;
    throw new PhoneBridgeError(
      "bad_input",
      "plain ws:// relays are only allowed on loopback or with --insecure-dev",
    );
  }
  throw new PhoneBridgeError("bad_input", `unsupported relay URL scheme: ${parsed.protocol}`);
}

/** Removes the pairing payload from a link/offer so it can be logged safely. */
export function redact(value: string): string {
  if (value.length <= 24) return value;
  return `${value.slice(0, 12)}…${value.slice(-6)}`;
}

export function isPairingLink(value: string): boolean {
  return value.trim().toLowerCase().startsWith(LINK_PREFIX);
}

export { formatCode };
