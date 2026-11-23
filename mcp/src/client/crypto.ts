/**
 * Symmetric and asymmetric primitives, matching the reference implementation in `core/`
 * (Bouncy Castle lightweight API) and docs/PROTOCOL.md:
 *
 * - X25519 key agreement (libsodium `crypto_scalarmult`)
 * - SHA-256, HMAC-SHA256, RFC 5869 HKDF-SHA256 and the Noise HKDF (node:crypto)
 * - ChaCha20-Poly1305 IETF with 12-byte nonces and 16-byte tags (libsodium)
 * - Noise transport nonce = 64-bit counter little-endian + four zero bytes
 */
import _sodium from "libsodium-wrappers";
import { createHash, createHmac, randomBytes as nodeRandomBytes } from "node:crypto";
import { base32Encode } from "./base32.js";
import { PhoneBridgeError } from "./errors.js";

export type SodiumApi = typeof _sodium;

export const KEY_BYTES = 32;
export const NONCE_BYTES = 12;
export const TAG_BYTES = 16;

let readyPromise: Promise<SodiumApi> | null = null;

/** Resolves once the libsodium WASM module is ready. Safe to call repeatedly. */
export function sodium(): Promise<SodiumApi> {
  if (readyPromise === null) readyPromise = _sodium.ready.then(() => _sodium);
  return readyPromise;
}

export interface KeyPair {
  publicKey: Uint8Array;
  secretKey: Uint8Array;
}

export interface Identity extends KeyPair {
  peerId: string;
}

export function sha256(...parts: Uint8Array[]): Uint8Array {
  const hash = createHash("sha256");
  for (const part of parts) hash.update(part);
  return new Uint8Array(hash.digest());
}

export function hmacSha256(key: Uint8Array, data: Uint8Array): Uint8Array {
  return new Uint8Array(createHmac("sha256", key).update(data).digest());
}

export function randomBytes(length: number): Uint8Array {
  return new Uint8Array(nodeRandomBytes(length));
}

/** RFC 5869 HKDF-SHA256 (extract + expand), identical to `Primitives.hkdf` in `core/`. */
export function hkdf(ikm: Uint8Array, salt: Uint8Array, info: Uint8Array, length: number): Uint8Array {
  if (length < 1 || length > 255 * KEY_BYTES) {
    throw new PhoneBridgeError("bad_input", `bad HKDF length: ${length}`);
  }
  const effectiveSalt = salt.length === 0 ? new Uint8Array(KEY_BYTES) : salt;
  const prk = hmacSha256(effectiveSalt, ikm);
  const out: Uint8Array = new Uint8Array(length);
  let block: Uint8Array = new Uint8Array(0);
  let written = 0;
  let counter = 1;
  while (written < length) {
    block = hmacSha256(prk, concat(block, info, new Uint8Array([counter])));
    const take = Math.min(block.length, length - written);
    out.set(block.subarray(0, take), written);
    written += take;
    counter += 1;
  }
  return out;
}

/** The Noise `HKDF(chaining_key, input_key_material, num_outputs)` (no info, 1-byte counters). */
export function noiseHkdf(
  chainingKey: Uint8Array,
  inputKeyMaterial: Uint8Array,
  numOutputs: number,
): Uint8Array[] {
  if (numOutputs < 1 || numOutputs > 3) throw new PhoneBridgeError("bad_input", "bad HKDF output count");
  const tempKey = hmacSha256(chainingKey, inputKeyMaterial);
  const outputs: Uint8Array[] = [];
  let previous: Uint8Array = new Uint8Array(0);
  for (let counter = 1; counter <= numOutputs; counter += 1) {
    previous = hmacSha256(tempKey, concat(previous, new Uint8Array([counter])));
    outputs.push(previous);
  }
  return outputs;
}

export async function generateKeyPair(): Promise<KeyPair> {
  const api = await sodium();
  const pair = api.crypto_box_keypair();
  return { publicKey: new Uint8Array(pair.publicKey), secretKey: new Uint8Array(pair.privateKey) };
}

export async function keyPairFromSecret(secretKey: Uint8Array): Promise<KeyPair> {
  const api = await sodium();
  if (secretKey.length !== api.crypto_scalarmult_SCALARBYTES) {
    throw new PhoneBridgeError("bad_input", "X25519 secret key must be 32 bytes");
  }
  return { publicKey: new Uint8Array(api.crypto_scalarmult_base(secretKey)), secretKey: new Uint8Array(secretKey) };
}

/** X25519 scalar multiplication. Rejects the all-zero output, like libsodium and `core/`. */
export async function x25519(secretKey: Uint8Array, publicKey: Uint8Array): Promise<Uint8Array> {
  const api = await sodium();
  if (secretKey.length !== 32 || publicKey.length !== 32) {
    throw new PhoneBridgeError("bad_input", "X25519 keys must be 32 bytes");
  }
  try {
    return new Uint8Array(api.crypto_scalarmult(secretKey, publicKey));
  } catch {
    throw new PhoneBridgeError("handshake_failed", "X25519 produced an invalid shared secret");
  }
}

/** ChaCha20-Poly1305 IETF seal: ciphertext with the 16-byte tag appended. */
export async function aeadSeal(
  key: Uint8Array,
  nonce: Uint8Array,
  associatedData: Uint8Array,
  plaintext: Uint8Array,
): Promise<Uint8Array> {
  const api = await sodium();
  requireKeyAndNonce(key, nonce);
  return new Uint8Array(
    api.crypto_aead_chacha20poly1305_ietf_encrypt(plaintext, associatedData, null, nonce, key),
  );
}

/** ChaCha20-Poly1305 IETF open; throws on a bad tag. */
export async function aeadOpen(
  key: Uint8Array,
  nonce: Uint8Array,
  associatedData: Uint8Array,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  const api = await sodium();
  requireKeyAndNonce(key, nonce);
  if (ciphertext.length < TAG_BYTES) throw new PhoneBridgeError("handshake_failed", "ciphertext too short");
  try {
    return new Uint8Array(
      api.crypto_aead_chacha20poly1305_ietf_decrypt(null, ciphertext, associatedData, nonce, key),
    );
  } catch {
    throw new PhoneBridgeError("handshake_failed", "authentication failed");
  }
}

/** Noise nonce: 64-bit counter little-endian followed by four zero bytes. */
export function noiseNonce(counter: number): Uint8Array {
  if (!Number.isSafeInteger(counter) || counter < 0) {
    throw new PhoneBridgeError("bad_input", `nonce counter out of range: ${counter}`);
  }
  const nonce = new Uint8Array(NONCE_BYTES);
  let value = counter;
  for (let i = 0; i < 8; i += 1) {
    nonce[i] = value % 256;
    value = Math.floor(value / 256);
  }
  return nonce;
}

/** peer_id = lowercase Crockford base32 of the first 16 bytes of SHA-256(public key). */
export function peerIdForPublicKey(publicKey: Uint8Array): string {
  return base32Encode(sha256(publicKey).subarray(0, 16));
}

/** User-facing fingerprint: the first 8 peer_id symbols, uppercased as `ABCD-EFGH`. */
export function fingerprintForPeerId(peerId: string): string {
  const first = peerId.slice(0, 8).toUpperCase();
  return `${first.slice(0, 4)}-${first.slice(4, 8)}`;
}

export async function createIdentity(): Promise<Identity> {
  const pair = await generateKeyPair();
  return { ...pair, peerId: peerIdForPublicKey(pair.publicKey) };
}

export async function identityFromSecret(secretKey: Uint8Array): Promise<Identity> {
  const pair = await keyPairFromSecret(secretKey);
  return { ...pair, peerId: peerIdForPublicKey(pair.publicKey) };
}

function concat(...parts: Uint8Array[]): Uint8Array {
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

function requireKeyAndNonce(key: Uint8Array, nonce: Uint8Array): void {
  if (key.length !== KEY_BYTES) throw new PhoneBridgeError("bad_input", "AEAD key must be 32 bytes");
  if (nonce.length !== NONCE_BYTES) throw new PhoneBridgeError("bad_input", "AEAD nonce must be 12 bytes");
}

export const NOISE_PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256";
export const NOISE_PROLOGUE_TEXT = "PhoneBridge/1/noise-xx";
