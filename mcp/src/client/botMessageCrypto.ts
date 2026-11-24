/**
 * Store-and-forward bot message encryption, matching `core/.../crypto/BotMessageCrypto.kt`:
 *
 *   frame = 0x01 | ephemeralPub(32) | senderPub(32) | nonce(12)
 *            | ChaCha20-Poly1305(ciphertext || tag(16))
 *
 *   dh1 = X25519(sender.secret, recipientPub)  (static, authenticates the sender)
 *   dh2 = X25519(ephemeral.secret, recipientPub)
 *   key = HKDF-SHA256(dh1 || dh2, "PhoneBridge/1/bot-msg", "aead-key", 32)
 *   aad = "PhoneBridge/1/bot-msg|v1|<from>|<to>|<messageId>"
 *
 * The peer ids and message id are bound into the tag, so a captured message cannot be re-routed or
 * replayed under a different id. `reply_to` is omitted from the plaintext JSON when absent.
 */
import { bytesEqual, concatBytes, fromUtf8, utf8 } from "./bytes.js";
import {
  aeadOpen,
  aeadSeal,
  hkdf,
  keyPairFromSecret,
  NONCE_BYTES,
  peerIdForPublicKey,
  randomBytes,
  TAG_BYTES,
  x25519,
  type KeyPair,
} from "./crypto.js";
import { PhoneBridgeError } from "./errors.js";

export const BOT_MESSAGE_VERSION = 0x01;
export const BOT_MSG_PUBLIC_KEY_BYTES = 32;
export const BOT_MSG_NONCE_BYTES = NONCE_BYTES;
export const BOT_MSG_KEY_BYTES = 32;
export const BOT_MSG_SALT_TEXT = "PhoneBridge/1/bot-msg";
export const BOT_MSG_KEY_INFO_TEXT = "aead-key";
export const BOT_MSG_AAD_PREFIX = "PhoneBridge/1/bot-msg|v1|";
export const BOT_MSG_HEADER_BYTES =
  1 + BOT_MSG_PUBLIC_KEY_BYTES + BOT_MSG_PUBLIC_KEY_BYTES + BOT_MSG_NONCE_BYTES;

export interface BotMessagePlaintext {
  v: 1;
  text: string;
  replyTo?: string;
}

/** A secret-key source for the ephemeral key and nonce. Defaults to the OS CSPRNG. */
export type BotMessageRandom = (length: number) => Uint8Array;

/** Seals one bot message for `recipientPublicKey`; returns the wire frame. */
export async function sealBotMessage(
  sender: KeyPair,
  recipientPublicKey: Uint8Array,
  fromPeerId: string,
  toPeerId: string,
  messageId: string,
  text: string,
  replyTo?: string,
  random: BotMessageRandom = randomBytes,
): Promise<Uint8Array> {
  if (recipientPublicKey.length !== BOT_MSG_PUBLIC_KEY_BYTES) {
    throw new PhoneBridgeError("bad_input", "recipient public key must be 32 bytes");
  }
  const ephemeralSecret = random(BOT_MSG_PUBLIC_KEY_BYTES);
  const nonce = random(NONCE_BYTES);
  if (ephemeralSecret.length !== BOT_MSG_PUBLIC_KEY_BYTES || nonce.length !== NONCE_BYTES) {
    throw new PhoneBridgeError("bad_input", "bot message random source returned the wrong byte count");
  }
  const ephemeral: KeyPair = await keyPairFromSecret(ephemeralSecret);
  const key = await deriveBotMessageKey(
    await x25519(sender.secretKey, recipientPublicKey),
    await x25519(ephemeral.secretKey, recipientPublicKey),
  );
  const plaintext = JSON.stringify({
    v: 1,
    text,
    ...(replyTo === undefined ? {} : { reply_to: replyTo }),
  });
  const ciphertext = await aeadSeal(
    key,
    nonce,
    botMessageAad(fromPeerId, toPeerId, messageId),
    utf8(plaintext),
  );
  return concatBytes(
    new Uint8Array([BOT_MESSAGE_VERSION]),
    ephemeral.publicKey,
    sender.publicKey,
    nonce,
    ciphertext,
  );
}

/** Opens and authenticates one bot message frame. */
export async function openBotMessage(
  recipient: KeyPair,
  fromPeerId: string,
  toPeerId: string,
  messageId: string,
  frame: Uint8Array,
  expectedSenderPublicKey?: Uint8Array,
): Promise<BotMessagePlaintext> {
  if (frame.length <= BOT_MSG_HEADER_BYTES + TAG_BYTES) {
    throw new PhoneBridgeError("bad_input", "bot message frame is too short");
  }
  if (frame[0] !== BOT_MESSAGE_VERSION) {
    throw new PhoneBridgeError(
      "unsupported",
      `unsupported bot message version: ${frame[0] as number}`,
    );
  }

  const ephemeralPub = frame.subarray(1, 1 + BOT_MSG_PUBLIC_KEY_BYTES);
  const senderStart = 1 + BOT_MSG_PUBLIC_KEY_BYTES;
  const senderPub = frame.subarray(senderStart, senderStart + BOT_MSG_PUBLIC_KEY_BYTES);
  const nonce = frame.subarray(senderStart + BOT_MSG_PUBLIC_KEY_BYTES, BOT_MSG_HEADER_BYTES);
  if (peerIdForPublicKey(senderPub) !== fromPeerId) {
    throw new PhoneBridgeError("bad_input", "bot message sender does not match from_peer_id");
  }
  if (expectedSenderPublicKey !== undefined && !bytesEqual(expectedSenderPublicKey, senderPub)) {
    throw new PhoneBridgeError("bad_input", "bot message sender key is not the expected key");
  }

  const key = await deriveBotMessageKey(
    await x25519(recipient.secretKey, senderPub),
    await x25519(recipient.secretKey, ephemeralPub),
  );
  const plaintext = await aeadOpen(
    key,
    nonce,
    botMessageAad(fromPeerId, toPeerId, messageId),
    frame.subarray(BOT_MSG_HEADER_BYTES),
  );

  let decoded: { v?: unknown; text?: unknown; reply_to?: unknown };
  try {
    decoded = JSON.parse(fromUtf8(plaintext)) as { v?: unknown; text?: unknown; reply_to?: unknown };
  } catch {
    throw new PhoneBridgeError("bad_args", "malformed bot message plaintext");
  }
  if (decoded.v !== 1) {
    throw new PhoneBridgeError("unsupported", `unsupported bot message plaintext version: ${String(decoded.v)}`);
  }
  if (typeof decoded.text !== "string") {
    throw new PhoneBridgeError("bad_args", "bot message plaintext has no text");
  }
  if (decoded.reply_to !== undefined && decoded.reply_to !== null && typeof decoded.reply_to !== "string") {
    throw new PhoneBridgeError("bad_args", "bot message reply_to must be a string");
  }
  return {
    v: 1,
    text: decoded.text,
    ...(typeof decoded.reply_to === "string" ? { replyTo: decoded.reply_to } : {}),
  };
}

function deriveBotMessageKey(dh1: Uint8Array, dh2: Uint8Array): Promise<Uint8Array> {
  return Promise.resolve(
    hkdf(concatBytes(dh1, dh2), utf8(BOT_MSG_SALT_TEXT), utf8(BOT_MSG_KEY_INFO_TEXT), BOT_MSG_KEY_BYTES),
  );
}

function botMessageAad(fromPeerId: string, toPeerId: string, messageId: string): Uint8Array {
  return utf8(`${BOT_MSG_AAD_PREFIX}${fromPeerId}|${toPeerId}|${messageId}`);
}
