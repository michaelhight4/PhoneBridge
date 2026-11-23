/**
 * Pairing input parsing.
 *
 * Accepted forms (all produce the same offer):
 *   - link:  phonebridge://pair?d=<base64url(offer JSON)>
 *   - file:  a `.pbpair` file (or a path to one) containing the offer JSON
 *   - JSON:  the offer object itself, pasted as text
 *   - code:  `7K4-Q9M` plus a relay URL (flag, env `PHONEBRIDGE_RELAY`, or the default LAN relay)
 *
 * The offer is one-time and expires 10 minutes after the device created it.
 */
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { fromBase64, fromBase64Url, toBase64Url } from "./bytes.js";
import { peerIdForPublicKey } from "./crypto.js";
import { parseCode } from "./codes.js";
import { PhoneBridgeError } from "./errors.js";

export const OFFER_VERSION = 1;
export const OFFER_TTL_MS = 10 * 60 * 1000;

export interface RawPairingOffer {
  v: number;
  relay: string;
  device_id: string;
  device_pub: string;
  secret: string;
  code?: string;
  exp: number;
  relay_fp?: string;
  device_name?: string;
  name?: string;
}

export interface PairingOffer {
  relay: string;
  deviceId: string;
  devicePublicKey: Uint8Array;
  secret: Uint8Array;
  code: string;
  expiresAt: number;
  relayFingerprint: string | undefined;
  deviceName: string | undefined;
  raw: RawPairingOffer;
}

export type PairingInputSource = "link" | "json" | "file" | "code";

export type ParsedPairingInput =
  | { kind: "offer"; offer: PairingOffer; source: Exclude<PairingInputSource, "code"> }
  | { kind: "code"; code: string; relayUrl: string; source: "code" };

export interface ParseOptions {
  /** Relay URL used when the input is a bare short code. */
  relayUrl?: string;
  /** Allow `ws://` relays (loopback and tests only). */
  insecureDev?: boolean;
  /** Test hook: treat this as "now" (ms since epoch). */
  now?: number;
  /** Test hook: skip the expiry check. */
  allowExpired?: boolean;
}

const LINK_PREFIX = "phonebridge://";

export function encodePairingLink(offer: RawPairingOffer): string {
  const json = Buffer.from(JSON.stringify(offer), "utf8").toString("base64url");
  return `${LINK_PREFIX}pair?d=${json}`;
}

export function encodePairingFile(offer: RawPairingOffer): string {
  return `${JSON.stringify(offer, null, 2)}\n`;
}

/** Parses any supported pairing input. Throws PhoneBridgeError with `bad_input`/`bad_offer`/`expired`. */
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
  if (/^[0-9A-Za-z]{3}-?[0-9A-Za-z]{3}$/.test(value)) {
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
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "phonebridge:" || (host !== "pair" && url.hostname !== "")) {
    throw new PhoneBridgeError("bad_input", `unexpected pairing link target: ${url.protocol}//${host}`);
  }
  // URL parses phonebridge://pair?d=... with hostname "pair" and, for phonebridge:///pair, pathname /pair.
  const payload = url.searchParams.get("d");
  if (payload === null || payload === "") {
    throw new PhoneBridgeError("bad_input", "pairing link has no d= payload");
  }
  let json: string;
  try {
    json = Buffer.from(payload, "base64url").toString("utf8");
  } catch {
    throw new PhoneBridgeError("bad_input", "pairing link payload is not base64url");
  }
  return parseOfferJson(json, options);
}

export async function parseOfferJson(json: string, options: ParseOptions = {}): Promise<PairingOffer> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json) as unknown;
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
      : typeof wrapper["pairing"] === "object" && wrapper["pairing"] !== null
        ? (wrapper["pairing"] as Record<string, unknown>)
        : wrapper;

  const v = candidate["v"];
  if (v !== OFFER_VERSION) {
    throw new PhoneBridgeError("bad_offer", `unsupported pairing offer version: ${String(v)}`, {
      supported: OFFER_VERSION,
    });
  }

  const relay = requireString(candidate, "relay");
  const deviceId = requireString(candidate, "device_id").toLowerCase();
  const devicePublicKey = decodeKey(candidate["device_pub"], "device_pub");
  const secret = decodeKey(candidate["secret"], "secret");
  const expiresAt = requireExpiry(candidate["exp"]);
  const relayFingerprint = optionalString(candidate["relay_fp"]);
  const deviceName = optionalString(candidate["device_name"]) ?? optionalString(candidate["name"]);

  const codeValue = optionalString(candidate["code"]);
  const code = codeValue === undefined ? "" : parseCode(codeValue).code;

  if (options.now !== undefined && options.allowExpired !== true && expiresAt <= options.now) {
    throw new PhoneBridgeError("expired", "pairing offer has expired; create a new one on the phone", {
      expiresAt,
    });
  }

  const expectedId = peerIdForPublicKey(devicePublicKey);
  if (deviceId !== expectedId) {
    throw new PhoneBridgeError(
      "bad_offer",
      "pairing offer device_id does not match the hash of device_pub",
      { expected: expectedId },
    );
  }

  normalizeRelayUrl(relay, options.insecureDev);

  const raw: RawPairingOffer = {
    v: OFFER_VERSION,
    relay,
    device_id: deviceId,
    device_pub: toBase64Url(devicePublicKey),
    secret: toBase64Url(secret),
    exp: Math.floor(expiresAt / 1000),
    ...(code === "" ? {} : { code }),
    ...(relayFingerprint === undefined ? {} : { relay_fp: relayFingerprint }),
    ...(deviceName === undefined ? {} : { device_name: deviceName }),
  };

  return { relay, deviceId, devicePublicKey, secret, code, expiresAt, relayFingerprint, deviceName, raw };
}

function parseShortCode(value: string, options: ParseOptions): ParsedPairingInput {
  const { code } = parseCode(value);
  const relayUrl =
    options.relayUrl ??
    process.env["PHONEBRIDGE_RELAY"] ??
    "wss://relay.phonebridge.local:8765";
  normalizeRelayUrl(relayUrl, options.insecureDev);
  return { kind: "code", code, relayUrl, source: "code" };
}

/** Rebuilds a full offer from a relay `pair_offer` reply (short-code path). */
export async function offerFromRelayReply(
  payloadJson: string,
  options: ParseOptions = {},
): Promise<PairingOffer> {
  return parseOfferJson(payloadJson, options);
}

function requireString(record: Record<string, unknown>, field: string): string {
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
  // Values below 1e12 are seconds; larger values are already milliseconds (tolerated).
  return seconds < 1e12 ? seconds * 1000 : seconds;
}

function decodeKey(value: unknown, field: string): Uint8Array {
  if (typeof value !== "string" || value === "") {
    throw new PhoneBridgeError("bad_offer", `pairing offer is missing ${field}`);
  }
  let bytes = fromBase64Url(value);
  if (bytes.length !== 32) {
    try {
      bytes = fromBase64(value);
    } catch {
      /* fall through to the length error */
    }
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

/** Removes the payload of a pairing link/offer so it can be logged safely. */
export function redact(value: string): string {
  if (value.length <= 24) return value;
  return `${value.slice(0, 12)}…${value.slice(-6)}`;
}

export function isPairingLink(value: string): boolean {
  return value.trim().toLowerCase().startsWith(LINK_PREFIX);
}
