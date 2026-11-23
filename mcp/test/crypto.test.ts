import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  blake2b,
  counterNonce,
  identityFromSecret,
  open,
  peerIdForPublicKey,
  randomBytes,
  seal,
  sha256,
  x25519,
} from "../src/client/crypto.js";
import {
  assertDevicePinned,
  assertHello,
  buildFinishMessage,
  createInitiatorHello,
  createResponderReply,
  deriveHandshakeKeys,
  verifyFinish,
} from "../src/client/handshake.js";
import { SecureChannel } from "../src/client/transport.js";
import { fromHex, toHex, utf8, concatBytes } from "../src/client/bytes.js";
import { PhoneBridgeError } from "../src/client/errors.js";

const VECTORS = join(import.meta.dirname, "vectors", "handshake-v1.json");

interface Vectors {
  identities: Record<"bot" | "device", { secretKey: string; publicKey: string; peerId: string; fingerprint: string }>;
  ephemeral: {
    bot: { secretKey: string; publicKey: string };
    device: { secretKey: string; publicKey: string };
    botNonce: string;
    deviceNonce: string;
  };
  pairing: { secret: string; code: string; offer: Record<string, unknown> };
  handshake: {
    hs1: Record<string, unknown>;
    hs2: Record<string, unknown>;
    transcriptHash: string;
    dh: Record<"dh1" | "dh2" | "dh3" | "dh4", string>;
    ikm: string;
    okm: string;
    keys: { botToDevice: string; deviceToBot: string };
    hs3: { plaintext: string; nonce: string; ciphertext: string };
    hs4: { plaintext: string; nonce: string; ciphertext: string };
  };
  frames: {
    tapRequest: { plaintext: string; counter: number; nonce: string; ciphertext: string };
    direct: { key: string; plaintext: string; aad: string; nonce: string; ciphertext: string };
  };
  relay: { challenge: { secretKey: string; publicKey: string }; proof: string; proofSeenByRelay: string };
}

async function loadVectors(): Promise<Vectors> {
  return JSON.parse(await readFile(VECTORS, "utf8")) as Vectors;
}

describe("test vectors (cross-language contract for :core)", () => {
  it("reproduces peer ids, fingerprints and the full key schedule", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const device = await identityFromSecret(fromHex(vectors.identities.device.secretKey));

    expect(bot.peerId).toBe(vectors.identities.bot.peerId);
    expect(device.peerId).toBe(vectors.identities.device.peerId);
    expect(peerIdForPublicKey(bot.publicKey)).toBe(vectors.identities.bot.peerId);
    expect(toHex(bot.publicKey)).toBe(vectors.identities.bot.publicKey);

    const botEphemeral = await identityFromSecret(fromHex(vectors.ephemeral.bot.secretKey));
    const deviceEphemeral = await identityFromSecret(fromHex(vectors.ephemeral.device.secretKey));
    const botNonce = fromHex(vectors.ephemeral.botNonce);
    const deviceNonce = fromHex(vectors.ephemeral.deviceNonce);
    const pairingSecret = fromHex(vectors.pairing.secret);

    const hello = await createInitiatorHello(bot.publicKey, {
      ephemeralKeyPair: botEphemeral,
      nonce: botNonce,
      pairing: true,
    });
    expect(hello.message).toEqual(vectors.handshake.hs1);

    const reply = await createResponderReply(hello.message, device.publicKey, {
      ephemeralKeyPair: deviceEphemeral,
      nonce: deviceNonce,
    });
    expect(reply.message).toEqual(vectors.handshake.hs2);

    expect(toHex(await x25519(botEphemeral.secretKey, deviceEphemeral.publicKey))).toBe(
      vectors.handshake.dh.dh1,
    );
    expect(toHex(await x25519(botEphemeral.secretKey, device.publicKey))).toBe(
      vectors.handshake.dh.dh2,
    );
    expect(toHex(await x25519(bot.secretKey, deviceEphemeral.publicKey))).toBe(
      vectors.handshake.dh.dh3,
    );
    expect(toHex(await x25519(bot.secretKey, device.publicKey))).toBe(vectors.handshake.dh.dh4);

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
    expect(toHex(botKeys.transcriptHash)).toBe(vectors.handshake.transcriptHash);
    expect(toHex(botKeys.ikm)).toBe(vectors.handshake.ikm);
    expect(toHex(botKeys.okm)).toBe(vectors.handshake.okm);
    expect(toHex(botKeys.botToDevice)).toBe(vectors.handshake.keys.botToDevice);
    expect(toHex(botKeys.deviceToBot)).toBe(vectors.handshake.keys.deviceToBot);

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
    expect(toHex(deviceKeys.okm)).toBe(toHex(botKeys.okm));
    expect(toHex(deviceKeys.botToDevice)).toBe(toHex(botKeys.botToDevice));
    expect(toHex(deviceKeys.deviceToBot)).toBe(toHex(botKeys.deviceToBot));
  });

  it("reproduces the hs3/hs4 and request ciphertexts byte for byte", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const device = await identityFromSecret(fromHex(vectors.identities.device.secretKey));
    const botEphemeral = await identityFromSecret(fromHex(vectors.ephemeral.bot.secretKey));
    const deviceEphemeral = await identityFromSecret(fromHex(vectors.ephemeral.device.secretKey));
    const pairingSecret = fromHex(vectors.pairing.secret);
    const hello = await createInitiatorHello(bot.publicKey, {
      ephemeralKeyPair: botEphemeral,
      nonce: fromHex(vectors.ephemeral.botNonce),
      pairing: true,
    });
    await createResponderReply(hello.message, device.publicKey, {
      ephemeralKeyPair: deviceEphemeral,
      nonce: fromHex(vectors.ephemeral.deviceNonce),
    });
    const botKeys = await deriveHandshakeKeys({
      role: "bot",
      staticKeyPair: bot,
      peerStaticPublic: device.publicKey,
      ephemeralKeyPair: botEphemeral,
      peerEphemeralPublic: deviceEphemeral.publicKey,
      ownNonce: fromHex(vectors.ephemeral.botNonce),
      peerNonce: fromHex(vectors.ephemeral.deviceNonce),
      pairingSecret,
    });
    const deviceKeys = await deriveHandshakeKeys({
      role: "device",
      staticKeyPair: device,
      peerStaticPublic: bot.publicKey,
      ephemeralKeyPair: deviceEphemeral,
      peerEphemeralPublic: botEphemeral.publicKey,
      ownNonce: fromHex(vectors.ephemeral.deviceNonce),
      peerNonce: fromHex(vectors.ephemeral.botNonce),
      pairingSecret,
    });

    const botChannel = new SecureChannel(botKeys.botToDevice, botKeys.deviceToBot);
    const deviceChannel = new SecureChannel(deviceKeys.deviceToBot, deviceKeys.botToDevice);

    const hs3 = await botChannel.sealJson(buildFinishMessage("hs3", botKeys.transcriptHash));
    expect(toHex(hs3)).toBe(vectors.handshake.hs3.ciphertext);
    const hs4 = await deviceChannel.sealJson(buildFinishMessage("hs4", deviceKeys.transcriptHash));
    expect(toHex(hs4)).toBe(vectors.handshake.hs4.ciphertext);

    const transcriptB64 = Buffer.from(vectors.handshake.transcriptHash, "hex").toString("base64url");
    expect(await deviceChannel.openJson(hs3)).toEqual({ t: "hs3", th: transcriptB64 });
    expect(await botChannel.openJson(hs4)).toEqual({ t: "hs4", th: transcriptB64 });

    const request = await botChannel.sealBytes(utf8(vectors.frames.tapRequest.plaintext));
    expect(toHex(request)).toBe(vectors.frames.tapRequest.ciphertext);

    const direct = await seal(
      fromHex(vectors.handshake.keys.botToDevice),
      fromHex(vectors.frames.direct.nonce),
      utf8(vectors.frames.direct.plaintext),
      utf8(vectors.frames.direct.aad),
    );
    expect(toHex(direct)).toBe(vectors.frames.direct.ciphertext);
    expect(toHex(counterNonce(vectors.frames.tapRequest.counter))).toBe(vectors.frames.tapRequest.nonce);
  });

  it("reproduces the relay hello proof (one-pass X25519)", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const challengePublic = fromHex(vectors.relay.challenge.publicKey);
    const proof = await x25519(bot.secretKey, challengePublic);
    expect(toHex(proof)).toBe(vectors.relay.proof);
    const relaySide = await x25519(fromHex(vectors.relay.challenge.secretKey), bot.publicKey);
    expect(toHex(relaySide)).toBe(vectors.relay.proof);
    expect(vectors.relay.proofSeenByRelay).toBe(vectors.relay.proof);
  });
});

describe("handshake helpers", () => {
  it("derives identical keys on both sides and verifies the finish messages", async () => {
    const bot = await identityFromSecret(sha256(utf8("hs-bot")));
    const device = await identityFromSecret(sha256(utf8("hs-device")));
    const hello = await createInitiatorHello(bot.publicKey);
    assertHello(hello.message);
    const reply = await createResponderReply(hello.message, device.publicKey);
    const botKeys = await deriveHandshakeKeys({
      role: "bot",
      staticKeyPair: bot,
      peerStaticPublic: device.publicKey,
      ephemeralKeyPair: hello.ephemeralKeyPair,
      peerEphemeralPublic: fromPublic(reply.message.e),
      ownNonce: hello.nonce,
      peerNonce: fromPublic(reply.message.n),
    });
    const deviceKeys = await deriveHandshakeKeys({
      role: "device",
      staticKeyPair: device,
      peerStaticPublic: bot.publicKey,
      ephemeralKeyPair: reply.ephemeralKeyPair,
      peerEphemeralPublic: fromPublic(hello.message.e),
      ownNonce: reply.nonce,
      peerNonce: hello.nonce,
    });
    expect(toHex(botKeys.okm)).toBe(toHex(deviceKeys.okm));
    const channel = new SecureChannel(botKeys.botToDevice, botKeys.deviceToBot);
    const deviceChannel = new SecureChannel(deviceKeys.deviceToBot, deviceKeys.botToDevice);
    const hs3 = await channel.sealJson(buildFinishMessage("hs3", botKeys.transcriptHash));
    const opened = await deviceChannel.openJson(hs3);
    verifyFinish(opened, "hs3", deviceKeys.transcriptHash);
    expect(() => verifyFinish(opened, "hs4", deviceKeys.transcriptHash)).toThrowError(
      PhoneBridgeError,
    );
  });

  it("binds the transcript to the pairing flag", async () => {
    const bot = await identityFromSecret(sha256(utf8("flag-bot")));
    const device = await identityFromSecret(sha256(utf8("flag-device")));
    const hello = await createInitiatorHello(bot.publicKey, { nonce: new Uint8Array(32).fill(1) });
    const reply = await createResponderReply(hello.message, device.publicKey, {
      nonce: new Uint8Array(32).fill(2),
    });
    const base = {
      role: "bot" as const,
      staticKeyPair: bot,
      peerStaticPublic: device.publicKey,
      ephemeralKeyPair: hello.ephemeralKeyPair,
      peerEphemeralPublic: fromPublic(reply.message.e),
      ownNonce: hello.nonce,
      peerNonce: fromPublic(reply.message.n),
    };
    const plain = await deriveHandshakeKeys(base);
    const paired = await deriveHandshakeKeys({ ...base, pairingSecret: sha256(utf8("secret")) });
    expect(toHex(plain.okm)).not.toBe(toHex(paired.okm));
  });

  it("rejects a device public key that does not match the pinned key", async () => {
    const bot = await identityFromSecret(sha256(utf8("pin-bot")));
    const device = await identityFromSecret(sha256(utf8("pin-device")));
    const impostor = await identityFromSecret(sha256(utf8("pin-impostor")));
    const hello = await createInitiatorHello(bot.publicKey);
    const reply = await createResponderReply(hello.message, impostor.publicKey);
    expect(() => assertDevicePinned(reply.message, device.publicKey)).toThrowError(
      /does not match the paired device/,
    );
  });

  it("fails closed when a frame is tampered with or replayed with the wrong counter", async () => {
    const key = sha256(utf8("aead-key"));
    const other = sha256(utf8("other-key"));
    const aad = utf8("PhoneBridge/v1/data");
    const ciphertext = await seal(key, counterNonce(0), utf8("hello"), aad);
    await expect(open(key, counterNonce(1), ciphertext, aad)).rejects.toBeTruthy();
    await expect(open(other, counterNonce(0), ciphertext, aad)).rejects.toBeTruthy();
    const tampered = new Uint8Array(ciphertext);
    tampered[0] = (tampered[0] as number) ^ 1;
    await expect(open(key, counterNonce(0), tampered, aad)).rejects.toBeTruthy();

    const channel = new SecureChannel(other, key);
    expect(new TextDecoder().decode(await channel.openBytes(ciphertext))).toBe("hello");
    await expect(channel.openBytes(ciphertext)).rejects.toMatchObject({ code: "handshake_failed" });
  });

  it("uses libsodium-compatible BLAKE2b and counter nonces", async () => {
    const digest = await blake2b(32, utf8("abc"));
    expect(toHex(digest)).toBe("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319");
    expect(toHex(counterNonce(1))).toBe("000000000000000000000000000000000000000000000001");
    const bytes = randomBytes(16);
    expect(bytes).toHaveLength(16);
    expect(toHex(sha256(bytes))).toHaveLength(64);
    expect(toHex(concatBytes(utf8("a"), utf8("b")))).toBe("6162");
  });
});

function fromPublic(text: string): Uint8Array {
  return Uint8Array.from(Buffer.from(text, "base64url"));
}
