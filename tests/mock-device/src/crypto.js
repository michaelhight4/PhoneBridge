import crypto from 'node:crypto';

export function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

export function b64urlDecode(str) {
  return Buffer.from(str, 'base64url');
}

export function b64(buf) {
  return Buffer.from(buf).toString('base64');
}

export function b64Decode(str) {
  return Buffer.from(str, 'base64');
}

export function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest();
}

export function hmacSha256(key, data) {
  return crypto.createHmac('sha256', key).update(data).digest();
}

export function randomBytes(n) {
  return crypto.randomBytes(n);
}

const CROCKFORD = '0123456789abcdefghjkmnpqrstvwxyz';

export function base32Encode(buf) {
  if (!buf || buf.length === 0) return '';
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += CROCKFORD[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += CROCKFORD[(value << (5 - bits)) & 31];
  return out;
}

export function peerIdFromPublicKey(publicKeyRaw) {
  const digest = sha256(publicKeyRaw);
  return base32Encode(digest.subarray(0, 16));
}

export function fingerprint(publicKeyRaw) {
  const id = peerIdFromPublicKey(publicKeyRaw).toUpperCase();
  return `${id.slice(0, 4)}-${id.slice(4, 8)}`;
}

export function generateX25519KeyPair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('x25519');
  // Export raw keys via JWK for portability
  const pubJwk = publicKey.export({ format: 'jwk' });
  const privJwk = privateKey.export({ format: 'jwk' });
  const pubRaw = Buffer.from(pubJwk.x, 'base64url');
  const privRaw = Buffer.from(privJwk.d, 'base64url');
  return { publicKey, privateKey, pubRaw, privRaw };
}

export function x25519SharedSecret(privateKeyObj, peerPublicRaw) {
  const peerPubJwk = { kty: 'OKP', crv: 'X25519', x: b64url(peerPublicRaw) };
  const peerPubKey = crypto.createPublicKey({ key: peerPubJwk, format: 'jwk' });
  return crypto.diffieHellman({ privateKey: privateKeyObj, publicKey: peerPubKey });
}

export function helloProof(peerPrivateKeyObj, relayPublicRaw, relayId, peerId, nonceRaw) {
  const shared = x25519SharedSecret(peerPrivateKeyObj, relayPublicRaw);
  const context = Buffer.from(
    `phonebridge/hello/v1|${relayId}|${b64url(relayPublicRaw)}|${peerId}|${b64url(nonceRaw)}`,
    'utf8'
  );
  return hmacSha256(shared, context);
}
