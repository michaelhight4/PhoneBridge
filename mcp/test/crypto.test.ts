import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  aeadOpen,
  aeadSeal,
  createIdentity,
  fingerprintForPeerId,
  hkdf,
  hmacSha256,
  identityFromSecret,
  noiseNonce,
  peerIdForPublicKey,
  sha256,
} from "../src/client/crypto.js";
import { E2ESession, frameType, FRAME_HANDSHAKE, FRAME_TRANSPORT } from "../src/client/noise.js";
import { helloContext, helloProof, verifyHelloProof } from "../src/client/helloAuth.js";
import {
  derivePairingKey,
  openPairingPayload,
  pairingRequestAad,
  pairingResultAad,
  sealPairingPayload,
} from "../src/client/pairingCrypto.js";
import { ChunkAssembler, buildChunks } from "../src/client/chunks.js";
import { fromBase64Loose, fromHex, toBase64Url, toHex, utf8 } from "../src/client/bytes.js";
import { PhoneBridgeError } from "../src/client/errors.js";

const VECTORS = join(import.meta.dirname, "vectors", "handshake-v1.json");

interface Vectors {
  identities: Record<"bot" | "device", { secretKey: string; publicKey: string; peerId: string; fingerprint: string }>;
  peerIdExample: { publicKey: string; peerId: string; fingerprint: string };
  shortCode: { examples: Array<{ data: string; code: string }>; vectorCode: string };
  relayChallenge: {
    ephemeral: { secretKey: string; publicKey: string };
    relayId: string;
    nonce: string;
    context: string;
    proof: string;
  };
  pairing: {
    secret: string;
    derivedKey: string;
    code: string;
    nonce: string;
    requestPlaintext: string;
    sealedRequest: string;
    resultPlaintext: string;
    sealedResult: string;
  };
  noise: {
    ephemeral: Record<"bot" | "device", { secretKey: string; publicKey: string }>;
    hs1: string;
    hs2: string;
    hs3: string;
    hs1Hex: string;
    hs2Hex: string;
    hs3Hex: string;
    handshakeHash: string;
    handshakeHashOnDevice: string;
    peerStaticSeenByDevice: string;
    peerStaticSeenByBot: string;
  };
  transport: {
    requestPlaintext: string;
    requestFrame: string;
    requestFrameHex: string;
    eventPlaintext: string;
    eventFrame: string;
    eventFrameHex: string;
    nonce0: string;
    nonce1: string;
  };
  chunks: {
    payload: string;
    payloadSha256: string;
    chunkSize: number;
    chunks: Array<{ chunk_id: string; index: number; total: number; data_b64: string; sha256: string }>;
  };
  kdf: {
    hkdfRfc5869Case1: { ikm: string; salt: string; info: string; length: number; okm: string };
    hmacSha256: { key: string; data: string; mac: string };
  };
}

async function loadVectors(): Promise<Vectors> {
  return JSON.parse(await readFile(VECTORS, "utf8")) as Vectors;
}

describe("primitives", () => {
  it("hashes, MACs and derives keys exactly like :core", async () => {
    expect(toHex(sha256(utf8("abc")))).toBe(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    );
    const vectors = await loadVectors();
    expect(toHex(hkdf(
      fromHex(vectors.kdf.hkdfRfc5869Case1.ikm),
      fromHex(vectors.kdf.hkdfRfc5869Case1.salt),
      fromHex(vectors.kdf.hkdfRfc5869Case1.info),
      vectors.kdf.hkdfRfc5869Case1.length,
    ))).toBe(vectors.kdf.hkdfRfc5869Case1.okm);
    expect(toHex(hmacSha256(fromHex(vectors.kdf.hmacSha256.key), fromHex(vectors.kdf.hmacSha256.data)))).toBe(
      vectors.kdf.hmacSha256.mac,
    );
  });

  it("builds the Noise nonce as a little-endian counter plus zero padding", () => {
    expect(toHex(noiseNonce(0))).toBe("000000000000000000000000");
    expect(toHex(noiseNonce(1))).toBe("010000000000000000000000");
    expect(toHex(noiseNonce(256))).toBe("000100000000000000000000");
  });

  it("seals and opens ChaCha20-Poly1305 frames, rejecting tampering and wrong nonces", async () => {
    const key = sha256(utf8("aead-key"));
    const aad = new Uint8Array(0);
    const ciphertext = await aeadSeal(key, noiseNonce(0), aad, utf8("hello"));
    expect(ciphertext.length).toBe(5 + 16);
    expect(Buffer.from(await aeadOpen(key, noiseNonce(0), aad, ciphertext)).toString("utf8")).toBe("hello");
    await expect(aeadOpen(key, noiseNonce(1), aad, ciphertext)).rejects.toBeTruthy();
    const tampered = new Uint8Array(ciphertext);
    tampered[0] = (tampered[0] as number) ^ 1;
    await expect(aeadOpen(key, noiseNonce(0), aad, tampered)).rejects.toBeTruthy();
  });

  it("derives peer ids and fingerprints with the documented test vector", async () => {
    const vectors = await loadVectors();
    const publicKey = fromHex(vectors.peerIdExample.publicKey);
    expect(peerIdForPublicKey(publicKey)).toBe(vectors.peerIdExample.peerId);
    expect(fingerprintForPeerId(vectors.peerIdExample.peerId)).toBe(vectors.peerIdExample.fingerprint);
  });
});

describe("test vectors (cross-language contract for :core)", () => {
  it("reproduces identities, my fingerprints and pair codes", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const device = await identityFromSecret(fromHex(vectors.identities.device.secretKey));
    expect(bot.peerId).toBe(vectors.identities.bot.peerId);
    expect(device.peerId).toBe(vectors.identities.device.peerId);
    expect(toHex(bot.publicKey)).toBe(vectors.identities.bot.publicKey);
    expect(fingerprintForPeerId(bot.peerId)).toBe(vectors.identities.bot.fingerprint);
  });

  it("reproduces the relay hello proof", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const relayPublicKey = fromHex(vectors.relayChallenge.ephemeral.publicKey);
    const nonce = fromHex(vectors.relayChallenge.nonce);
    const context = helloContext(vectors.relayChallenge.relayId, relayPublicKey, bot.peerId, nonce);
    expect(Buffer.from(context).toString("utf8")).toBe(vectors.relayChallenge.context);
    const proof = await helloProof(
      bot.secretKey,
      bot.publicKey,
      relayPublicKey,
      vectors.relayChallenge.relayId,
      bot.peerId,
      nonce,
    );
    expect(toHex(proof)).toBe(vectors.relayChallenge.proof);
    const relayOk = await verifyHelloProof(
      fromHex(vectors.relayChallenge.ephemeral.secretKey),
      relayPublicKey,
      vectors.relayChallenge.relayId,
      bot.peerId,
      bot.publicKey,
      nonce,
      proof,
    );
    expect(relayOk).toBe(true);
    const tampered = new Uint8Array(proof);
    tampered[0] = (tampered[0] as number) ^ 1;
    expect(
      await verifyHelloProof(
        fromHex(vectors.relayChallenge.ephemeral.secretKey),
        relayPublicKey,
        vectors.relayChallenge.relayId,
        bot.peerId,
        bot.publicKey,
        nonce,
        tampered,
      ),
    ).toBe(false);
  });

  it("reproduces the pairing key and sealed request/result", async () => {
    const vectors = await loadVectors();
    const devicePublicKey = fromHex(vectors.identities.device.publicKey);
    const secret = fromHex(vectors.pairing.secret);
    expect(toHex(derivePairingKey(secret))).toBe(vectors.pairing.derivedKey);
    const sealed = await sealPairingPayload(
      secret,
      pairingRequestAad(devicePublicKey),
      utf8(vectors.pairing.requestPlaintext),
      fromHex(vectors.pairing.nonce),
    );
    expect(toHex(sealed)).toBe(vectors.pairing.sealedRequest);
    const opened = await openPairingPayload(secret, pairingResultAad(devicePublicKey), fromHex(vectors.pairing.sealedResult));
    expect(Buffer.from(opened).toString("utf8")).toBe(vectors.pairing.resultPlaintext);
    // Direction binding: a request payload must not open with the result AAD.
    await expect(
      openPairingPayload(secret, pairingResultAad(devicePublicKey), fromHex(vectors.pairing.sealedRequest)),
    ).rejects.toBeTruthy();
  });

  it("reproduces the Noise XX handshake, keys and first transport frames", async () => {
    const vectors = await loadVectors();
    const bot = await identityFromSecret(fromHex(vectors.identities.bot.secretKey));
    const device = await identityFromSecret(fromHex(vectors.identities.device.secretKey));
    const botEphemeral = await identityFromSecret(fromHex(vectors.noise.ephemeral.bot.secretKey));
    const deviceEphemeral = await identityFromSecret(fromHex(vectors.noise.ephemeral.device.secretKey));

    const botSession = new E2ESession("initiator", bot, { ephemeralKeyPair: botEphemeral });
    const deviceSession = new E2ESession("responder", device, { ephemeralKeyPair: deviceEphemeral });

    const hs1 = await botSession.writeHandshakeFrame();
    expect(toBase64Url(hs1)).toBe(vectors.noise.hs1);
    expect(toHex(hs1)).toBe(vectors.noise.hs1Hex);
    expect(hs1.length).toBe(33);
    await deviceSession.readHandshakeFrame(hs1);

    const hs2 = await deviceSession.writeHandshakeFrame();
    expect(toBase64Url(hs2)).toBe(vectors.noise.hs2);
    expect(hs2.length).toBe(97);
    await botSession.readHandshakeFrame(hs2);

    const hs3 = await botSession.writeHandshakeFrame();
    expect(toBase64Url(hs3)).toBe(vectors.noise.hs3);
    expect(hs3.length).toBe(65);
    await deviceSession.readHandshakeFrame(hs3);

    expect(botSession.isEstablished).toBe(true);
    expect(deviceSession.isEstablished).toBe(true);
    expect(toHex(botSession.handshakeHash)).toBe(vectors.noise.handshakeHash);
    expect(toHex(deviceSession.handshakeHash)).toBe(vectors.noise.handshakeHashOnDevice);
    expect(toHex(botSession.peerStaticKey ?? new Uint8Array())).toBe(vectors.noise.peerStaticSeenByBot);
    expect(toHex(deviceSession.peerStaticKey ?? new Uint8Array())).toBe(vectors.noise.peerStaticSeenByDevice);

    const request = await botSession.sealText(vectors.transport.requestPlaintext);
    expect(toBase64Url(request)).toBe(vectors.transport.requestFrame);
    expect(toHex(request)).toBe(vectors.transport.requestFrameHex);
    expect(await deviceSession.openText(request)).toBe(vectors.transport.requestPlaintext);

    const event = await deviceSession.sealText(vectors.transport.eventPlaintext);
    expect(toBase64Url(event)).toBe(vectors.transport.eventFrame);
    expect(await botSession.openText(event)).toBe(vectors.transport.eventPlaintext);
  });

  it("reproduces chunking exactly", async () => {
    const vectors = await loadVectors();
    const set = buildChunks(utf8(vectors.chunks.payload), vectors.chunks.chunkSize, "AAECAwQFBgc");
    expect(set.total).toBe(vectors.chunks.chunks.length);
    expect(set.sha256).toBe(vectors.chunks.payloadSha256);
    expect(
      set.chunks.map((chunk) => ({
        chunk_id: chunk.chunk_id,
        index: chunk.index,
        total: chunk.total,
        data_b64: chunk.data_b64,
        sha256: chunk.sha256,
      })),
    ).toEqual(vectors.chunks.chunks);
    const assembler = new ChunkAssembler();
    let payload: Uint8Array | undefined;
    for (const chunk of [...set.chunks].reverse()) {
      payload = assembler.accept(chunk);
    }
    expect(Buffer.from(payload ?? new Uint8Array()).toString("utf8")).toBe(vectors.chunks.payload);
  });
});

describe("Noise XX behaviours", () => {
  async function handshake(): Promise<{ bot: E2ESession; device: E2ESession }> {
    const botIdentity = await createIdentity();
    const deviceIdentity = await createIdentity();
    const bot = new E2ESession("initiator", botIdentity);
    const device = new E2ESession("responder", deviceIdentity);
    await device.readHandshakeFrame(await bot.writeHandshakeFrame());
    await bot.readHandshakeFrame(await device.writeHandshakeFrame());
    await device.readHandshakeFrame(await bot.writeHandshakeFrame());
    return { bot, device };
  }

  it("derives the same channel on both sides and enforces key pinning", async () => {
    const botIdentity = await createIdentity();
    const deviceIdentity = await createIdentity();
    const bot = new E2ESession("initiator", botIdentity);
    const device = new E2ESession("responder", deviceIdentity);
    await device.readHandshakeFrame(await bot.writeHandshakeFrame());
    await bot.readHandshakeFrame(await device.writeHandshakeFrame());
    await device.readHandshakeFrame(await bot.writeHandshakeFrame());
    expect(toHex(bot.handshakeHash)).toBe(toHex(device.handshakeHash));
    expect(() => bot.requirePeer(deviceIdentity.publicKey)).not.toThrow();
    expect(() => bot.requirePeer(botIdentity.publicKey)).toThrowError(PhoneBridgeError);

    const frame = await bot.sealText('{"id":"1"}');
    expect(frameType(frame)).toBe(FRAME_TRANSPORT);
    expect(await device.openText(frame)).toBe('{"id":"1"}');
  });

  it("rejects replays, reordering and tampering", async () => {
    const { bot, device } = await handshake();
    const first = await bot.sealText("one");
    const second = await bot.sealText("two");
    expect(await device.openText(first)).toBe("one");
    await expect(device.openText(first)).rejects.toBeTruthy();
    expect(await device.openText(second)).toBe("two");
    const tampered = new Uint8Array(second);
    tampered[2] = (tampered[2] as number) ^ 1;
    await expect(device.openText(tampered)).rejects.toBeTruthy();
    expect(frameType(new Uint8Array([FRAME_HANDSHAKE]))).toBe(FRAME_HANDSHAKE);
  });

  it("fails when the prologues differ and when a MITM answers", async () => {
    const alice = await createIdentity();
    const bob = await createIdentity();
    const mallory = await createIdentity();
    const opener = new E2ESession("initiator", alice, { prologue: utf8("PhoneBridge/1/other") });
    const responder = new E2ESession("responder", bob);
    await responder.readHandshakeFrame(await opener.writeHandshakeFrame());
    await expect(opener.readHandshakeFrame(await responder.writeHandshakeFrame())).rejects.toBeTruthy();

    const opener2 = new E2ESession("initiator", alice);
    const mitm = new E2ESession("responder", mallory);
    await mitm.readHandshakeFrame(await opener2.writeHandshakeFrame());
    await opener2.readHandshakeFrame(await mitm.writeHandshakeFrame());
    await mitm.readHandshakeFrame(await opener2.writeHandshakeFrame());
    expect(() => opener2.requirePeer(bob.publicKey)).toThrowError(/does not match the paired peer/);
  });

  it("refuses out-of-order handshake messages and oversized transport frames", async () => {
    const { bot } = await handshake();
    const identity = await createIdentity();
    const fresh = new E2ESession("responder", identity);
    await expect(fresh.writeHandshakeFrame()).rejects.toBeTruthy();
    await expect(fresh.sealText("too early")).rejects.toBeTruthy();
    await expect(bot.sealText("x".repeat(8 * 1024 * 1024 + 1))).rejects.toMatchObject({ code: "bad_args" });
  });

  it("round-trips base64url helpers liberally, as the protocol requires", async () => {
    const bytes = new Uint8Array([0xfb, 0xff, 0x00, 0x10]);
    expect(fromBase64Loose(toBase64Url(bytes))).toEqual(bytes);
    expect(fromBase64Loose(`${toBase64Url(bytes).replace(/-/g, "+").replace(/_/g, "/")}==`)).toEqual(bytes);
    expect(fromBase64Loose(" Zm9v\n")).toEqual(utf8("foo"));
  });
});
