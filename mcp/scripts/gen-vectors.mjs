/**
 * Regenerates test/vectors/handshake-v1.json — the cross-language contract for `core/`.
 *
 * Run `npm run build && npm run vectors` after any protocol change and commit both the script and the
 * JSON. Every value is derived from fixed inputs so Kotlin and TypeScript can compare byte for byte.
 */
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  sha256,
  identityFromSecret,
  hkdf,
  noiseNonce,
  hmacSha256,
} from "../dist/client/crypto.js";
import { E2ESession } from "../dist/client/noise.js";
import { helloContext, helloProof } from "../dist/client/helloAuth.js";
import {
  derivePairingKey,
  pairingRequestAad,
  pairingResultAad,
  sealPairingPayload,
} from "../dist/client/pairingCrypto.js";
import { buildChunks } from "../dist/client/chunks.js";
import { makeCode } from "../dist/client/codes.js";
import { toBase64Url, toHex, utf8 } from "../dist/client/bytes.js";

const here = dirname(fileURLToPath(import.meta.url));
const outFile = join(here, "..", "test", "vectors", "handshake-v1.json");

const key = async (label) => identityFromSecret(sha256(utf8(label)));
const bytes = (label, length) => sha256(utf8(label)).subarray(0, length);

const bot = await key("phonebridge-vector/bot-static");
const device = await key("phonebridge-vector/device-static");
const botEphemeral = await key("phonebridge-vector/bot-ephemeral");
const deviceEphemeral = await key("phonebridge-vector/device-ephemeral");
const relay = await key("phonebridge-vector/relay-ephemeral");
const pairingSecret = sha256(utf8("phonebridge-vector/pairing-secret"));
const pairingNonce = bytes("phonebridge-vector/pairing-nonce", 12);
const relayNonce = sha256(utf8("phonebridge-vector/relay-nonce"));
const relayId = relay.peerId;
const code = makeCode("7K4Q9");

const helloCtx = helloContext(relayId, relay.publicKey, bot.peerId, relayNonce);
const helloProofBytes = await helloProof(
  bot.secretKey,
  bot.publicKey,
  relay.publicKey,
  relayId,
  bot.peerId,
  relayNonce,
);

const pairingKey = derivePairingKey(pairingSecret);
const pairRequest = JSON.stringify({
  v: 1,
  bot_name: "vector-bot",
  bot_pub: toBase64Url(bot.publicKey),
  requested_level: "control",
  bot_id: bot.peerId,
});
const sealedRequest = await sealPairingPayload(
  pairingSecret,
  pairingRequestAad(device.publicKey),
  utf8(pairRequest),
  pairingNonce,
);
const pairResult = JSON.stringify({
  v: 1,
  ok: true,
  granted_level: "control",
  device_name: "Vector Pixel",
  device_id: device.peerId,
});
const sealedResult = await sealPairingPayload(
  pairingSecret,
  pairingResultAad(device.publicKey),
  utf8(pairResult),
  pairingNonce,
);

const botSession = new E2ESession("initiator", bot, { ephemeralKeyPair: botEphemeral });
const deviceSession = new E2ESession("responder", device, { ephemeralKeyPair: deviceEphemeral });
const hs1 = await botSession.writeHandshakeFrame();
await deviceSession.readHandshakeFrame(hs1);
const hs2 = await deviceSession.writeHandshakeFrame();
await botSession.readHandshakeFrame(hs2);
const hs3 = await botSession.writeHandshakeFrame();
await deviceSession.readHandshakeFrame(hs3);

const appRequest = JSON.stringify({
  id: "11111111-1111-4111-8111-111111111111",
  cmd: "status",
  args: {},
});
const appRequestFrame = await botSession.sealText(appRequest);
const appRequestOnDevice = await deviceSession.openText(appRequestFrame);
if (appRequestOnDevice !== appRequest) throw new Error("vector sanity: request did not round-trip");

const appEvent = JSON.stringify({ event: "queue_position", data: { position: 2 } });
const appEventFrame = await deviceSession.sealText(appEvent);

const chunkPayload = utf8("PhoneBridge chunk vector payload");
const chunks = buildChunks(chunkPayload, 8, "AAECAwQFBgc");

const hkdfCase1 = hkdf(
  new Uint8Array(22).fill(0x0b),
  Uint8Array.from([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]),
  Uint8Array.from([0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9]),
  42,
);

const hex = (value) => toHex(value);

const vector = {
  version: 1,
  protocol: "PhoneBridge protocol v1 (docs/PROTOCOL.md, core/ is the reference implementation)",
  generatedBy: "mcp/scripts/gen-vectors.mjs",
  notes: [
    "peer_id = lowercase Crockford base32 of SHA-256(publicKey)[0..16); fingerprint = first 8 symbols uppercased ABCD-EFGH.",
    "Short code = 5 Crockford data symbols + CRC-5 check (poly 0x05, init 0x1F, MSB first).",
    "Noise: Noise_XX_25519_ChaChaPoly_SHA256, prologue ASCII 'PhoneBridge/1/noise-xx'.",
    "Frames: 0x01 || handshake message, 0x02 || transport ciphertext; the values below are base64url of the whole frame.",
    "Transport nonce = 64-bit counter little-endian + four zero bytes; AAD empty; ChaCha20-Poly1305 tags are 16 bytes.",
    "Pairing: HKDF-SHA256(secret, salt 'PhoneBridge/1/pairing', info 'pair-frame') -> ChaCha20-Poly1305 with AAD 'PhoneBridge/1/pair|request|' + device_pub, sealed = nonce(12) || ciphertext || tag.",
    "Relay hello proof: sig = HMAC-SHA256(X25519(peer_private, relay_pub), 'phonebridge/hello/v1|<relay_id>|<b64url(relay_pub)>|<peer_id>|<b64url(nonce)>').",
    "Chunks: 256 KiB slices by default; data_b64 is base64url; sha256 is lowercase hex of the whole payload.",
  ],
  identities: {
    bot: {
      secretKey: hex(bot.secretKey),
      publicKey: hex(bot.publicKey),
      peerId: bot.peerId,
      fingerprint: `${bot.peerId.slice(0, 4).toUpperCase()}-${bot.peerId.slice(4, 8).toUpperCase()}`,
    },
    device: {
      secretKey: hex(device.secretKey),
      publicKey: hex(device.publicKey),
      peerId: device.peerId,
      fingerprint: `${device.peerId.slice(0, 4).toUpperCase()}-${device.peerId.slice(4, 8).toUpperCase()}`,
    },
  },
  peerIdExample: {
    publicKey: hex(Uint8Array.from({ length: 32 }, (_, index) => index)),
    peerId: "cc6wtab6rgspd48jah4bqcjv9w",
    fingerprint: "CC6W-TAB6",
  },
  shortCode: {
    examples: [
      { data: "7K4Q9", code: "7K4-Q9B" },
      { data: "ZZZZZ", code: "ZZZ-ZZQ" },
      { data: "00000", code: "000-00T" },
    ],
    vectorCode: code,
  },
  relayChallenge: {
    ephemeral: { secretKey: hex(relay.secretKey), publicKey: hex(relay.publicKey) },
    relayId,
    nonce: hex(relayNonce),
    context: Buffer.from(helloCtx).toString("utf8"),
    proof: hex(helloProofBytes),
  },
  pairing: {
    secret: hex(pairingSecret),
    derivedKey: hex(pairingKey),
    code,
    nonce: hex(pairingNonce),
    requestPlaintext: pairRequest,
    sealedRequest: hex(sealedRequest),
    resultPlaintext: pairResult,
    sealedResult: hex(sealedResult),
  },
  noise: {
    ephemeral: {
      bot: { secretKey: hex(botEphemeral.secretKey), publicKey: hex(botEphemeral.publicKey) },
      device: { secretKey: hex(deviceEphemeral.secretKey), publicKey: hex(deviceEphemeral.publicKey) },
    },
    hs1: toBase64Url(hs1),
    hs2: toBase64Url(hs2),
    hs3: toBase64Url(hs3),
    hs1Hex: hex(hs1),
    hs2Hex: hex(hs2),
    hs3Hex: hex(hs3),
    handshakeHash: hex(botSession.handshakeHash),
    handshakeHashOnDevice: hex(deviceSession.handshakeHash),
    peerStaticSeenByDevice: hex(deviceSession.peerStaticKey),
    peerStaticSeenByBot: hex(botSession.peerStaticKey),
  },
  transport: {
    requestPlaintext: appRequest,
    requestFrame: toBase64Url(appRequestFrame),
    requestFrameHex: hex(appRequestFrame),
    eventPlaintext: appEvent,
    eventFrame: toBase64Url(appEventFrame),
    eventFrameHex: hex(appEventFrame),
    nonce0: hex(noiseNonce(0)),
    nonce1: hex(noiseNonce(1)),
  },
  chunks: {
    payload: Buffer.from(chunkPayload).toString("utf8"),
    payloadSha256: toHex(sha256(chunkPayload)),
    chunkSize: 8,
    chunks: chunks.chunks.map((chunk) => ({
      chunk_id: chunk.chunk_id,
      index: chunk.index,
      total: chunk.total,
      data_b64: chunk.data_b64,
      sha256: chunk.sha256,
    })),
  },
  kdf: {
    hkdfRfc5869Case1: {
      ikm: hex(new Uint8Array(22).fill(0x0b)),
      salt: "000102030405060708090a0b0c",
      info: "f0f1f2f3f4f5f6f7f8f9",
      length: 42,
      okm: hex(hkdfCase1),
    },
    hmacSha256: {
      key: "6b6579",
      data: hex(utf8("data")),
      mac: hex(hmacSha256(utf8("key"), utf8("data"))),
    },
  },
};

await mkdir(dirname(outFile), { recursive: true });
await writeFile(outFile, `${JSON.stringify(vector, null, 2)}\n`, "utf8");
process.stdout.write(`wrote ${outFile}\n`);
