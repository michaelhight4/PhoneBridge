/**
 * Short pairing codes, e.g. `7K4-Q9B`, exactly as `core/` implements them.
 *
 * Six Crockford base32 symbols: five data symbols (25 bits) plus one check symbol. The check symbol is
 * a CRC-5 over the 25 data bits, MSB first, polynomial 0x05 (x^5 + x^2 + 1), initial value 0x1F, no
 * reflection, no final XOR. It catches any single wrong symbol.
 */
import { BASE32_ALPHABET } from "./base32.js";
import { PhoneBridgeError } from "./errors.js";

const ALPHABET = BASE32_ALPHABET.toUpperCase();
const DATA_SYMBOLS = 5;
const SYMBOLS = 6;
const CRC_INIT = 0x1f;
const CRC_POLY = 0x05;
const CRC_MASK = 0x1f;

/** Uppercase, separators dropped, O→0, I/L→1 (Crockford's ambiguity fixes). `U` stays invalid. */
export function normalizeCode(input: string): string {
  let out = "";
  for (const raw of input) {
    if (raw === "-" || raw === " " || raw === "\t" || raw === "\n" || raw === "\r" || raw === "_" || raw === ".") {
      continue;
    }
    const upper = raw.toUpperCase();
    out += upper === "O" ? "0" : upper === "I" || upper === "L" ? "1" : upper;
  }
  return out;
}

export function symbolValue(symbol: string): number {
  return ALPHABET.indexOf(symbol.toUpperCase());
}

export function symbolFor(value: number): string {
  if (!Number.isInteger(value) || value < 0 || value >= ALPHABET.length) {
    throw new PhoneBridgeError("bad_input", `bad symbol value: ${value}`);
  }
  return ALPHABET[value] as string;
}

/** CRC-5 over the five data symbols' 25 bits, MSB first. */
export function checkValue(dataSymbols: string): number {
  if (dataSymbols.length !== DATA_SYMBOLS) {
    throw new PhoneBridgeError("bad_input", `need ${DATA_SYMBOLS} data symbols`);
  }
  let crc = CRC_INIT;
  for (const symbol of dataSymbols) {
    const value = symbolValue(symbol);
    if (value < 0) throw new PhoneBridgeError("bad_code", `invalid pairing code symbol: ${symbol}`);
    for (let bit = 4; bit >= 0; bit -= 1) {
      const incoming = (value >>> bit) & 1;
      const top = (crc >>> 4) & 1;
      crc = (crc << 1) & CRC_MASK;
      if ((top ^ incoming) !== 0) crc ^= CRC_POLY;
    }
  }
  return crc;
}

export function checkSymbol(dataSymbols: string): string {
  return symbolFor(checkValue(dataSymbols));
}

/** Display form with the dash after three symbols: `7K4-Q9B`. */
export function formatCode(input: string): string {
  const normalised = normalizeCode(input);
  if (normalised.length !== SYMBOLS) return normalised;
  return `${normalised.slice(0, 3)}-${normalised.slice(3)}`;
}

export function isValidCode(input: string): boolean {
  const normalised = normalizeCode(input);
  if (normalised.length !== SYMBOLS) return false;
  for (const symbol of normalised) {
    if (symbolValue(symbol) < 0) return false;
  }
  return checkSymbol(normalised.slice(0, DATA_SYMBOLS)) === normalised.slice(DATA_SYMBOLS);
}

/** Validates a code and returns it in display form and bare form. */
export function parseCode(raw: string): { code: string; compact: string } {
  const normalised = normalizeCode(raw);
  if (normalised.length !== SYMBOLS) {
    throw new PhoneBridgeError("bad_code", `pairing code must have 6 symbols, got "${raw}"`);
  }
  for (const symbol of normalised) {
    if (symbolValue(symbol) < 0) {
      throw new PhoneBridgeError("bad_code", `invalid pairing code symbol: ${symbol}`);
    }
  }
  const data = normalised.slice(0, DATA_SYMBOLS);
  const actual = normalised.slice(DATA_SYMBOLS);
  const expected = checkSymbol(data);
  if (expected !== actual) {
    throw new PhoneBridgeError("bad_code", `pairing code checksum failed for "${formatCode(normalised)}"`, {
      expectedCheck: expected,
    });
  }
  return { code: formatCode(normalised), compact: normalised };
}

/** Builds a code from five data symbols, computing the check symbol. */
export function makeCode(data: string): string {
  const upper = normalizeCode(data);
  if (upper.length !== DATA_SYMBOLS) {
    throw new PhoneBridgeError("bad_input", "pairing code data must be 5 symbols");
  }
  for (const symbol of upper) {
    if (symbolValue(symbol) < 0) {
      throw new PhoneBridgeError("bad_input", `invalid pairing code symbol: ${symbol}`);
    }
  }
  return formatCode(upper + checkSymbol(upper));
}

/** Generates a random code (used by devices and docs examples). */
export function randomCode(random: (length: number) => Uint8Array = () => new Uint8Array(5)): string {
  const bytes = random(DATA_SYMBOLS);
  let data = "";
  for (const byte of bytes) data += ALPHABET[byte % ALPHABET.length] as string;
  return makeCode(data);
}
