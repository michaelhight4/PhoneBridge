/**
 * PhoneBridge handshake v1 (bot side is the initiator).
 *
 * Wire messages travel as base64url-encoded JSON in relay `route` envelopes:
 *   hs1  bot -> device : { t:"hs1", e:<ephemeral X25519 pub>, n:<32B nonce>, bp:<bot static pub>, p?:1 }
 *   hs2  device -> bot : { t:"hs2", e:<ephemeral X25519 pub>, n:<32B nonce>, dp:<device static pub> }
 *   hs3  bot -> device : AEAD(k_b2d, counter 0) of { t:"hs3", th:<transcript hash> }
 *   hs4  device -> bot : AEAD(k_d2b, counter 0) of { t:"hs4", th:<transcript hash> }
 *
 * Key schedule:
 *   dh1 = X25519(e_bot, e_dev)
 *   dh2 = X25519(e_bot, s_dev)
 *   dh3 = X25519(s_bot, e_dev)
 *   dh4 = X25519(s_bot, s_dev)
 *   th  = SHA-256("PhoneBridge/v1/handshake" || flags || e_bot || n_bot || s_bot ||
 *                 e_dev || n_dev || s_dev || (pairing ? SHA-256(secret) : ""))
 *   okm = BLAKE2b-512(key=th, dh1 || dh2 || dh3 || dh4)   // libsodium crypto_generichash
 *   k_b2d = okm[0..32), k_d2b = okm[32..64)
 *
 * `p:1` marks a pairing handshake, where the 32-byte one-time pairing secret is mixed into `th`.
 * Later reconnects use the paired long-term keys with `p` absent.
 */
import { fromBase64Url, toBase64Url, concatBytes, utf8 } from "./bytes.js";
import { blake2b, generateKeyPair, randomBytes, type KeyPair, x25519, sha256 } from "./crypto.js";
import { PhoneBridgeError } from "./errors.js";

export const HANDSHAKE_DOMAIN = "PhoneBridge/v1/handshake";

export interface HandshakeHello {
  t: "hs1";
  e: string;
  n: string;
  bp: string;
  p?: 1;
}

export interface HandshakeReply {
  t: "hs2";
  e: string;
  n: string;
  dp: string;
}

export interface HandshakeFinish {
  t: "hs3" | "hs4";
  th: string;
}

export interface HandshakeKeys {
  transcriptHash: Uint8Array;
  ikm: Uint8Array;
  okm: Uint8Array;
  botToDevice: Uint8Array;
  deviceToBot: Uint8Array;
}

export interface HelloOptions {
  /** Test hook: use a fixed ephemeral key pair instead of a random one. */
  ephemeralKeyPair?: KeyPair;
  /** Test hook: use a fixed 32-byte nonce instead of a random one. */
  nonce?: Uint8Array;
  /** Set when the handshake authenticates with the one-time pairing secret. */
  pairing?: boolean;
}

export interface HelloResult<Message> {
  message: Message;
  ephemeralKeyPair: KeyPair;
  nonce: Uint8Array;
}

function randomNonce(): Uint8Array {
  return randomBytes(32);
}

export async function createInitiatorHello(
  botPublicKey: Uint8Array,
  options: HelloOptions = {},
): Promise<HelloResult<HandshakeHello>> {
  const ephemeralKeyPair = options.ephemeralKeyPair ?? (await generateKeyPair());
  const nonce = options.nonce ?? randomNonce();
  if (nonce.length !== 32) throw new PhoneBridgeError("handshake_failed", "handshake nonce must be 32 bytes");
  const message: HandshakeHello = {
    t: "hs1",
    e: toBase64Url(ephemeralKeyPair.publicKey),
    n: toBase64Url(nonce),
    bp: toBase64Url(botPublicKey),
    ...(options.pairing === true ? { p: 1 as const } : {}),
  };
  return { message, ephemeralKeyPair, nonce };
}

export async function createResponderReply(
  hello: HandshakeHello,
  devicePublicKey: Uint8Array,
  options: HelloOptions = {},
): Promise<HelloResult<HandshakeReply>> {
  assertHello(hello);
  const ephemeralKeyPair = options.ephemeralKeyPair ?? (await generateKeyPair());
  const nonce = options.nonce ?? randomNonce();
  if (nonce.length !== 32) throw new PhoneBridgeError("handshake_failed", "handshake nonce must be 32 bytes");
  const message: HandshakeReply = {
    t: "hs2",
    e: toBase64Url(ephemeralKeyPair.publicKey),
    n: toBase64Url(nonce),
    dp: toBase64Url(devicePublicKey),
  };
  return { message, ephemeralKeyPair, nonce };
}

export function assertHello(value: unknown): asserts value is HandshakeHello {
  const record = asRecord(value);
  if (record["t"] !== "hs1") throw new PhoneBridgeError("handshake_failed", "expected hs1 message");
  assertBytes(record["e"], 32, "hs1.e");
  assertBytes(record["n"], 32, "hs1.n");
  assertBytes(record["bp"], 32, "hs1.bp");
  if (record["p"] !== undefined && record["p"] !== 1) {
    throw new PhoneBridgeError("handshake_failed", "unsupported pairing flag in hs1");
  }
}

export function assertReply(value: unknown): asserts value is HandshakeReply {
  const record = asRecord(value);
  if (record["t"] !== "hs2") throw new PhoneBridgeError("handshake_failed", "expected hs2 message");
  assertBytes(record["e"], 32, "hs2.e");
  assertBytes(record["n"], 32, "hs2.n");
  assertBytes(record["dp"], 32, "hs2.dp");
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null) {
    throw new PhoneBridgeError("handshake_failed", "handshake message is not an object");
  }
  return value as Record<string, unknown>;
}

function assertBytes(value: unknown, length: number, field: string): void {
  if (typeof value !== "string") {
    throw new PhoneBridgeError("handshake_failed", `${field} must be a base64url string`);
  }
  let bytes: Uint8Array;
  try {
    bytes = fromBase64Url(value);
  } catch {
    throw new PhoneBridgeError("handshake_failed", `${field} is not valid base64url`);
  }
  if (bytes.length !== length) {
    throw new PhoneBridgeError("handshake_failed", `${field} must be ${length} bytes`);
  }
}

/** Verifies that the device proved the identity the bot pinned in its pairing record. */
export function assertDevicePinned(reply: HandshakeReply, expectedDevicePublicKey: Uint8Array): void {
  const actual = fromBase64Url(reply.dp);
  if (actual.length !== expectedDevicePublicKey.length) {
    throw new PhoneBridgeError("handshake_failed", "device identity length mismatch");
  }
  let diff = 0;
  for (let i = 0; i < actual.length; i += 1) diff |= (actual[i] ?? 0) ^ (expectedDevicePublicKey[i] ?? 0);
  if (diff !== 0) {
    throw new PhoneBridgeError("handshake_failed", "device public key does not match the paired device");
  }
}

export interface DeriveKeysInput {
  role: "bot" | "device";
  /** Own long-term key pair. */
  staticKeyPair: KeyPair;
  /** Peer long-term public key (pinned). */
  peerStaticPublic: Uint8Array;
  /** Own ephemeral key pair from this handshake. */
  ephemeralKeyPair: KeyPair;
  /** Peer ephemeral public key from this handshake. */
  peerEphemeralPublic: Uint8Array;
  /** Own hs nonce. */
  ownNonce: Uint8Array;
  /** Peer hs nonce. */
  peerNonce: Uint8Array;
  /** One-time pairing secret, only for the first paired handshake. */
  pairingSecret?: Uint8Array;
}

export async function deriveHandshakeKeys(input: DeriveKeysInput): Promise<HandshakeKeys> {
  const botStaticPublic =
    input.role === "bot" ? input.staticKeyPair.publicKey : input.peerStaticPublic;
  const botEphemeralPublic =
    input.role === "bot" ? input.ephemeralKeyPair.publicKey : input.peerEphemeralPublic;
  const botNonce = input.role === "bot" ? input.ownNonce : input.peerNonce;
  const deviceStaticPublic =
    input.role === "device" ? input.staticKeyPair.publicKey : input.peerStaticPublic;
  const deviceEphemeralPublic =
    input.role === "device" ? input.ephemeralKeyPair.publicKey : input.peerEphemeralPublic;
  const deviceNonce = input.role === "device" ? input.ownNonce : input.peerNonce;

  const dh1 = await x25519(input.ephemeralKeyPair.secretKey, input.peerEphemeralPublic);
  const dh2 =
    input.role === "bot"
      ? await x25519(input.ephemeralKeyPair.secretKey, input.peerStaticPublic)
      : await x25519(input.staticKeyPair.secretKey, input.peerEphemeralPublic);
  const dh3 =
    input.role === "bot"
      ? await x25519(input.staticKeyPair.secretKey, input.peerEphemeralPublic)
      : await x25519(input.ephemeralKeyPair.secretKey, input.peerStaticPublic);
  const dh4 = await x25519(input.staticKeyPair.secretKey, input.peerStaticPublic);

  const secretHash =
    input.pairingSecret === undefined ? new Uint8Array(0) : sha256(input.pairingSecret);
  const flags = new Uint8Array([input.pairingSecret === undefined ? 0 : 1]);
  const transcriptHash = sha256(
    concatBytes(
      utf8(HANDSHAKE_DOMAIN),
      flags,
      botEphemeralPublic,
      botNonce,
      botStaticPublic,
      deviceEphemeralPublic,
      deviceNonce,
      deviceStaticPublic,
      secretHash,
    ),
  );

  const ikm = concatBytes(dh1, dh2, dh3, dh4);
  const okm = await blake2b(64, ikm, transcriptHash);
  return {
    transcriptHash,
    ikm,
    okm,
    botToDevice: okm.subarray(0, 32),
    deviceToBot: okm.subarray(32, 64),
  };
}

export function buildFinishMessage(kind: "hs3" | "hs4", transcriptHash: Uint8Array): HandshakeFinish {
  return { t: kind, th: toBase64Url(transcriptHash) };
}

export function verifyFinish(
  value: unknown,
  kind: "hs3" | "hs4",
  transcriptHash: Uint8Array,
): void {
  const record = asRecord(value);
  if (record["t"] !== kind) {
    throw new PhoneBridgeError("handshake_failed", `expected ${kind} message`);
  }
  if (typeof record["th"] !== "string") {
    throw new PhoneBridgeError("handshake_failed", `${kind} is missing the transcript hash`);
  }
  const expected = toBase64Url(transcriptHash);
  if (record["th"] !== expected) {
    throw new PhoneBridgeError("handshake_failed", "handshake transcript mismatch");
  }
}
