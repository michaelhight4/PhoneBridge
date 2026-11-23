/**
 * Crockford base32 (as used by the human pairing codes in docs/PROTOCOL.md).
 * Alphabet: 0-9 then A-Z without I, L, O, U. Decoding is case-insensitive and
 * maps the usual Crockford look-alikes (I/L -> 1, O -> 0).
 *
 * peer_id = base32(first 16 bytes of SHA-256(public key)), lowercase, no padding.
 */
const ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

const REVERSE = new Map<string, number>();
for (let i = 0; i < ALPHABET.length; i += 1) REVERSE.set(ALPHABET[i] as string, i);
REVERSE.set("i", 1);
REVERSE.set("l", 1);
REVERSE.set("o", 0);

export function base32Encode(bytes: Uint8Array): string {
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (const byte of bytes) {
    buffer = (buffer << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += ALPHABET[(buffer >>> bits) & 31] as string;
    }
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) {
    out += ALPHABET[(buffer << (5 - bits)) & 31] as string;
  }
  return out;
}

export function base32Decode(text: string): Uint8Array {
  const cleaned = text.toLowerCase().replace(/[\s-]/g, "");
  const out: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of cleaned) {
    const value = REVERSE.get(char);
    if (value === undefined) {
      throw new Error(`invalid base32 character: ${char}`);
    }
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      out.push((buffer >>> bits) & 0xff);
    }
    buffer &= (1 << bits) - 1;
  }
  return new Uint8Array(out);
}

export const BASE32_ALPHABET = ALPHABET;
