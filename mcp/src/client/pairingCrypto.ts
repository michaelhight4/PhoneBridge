/**
 * Pairing payload crypto, matching `core/.../pairing/PairingCrypto.kt`:
 *
 *   key    = HKDF-SHA256(ikm = secret, salt = "PhoneBridge/1/pairing", info = "pair-frame", L = 32)
 *   sealed = nonce(12) || ChaCha20-Poly1305(ciphertext || tag(16))
 *
 * Associated data binds the direction and the device key, so a captured request cannot be replayed as
 * a result or re-targeted at another device.
 */
import { concatBytes, utf8 } from "./bytes.js";
import { aeadOpen, aeadSeal, hkdf, NONCE_BYTES, randomBytes, TAG_BYTES } from "./crypto.js";
import { PhoneBridgeError } from "./errors.js";

export const PAIRING_SALT_TEXT = "PhoneBridge/1/pairing";
export const PAIRING_KEY_INFO_TEXT = "pair-frame";
export const PAIRING_REQUEST_LABEL = "PhoneBridge/1/pair|request|";
export const PAIRING_RESULT_LABEL = "PhoneBridge/1/pair|result|";

export function derivePairingKey(secret: Uint8Array): Uint8Array {
  if (secret.length !== 32) throw new PhoneBridgeError("bad_input", "pairing secret must be 32 bytes");
  return hkdf(secret, utf8(PAIRING_SALT_TEXT), utf8(PAIRING_KEY_INFO_TEXT), 32);
}

export function pairingRequestAad(devicePublicKey: Uint8Array): Uint8Array {
  return concatBytes(utf8(PAIRING_REQUEST_LABEL), devicePublicKey);
}

export function pairingResultAad(devicePublicKey: Uint8Array): Uint8Array {
  return concatBytes(utf8(PAIRING_RESULT_LABEL), devicePublicKey);
}

/** Seals one pairing message; `nonce` is a test hook. */
export async function sealPairingPayload(
  secret: Uint8Array,
  associatedData: Uint8Array,
  plaintext: Uint8Array,
  nonce: Uint8Array = randomBytes(NONCE_BYTES),
): Promise<Uint8Array> {
  if (nonce.length !== NONCE_BYTES) throw new PhoneBridgeError("bad_input", "pairing nonce must be 12 bytes");
  const key = derivePairingKey(secret);
  const ciphertext = await aeadSeal(key, nonce, associatedData, plaintext);
  return concatBytes(nonce, ciphertext);
}

export async function openPairingPayload(
  secret: Uint8Array,
  associatedData: Uint8Array,
  sealed: Uint8Array,
): Promise<Uint8Array> {
  if (sealed.length < NONCE_BYTES + TAG_BYTES) {
    throw new PhoneBridgeError("bad_args", "pairing payload too short");
  }
  const key = derivePairingKey(secret);
  return aeadOpen(key, sealed.subarray(0, NONCE_BYTES), associatedData, sealed.subarray(NONCE_BYTES));
}
