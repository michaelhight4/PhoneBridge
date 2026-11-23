/**
 * Crypto primitives for the PhoneBridge client.
 *
 * Construction (v0.1, mirrored by :core and checked by test/vectors/handshake-v1.json):
 * - Long-term and ephemeral keys: X25519 (libsodium `crypto_box_keypair` / `crypto_scalarmult`).
 * - KDF: keyed BLAKE2b-512 (libsodium `crypto_generichash`) over the concatenated DH outputs,
 *   keyed with the handshake transcript hash.
 * - AEAD: XChaCha20-Poly1305 IETF with per-direction 64-bit counters in a 24-byte nonce.
 */
import _sodium from "libsodium-wrappers";
import { createHash, randomBytes as nodeRandomBytes } from "node:crypto";
import { base32Encode } from "./base32.js";

export type SodiumApi = typeof _sodium;

let readyPromise: Promise<SodiumApi> | null = null;

/** Resolves once the libsodium WASM module is ready. Safe to call repeatedly. */
export function sodium(): Promise<SodiumApi> {
  if (readyPromise === null) {
    readyPromise = _sodium.ready.then(() => _sodium);
  }
  return readyPromise;
}

export interface KeyPair {
  publicKey: Uint8Array;
  secretKey: Uint8Array;
}

export interface Identity extends KeyPair {
  peerId: string;
}

export function sha256(data: Uint8Array): Uint8Array {
  return new Uint8Array(createHash("sha256").update(data).digest());
}

export function randomBytes(length: number): Uint8Array {
  return new Uint8Array(nodeRandomBytes(length));
}

export async function generateKeyPair(): Promise<KeyPair> {
  const api = await sodium();
  const pair = api.crypto_box_keypair();
  return { publicKey: new Uint8Array(pair.publicKey), secretKey: new Uint8Array(pair.privateKey) };
}

export async function keyPairFromSecret(secretKey: Uint8Array): Promise<KeyPair> {
  const api = await sodium();
  if (secretKey.length !== api.crypto_scalarmult_SCALARBYTES) {
    throw new Error("X25519 secret key must be 32 bytes");
  }
  const publicKey = api.crypto_scalarmult_base(secretKey);
  return { publicKey: new Uint8Array(publicKey), secretKey: new Uint8Array(secretKey) };
}

/** X25519 scalar multiplication. */
export async function x25519(secretKey: Uint8Array, publicKey: Uint8Array): Promise<Uint8Array> {
  const api = await sodium();
  return new Uint8Array(api.crypto_scalarmult(secretKey, publicKey));
}

/** Keyed BLAKE2b; `key` must be 16..64 bytes when present. */
export async function blake2b(
  outLength: number,
  data: Uint8Array,
  key?: Uint8Array,
): Promise<Uint8Array> {
  const api = await sodium();
  const hash = api.crypto_generichash(outLength, data, key ?? null);
  return new Uint8Array(hash);
}

export async function seal(
  key: Uint8Array,
  nonce: Uint8Array,
  plaintext: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  const api = await sodium();
  return new Uint8Array(api.crypto_aead_xchacha20poly1305_ietf_encrypt(plaintext, aad, null, nonce, key));
}

export async function open(
  key: Uint8Array,
  nonce: Uint8Array,
  ciphertext: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  const api = await sodium();
  return new Uint8Array(api.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ciphertext, aad, nonce, key));
}

/** 24-byte XChaCha nonce: 16 zero bytes then a big-endian 64-bit counter. */
export function counterNonce(counter: number): Uint8Array {
  if (!Number.isSafeInteger(counter) || counter < 0) {
    throw new Error(`nonce counter out of range: ${counter}`);
  }
  const nonce = new Uint8Array(24);
  let value = counter;
  for (let i = 23; i >= 16; i -= 1) {
    nonce[i] = value & 0xff;
    value = Math.floor(value / 256);
  }
  return nonce;
}

/** peer_id = base32(first 16 bytes of SHA-256(public key)), lowercase, no padding. */
export function peerIdForPublicKey(publicKey: Uint8Array): string {
  return base32Encode(sha256(publicKey).subarray(0, 16));
}

/** User-facing fingerprint: first 8 base32 symbols, uppercase, grouped `ABCD-EFGH`. */
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

export const HELLO_PROOF_LABEL = "PhoneBridge/v1/hello-proof";
export const DATA_AAD_LABEL = "PhoneBridge/v1/data";
