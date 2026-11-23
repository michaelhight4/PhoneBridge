/** Base64 / base64url helpers. Encoding is unpadded base64url; decoding is liberal. */
import { PhoneBridgeError } from "./errors.js";

export function toBase64Url(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64url");
}

/** Standard base64 (with padding), used by MCP image content and file writes. */
export function toBase64(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("base64");
}

/**
 * Decodes base64url, standard base64, padded or unpadded values and values with whitespace.
 * docs/PROTOCOL.md requires decoders to accept `+`/`/`, padding and whitespace from other tools.
 */
export function fromBase64Loose(text: string): Uint8Array {
  const cleaned = text
    .replace(/\s+/g, "")
    .replace(/-/g, "+")
    .replace(/_/g, "/")
    .replace(/=+$/, "");
  if (/[^A-Za-z0-9+/]/.test(cleaned)) {
    throw new PhoneBridgeError("bad_input", "value is not valid base64");
  }
  return new Uint8Array(Buffer.from(cleaned, "base64"));
}

export const fromBase64Url = fromBase64Loose;

/** Re-encodes any accepted base64 form as standard base64, for image content or files. */
export function toBase64Std(text: string): string {
  return toBase64(fromBase64Loose(text));
}

export function utf8(text: string): Uint8Array {
  return new Uint8Array(Buffer.from(text, "utf8"));
}

export function fromUtf8(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("utf8");
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  let total = 0;
  for (const part of parts) total += part.length;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

export function toHex(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("hex");
}

export function fromHex(text: string): Uint8Array {
  if (text.length % 2 !== 0 || /[^0-9a-fA-F]/.test(text)) {
    throw new Error(`invalid hex string: ${text}`);
  }
  return new Uint8Array(Buffer.from(text, "hex"));
}

export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  return diff === 0;
}
