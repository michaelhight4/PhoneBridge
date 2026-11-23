/**
 * Relay hello proof (`dh-hmac-sha256`), matching `core/.../crypto/HelloAuth.kt` and
 * `relay/PROTOCOL-RELAY.md`:
 *
 *   shared  = X25519(peer_private, relay_pub)   // relay_pub is per-connection ephemeral
 *   context = "phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>"
 *   sig     = HMAC-SHA256(key = shared, message = context)
 */
import { fromBase64Loose, toBase64Url, utf8 } from "./bytes.js";
import { hmacSha256, x25519 } from "./crypto.js";
import { PhoneBridgeError } from "./errors.js";

export const HELLO_AUTH_LABEL = "phonebridge/hello/v1";
export const HELLO_PROOF_METHOD = "dh-hmac-sha256";

export function helloContext(
  relayId: string,
  relayPublicKey: Uint8Array,
  peerId: string,
  nonce: Uint8Array,
): Uint8Array {
  return utf8(
    `${HELLO_AUTH_LABEL}|${relayId}|${toBase64Url(relayPublicKey)}|${peerId}|${toBase64Url(nonce)}`,
  );
}

/** Peer side: proves ownership of `peerPublicKey` for one relay challenge. */
export async function helloProof(
  peerSecretKey: Uint8Array,
  _peerPublicKey: Uint8Array,
  relayPublicKey: Uint8Array,
  relayId: string,
  peerId: string,
  nonce: Uint8Array,
): Promise<Uint8Array> {
  const shared = await x25519(peerSecretKey, relayPublicKey);
  return hmacSha256(shared, helloContext(relayId, relayPublicKey, peerId, nonce));
}

/** Relay side (used by the mock relay and tests): checks a hello proof. */
export async function verifyHelloProof(
  relaySecretKey: Uint8Array,
  relayPublicKey: Uint8Array,
  relayId: string,
  peerId: string,
  peerPublicKey: Uint8Array,
  nonce: Uint8Array,
  proof: Uint8Array,
): Promise<boolean> {
  const shared = await x25519(relaySecretKey, peerPublicKey);
  const expected = hmacSha256(shared, helloContext(relayId, relayPublicKey, peerId, nonce));
  if (expected.length !== proof.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i += 1) diff |= (expected[i] ?? 0) ^ (proof[i] ?? 0);
  return diff === 0;
}

/** Reads the `challenge` envelope fields, tolerating a missing optional `expires`. */
export function parseChallenge(message: Record<string, unknown>): {
  nonce: Uint8Array;
  relayPublicKey: Uint8Array;
  relayId: string;
  proofMethod: string;
} {
  const nonce = typeof message["nonce"] === "string" ? message["nonce"] : "";
  const relayPub = typeof message["relay_pub"] === "string" ? message["relay_pub"] : "";
  const relayId = typeof message["relay_id"] === "string" ? message["relay_id"] : "";
  const proofMethod = typeof message["proof"] === "string" ? message["proof"] : HELLO_PROOF_METHOD;
  if (nonce === "" || relayPub === "" || relayId === "") {
    throw new PhoneBridgeError("relay_error", "relay challenge is missing nonce, relay_pub or relay_id");
  }
  const nonceBytes = fromBase64Loose(nonce);
  const relayBytes = fromBase64Loose(relayPub);
  if (nonceBytes.length !== 32 || relayBytes.length !== 32) {
    throw new PhoneBridgeError("relay_error", "relay challenge has malformed 32-byte fields");
  }
  if (proofMethod !== HELLO_PROOF_METHOD) {
    throw new PhoneBridgeError("relay_error", `unsupported relay proof method: ${proofMethod}`);
  }
  return { nonce: nonceBytes, relayPublicKey: relayBytes, relayId, proofMethod };
}
