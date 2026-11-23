/**
 * Pairing short codes: `7K4-Q9M` — Crockford base32, 5 data symbols plus one check symbol.
 *
 * Checksum (concretized for v0.1, the same in :core):
 *   check = (v0 + v1 + v2 + v3 + v4) mod 32, encoded with the Crockford alphabet.
 */
import { BASE32_ALPHABET, base32Decode, base32Encode } from "./base32.js";
import { PhoneBridgeError } from "./errors.js";

const FORMATTED = /^[0-9A-Za-z]{3}-[0-9A-Za-z]{3}$/;
const COMPACT = /^[0-9A-Za-z]{6}$/;

export function normalizeCode(code: string): string {
  return code.trim().toUpperCase();
}

function symbolValue(char: string): number {
  const index = BASE32_ALPHABET.indexOf(char.toLowerCase());
  if (index < 0) throw new PhoneBridgeError("bad_code", `invalid pairing code symbol: ${char}`);
  return index;
}

export function checkSymbol(data: string): string {
  let sum = 0;
  for (const char of data.toLowerCase()) sum += symbolValue(char);
  return (BASE32_ALPHABET[sum % 32] as string).toUpperCase();
}

/** Formats a raw code (`7K4Q9M` or `7K4-Q9M`) as `7K4-Q9M` after validating the checksum. */
export function parseCode(raw: string): { code: string; compact: string } {
  const value = normalizeCode(raw);
  if (!FORMATTED.test(value) && !COMPACT.test(value)) {
    throw new PhoneBridgeError("bad_code", `pairing code must look like 7K4-Q9M, got "${raw}"`);
  }
  const compact = value.replace("-", "");
  const data = compact.slice(0, 5);
  const expected = checkSymbol(data);
  const actual = compact.slice(5, 6);
  if (expected !== actual) {
    throw new PhoneBridgeError("bad_code", `pairing code checksum failed for "${value}"`, {
      expectedCheck: expected,
    });
  }
  return { code: `${data.slice(0, 3)}-${data.slice(3)}${actual}`, compact };
}

export function makeCode(data: string): string {
  if (data.length !== 5) throw new PhoneBridgeError("bad_input", "pairing code data must be 5 symbols");
  for (const char of data.toLowerCase()) symbolValue(char);
  const check = checkSymbol(data);
  return `${data.slice(0, 3).toUpperCase()}-${data.slice(3).toUpperCase()}${check}`;
}

/** Generates a random pairing code (used by device-side mocks and docs examples). */
export function randomCode(random: (n: number) => Uint8Array): string {
  const bytes = random(5);
  let data = "";
  for (const byte of bytes) data += BASE32_ALPHABET[byte % 32] as string;
  return makeCode(data.toUpperCase());
}

/** True when `code` is 5 data symbols + valid check symbol. */
export function isValidCode(code: string): boolean {
  try {
    parseCode(code);
    return true;
  } catch {
    return false;
  }
}

export { base32Decode, base32Encode };
