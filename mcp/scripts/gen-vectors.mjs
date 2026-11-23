/**
 * Regenerates test/vectors/handshake-v1.json.
 *
 * Run `npm run build && npm run vectors` after any protocol change, commit both the script and the
 * JSON, and tell the :core lane — the vectors are the cross-language contract.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { sha256, identityFromSecret, seal, counterNonce, x25519 } from "../dist/client/crypto.js";
import {
  createInitiatorHello,
  createResponderReply,
  deriveHandshakeKeys,
  buildFinishMessage,
} from "../dist/client/handshake.js";
import { SecureChannel } from "../dist/client/transport.js";
import { makeCode } from "../dist/client/codes.js";
import { utf8, toHex, toBase64Url, concatBytes } from "../dist/client/bytes.js";

const here = dirname(fileURLToPath(import.meta.url));
const outFile = join(here, "..", "test", "vectors", "handshake-v1.json");

const key = (label) => identityFromSecret(sha256(utf8(label)));
const bytes32 = (label) => sha256(utf8(label));

const bot = await key("phonebridge-vector/bot-static");
const device = await key("phonebridge-vector/device-static");
const botEphemeral = await key("phonebridge-vector/bot-ephemeral");
const deviceEphemeral = await key("phonebridge-vector/device-ephemeral");
const botNonce = bytes32("phonebridge-vector/bot-nonce");
const deviceNonce = bytes32("phonebridge-vector/device-nonce");
const pairingSecret = bytes32("phonebridge-vector/pairing-secret");
const challenge = await key("phonebridge-vector/relay-challenge");

const hello = await createInitiatorHello(bot.publicKey, {
  ephemeralKeyPair: botEphemeral,
  nonce: botNonce,
  pairing: true,
});
const reply = await createResponderReply(hello.message, device.publicKey, {
  ephemeralKeyPair: deviceEphemeral,
  nonce: deviceNonce,
});

const botKeys = await deriveHandshakeKeys({
  role: "bot",
  staticKeyPair: bot,
  peerStaticPublic: device.publicKey,
  ephemeralKeyPair: botEphemeral,
  peerEphemeralPublic: deviceEphemeral.publicKey,
  ownNonce: botNonce,
  peerNonce: deviceNonce,
  pairingSecret,
});
const deviceKeys = await deriveHandshakeKeys({
  role: "device",
  staticKeyPair: device,
  peerStaticPublic: bot.publicKey,
  ephemeralKeyPair: deviceEphemeral,
  peerEphemeralPublic: botEphemeral.publicKey,
  ownNonce: deviceNonce,
  peerNonce: botNonce,
  pairingSecret,
});

const botChannel = new SecureChannel(botKeys.botToDevice, botKeys.deviceToBot);
const deviceChannel = new SecureChannel(deviceKeys.deviceToBot, deviceKeys.botToDevice);

const hs3 = await botChannel.sealJson(buildFinishMessage("hs3", botKeys.transcriptHash));
const hs4 = await deviceChannel.sealJson(buildFinishMessage("hs4", deviceKeys.transcriptHash));

const request = {
  id: "11111111-1111-4111-8111-111111111111",
  cmd: "tap",
  args: { x: 540, y: 1200 },
};
const requestPlaintext = JSON.stringify(request);
const requestCiphertext = await botChannel.sealBytes(utf8(requestPlaintext));

// Same values with the plain AEAD primitive and an explicit nonce, so :core can check it directly.
const directNonce = counterNonce(0);
const directCiphertext = await seal(
  botKeys.botToDevice,
  directNonce,
  utf8("PhoneBridge vector"),
  utf8("PhoneBridge/v1/data"),
);

const helloProof = await x25519(bot.secretKey, challenge.publicKey);
const helloProofRelaySide = await x25519(challenge.secretKey, bot.publicKey);

const dh1 = await x25519(botEphemeral.secretKey, deviceEphemeral.publicKey);
const dh2 = await x25519(botEphemeral.secretKey, device.publicKey);
const dh3 = await x25519(bot.secretKey, deviceEphemeral.publicKey);
const dh4 = await x25519(bot.secretKey, device.publicKey);

const only = (bytes) => toHex(bytes);

const vector = {
  version: 1,
  protocol: "PhoneBridge v0.1 handshake/1 (mcp concretization)",
  generatedBy: "mcp/scripts/gen-vectors.mjs",
  notes: [
    "All keys are X25519 secret/public pairs encoded as hex.",
    "peer_id = Crockford base32 (lowercase, no padding) of the first 16 bytes of SHA-256(public key).",
    "fingerprint = the first 8 peer_id symbols uppercased and grouped ABCD-EFGH.",
    "th = SHA-256('PhoneBridge/v1/handshake' || flags || e_bot || n_bot || s_bot || e_dev || n_dev || s_dev || [SHA-256(secret)]).",
    "okm = BLAKE2b-512(key = th, data = dh1 || dh2 || dh3 || dh4) using libsodium crypto_generichash.",
    "k_bot_to_device = okm[0..32), k_device_to_bot = okm[32..64).",
    "Frames are XChaCha20-Poly1305 IETF with AAD 'PhoneBridge/v1/data' and a 24-byte nonce of 16 zero bytes followed by a big-endian 64-bit counter.",
    "hs3 is sealed by the bot with counter 0, hs4 by the device with counter 0; app data starts at counter 1.",
  ],
  alphabet: "0123456789abcdefghjkmnpqrstvwxyz",
  identities: {
    bot: {
      secretKey: only(bot.secretKey),
      publicKey: only(bot.publicKey),
      peerId: bot.peerId,
      fingerprint: `${bot.peerId.slice(0, 4).toUpperCase()}-${bot.peerId.slice(4, 8).toUpperCase()}`,
    },
    device: {
      secretKey: only(device.secretKey),
      publicKey: only(device.publicKey),
      peerId: device.peerId,
      fingerprint: `${device.peerId.slice(0, 4).toUpperCase()}-${device.peerId.slice(4, 8).toUpperCase()}`,
    },
  },
  ephemeral: {
    bot: { secretKey: only(botEphemeral.secretKey), publicKey: only(botEphemeral.publicKey) },
    device: { secretKey: only(deviceEphemeral.secretKey), publicKey: only(deviceEphemeral.publicKey) },
    botNonce: only(botNonce),
    deviceNonce: only(deviceNonce),
  },
  pairing: {
    secret: only(pairingSecret),
    code: makeCode("7K4Q9"),
    offer: {
      v: 1,
      relay: "wss://relay.example.test:8765",
      device_id: device.peerId,
      device_pub: toBase64Url(device.publicKey),
      secret: toBase64Url(pairingSecret),
      code: makeCode("7K4Q9"),
      exp: 1900000000,
    },
  },
  handshake: {
    hs1: hello.message,
    hs2: reply.message,
    transcriptHash: only(botKeys.transcriptHash),
    dh: {
      dh1: only(dh1),
      dh2: only(dh2),
      dh3: only(dh3),
      dh4: only(dh4),
    },
    ikm: only(botKeys.ikm),
    okm: only(botKeys.okm),
    keys: {
      botToDevice: only(botKeys.botToDevice),
      deviceToBot: only(botKeys.deviceToBot),
    },
    hs3: { plaintext: JSON.stringify(buildFinishMessage("hs3", botKeys.transcriptHash)), nonce: toHex(counterNonce(0)), ciphertext: only(hs3) },
    hs4: { plaintext: JSON.stringify(buildFinishMessage("hs4", deviceKeys.transcriptHash)), nonce: toHex(counterNonce(0)), ciphertext: only(hs4) },
  },
  frames: {
    tapRequest: {
      plaintext: requestPlaintext,
      counter: 1,
      nonce: toHex(counterNonce(1)),
      ciphertext: only(requestCiphertext),
    },
    direct: {
      key: "botToDevice",
      plaintext: "PhoneBridge vector",
      aad: "PhoneBridge/v1/data",
      nonce: toHex(directNonce),
      ciphertext: only(directCiphertext),
    },
  },
  relay: {
    challenge: { secretKey: only(challenge.secretKey), publicKey: only(challenge.publicKey) },
    proof: only(helloProof),
    proofSeenByRelay: only(helloProofRelaySide),
  },
};

await mkdir(dirname(outFile), { recursive: true });
await writeFile(outFile, `${JSON.stringify(vector, null, 2)}\n`, "utf8");
process.stdout.write(`wrote ${outFile}\n`);
