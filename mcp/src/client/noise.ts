/**
 * Noise `XX` (`Noise_XX_25519_ChaChaPoly_SHA256`, revision 34), ported 1:1 from the reference
 * implementation in `core/src/main/kotlin/.../crypto/Noise.kt`.
 *
 * Pattern:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 *
 * Prologue: `PhoneBridge/1/noise-xx`. Every end-to-end byte string in a `route.payload` is a frame:
 * `0x01 || handshake message` or `0x02 || transport ciphertext`.
 */
import {
  KEY_BYTES,
  TAG_BYTES,
  aeadOpen,
  aeadSeal,
  generateKeyPair,
  noiseHkdf,
  noiseNonce,
  NOISE_PROLOGUE_TEXT,
  NOISE_PROTOCOL_NAME,
  type KeyPair,
  sha256,
  x25519,
} from "./crypto.js";
import { concatBytes, fromUtf8, utf8, bytesEqual } from "./bytes.js";
import { PhoneBridgeError } from "./errors.js";

export type NoiseRole = "initiator" | "responder";

export const FRAME_HANDSHAKE = 0x01;
export const FRAME_TRANSPORT = 0x02;
export const HASH_LEN = 32;
export const DH_LEN = 32;
export const MAX_HANDSHAKE_MESSAGE = 65_535;
export const MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024;

const EMPTY = new Uint8Array(0);

export function frameHandshake(body: Uint8Array): Uint8Array {
  return concatBytes(new Uint8Array([FRAME_HANDSHAKE]), body);
}

export function frameTransport(body: Uint8Array): Uint8Array {
  return concatBytes(new Uint8Array([FRAME_TRANSPORT]), body);
}

export function frameType(frame: Uint8Array): number {
  if (frame.length === 0) throw new PhoneBridgeError("handshake_failed", "empty frame");
  return frame[0] as number;
}

export function frameBody(frame: Uint8Array, expected: number): Uint8Array {
  const actual = frameType(frame);
  if (actual !== expected) {
    throw new PhoneBridgeError(
      "handshake_failed",
      `expected frame type ${expected}, got ${actual}`,
    );
  }
  return frame.subarray(1);
}

/** The Noise `CipherState`: one key plus a 64-bit counter; the counter only advances on success. */
export class NoiseCipherState {
  private key: Uint8Array | null;
  private counter: number;
  /** Serialises concurrent seals/opens so two callers can never reuse a counter. */
  private queue: Promise<unknown> = Promise.resolve();

  constructor(key: Uint8Array | null, counter = 0) {
    this.key = key === null ? null : new Uint8Array(key);
    this.counter = counter;
  }

  get hasKey(): boolean {
    return this.key !== null;
  }

  get messages(): number {
    return this.counter;
  }

  async encryptWithAd(associatedData: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
    return this.serialise(async () => {
      if (this.key === null) return plaintext;
      if (!Number.isSafeInteger(this.counter) || this.counter >= Number.MAX_SAFE_INTEGER) {
        throw new PhoneBridgeError("internal", "nonce exhausted, session must be replaced");
      }
      const ciphertext = await aeadSeal(this.key, noiseNonce(this.counter), associatedData, plaintext);
      this.counter += 1;
      return ciphertext;
    });
  }

  async decryptWithAd(associatedData: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array> {
    return this.serialise(async () => {
      if (this.key === null) return ciphertext;
      const plaintext = await aeadOpen(this.key, noiseNonce(this.counter), associatedData, ciphertext);
      this.counter += 1;
      return plaintext;
    });
  }

  private serialise<T>(operation: () => Promise<T>): Promise<T> {
    const next = this.queue.then(operation, operation);
    this.queue = next.then(
      () => undefined,
      () => undefined,
    );
    return next;
  }
}

/** The Noise `SymmetricState`: chaining key, handshake hash and current cipher. */
class SymmetricState {
  chainingKey: Uint8Array;
  handshakeHash: Uint8Array;
  private cipher = new NoiseCipherState(null);

  constructor(protocolName: string) {
    const name = utf8(protocolName);
    this.handshakeHash = name.length <= HASH_LEN ? padTo(name, HASH_LEN) : sha256(name);
    this.chainingKey = new Uint8Array(this.handshakeHash);
  }

  get hasKey(): boolean {
    return this.cipher.hasKey;
  }

  mixHash(data: Uint8Array): void {
    this.handshakeHash = sha256(this.handshakeHash, data);
  }

  mixKey(inputKeyMaterial: Uint8Array): void {
    const outputs = noiseHkdf(this.chainingKey, inputKeyMaterial, 2);
    this.chainingKey = outputs[0] as Uint8Array;
    this.cipher = new NoiseCipherState(outputs[1] as Uint8Array);
  }

  async encryptAndHash(plaintext: Uint8Array): Promise<Uint8Array> {
    const ciphertext = await this.cipher.encryptWithAd(this.handshakeHash, plaintext);
    this.mixHash(ciphertext);
    return ciphertext;
  }

  async decryptAndHash(ciphertext: Uint8Array): Promise<Uint8Array> {
    const plaintext = await this.cipher.decryptWithAd(this.handshakeHash, ciphertext);
    this.mixHash(ciphertext);
    return plaintext;
  }

  split(): { initiatorToResponder: Uint8Array; responderToInitiator: Uint8Array } {
    const outputs = noiseHkdf(this.chainingKey, EMPTY, 2);
    return {
      initiatorToResponder: outputs[0] as Uint8Array,
      responderToInitiator: outputs[1] as Uint8Array,
    };
  }
}

type Token = "e" | "s" | "ee" | "es" | "se";

const PATTERN: readonly (readonly Token[])[] = [
  ["e"],
  ["e", "ee", "s", "es"],
  ["s", "se"],
];

export interface HandshakeOptions {
  prologue?: Uint8Array;
  /** Test hook: reuse a fixed ephemeral key pair instead of generating one. */
  ephemeralKeyPair?: KeyPair;
}

/** The Noise `XX` handshake. Callers exchange [frameHandshake] frames. */
export class NoiseXXHandshake {
  readonly role: NoiseRole;
  private readonly localStatic: KeyPair;
  private readonly symmetric: SymmetricState;
  private readonly options: HandshakeOptions;
  private localEphemeral: KeyPair | undefined;
  private remoteEphemeral: Uint8Array | undefined;
  private remoteStatic: Uint8Array | undefined;
  private index = 0;

  constructor(role: NoiseRole, localStatic: KeyPair, options: HandshakeOptions = {}) {
    this.role = role;
    this.localStatic = localStatic;
    this.options = options;
    this.symmetric = new SymmetricState(NOISE_PROTOCOL_NAME);
    this.symmetric.mixHash(options.prologue ?? utf8(NOISE_PROLOGUE_TEXT));
  }

  get messageIndex(): number {
    return this.index;
  }

  get isComplete(): boolean {
    return this.index >= PATTERN.length;
  }

  get peerStaticKey(): Uint8Array | undefined {
    return this.remoteStatic === undefined ? undefined : new Uint8Array(this.remoteStatic);
  }

  get handshakeHash(): Uint8Array {
    return new Uint8Array(this.symmetric.handshakeHash);
  }

  get canWrite(): boolean {
    return !this.isComplete && this.isSender(this.index);
  }

  get canRead(): boolean {
    return !this.isComplete && !this.isSender(this.index);
  }

  private isSender(index: number): boolean {
    return (index % 2 === 0) === (this.role === "initiator");
  }

  async writeMessage(payload: Uint8Array = EMPTY): Promise<Uint8Array> {
    if (!this.canWrite) {
      throw new PhoneBridgeError("handshake_failed", `handshake message ${this.index} is not ours to write`);
    }
    const parts: Uint8Array[] = [];
    for (const token of PATTERN[this.index] as readonly Token[]) {
      switch (token) {
        case "e": {
          if (this.localEphemeral !== undefined) {
            throw new PhoneBridgeError("handshake_failed", "ephemeral key already used");
          }
          const ephemeral = this.options.ephemeralKeyPair ?? (await generateKeyPair());
          this.localEphemeral = ephemeral;
          parts.push(ephemeral.publicKey);
          this.symmetric.mixHash(ephemeral.publicKey);
          break;
        }
        case "s":
          parts.push(await this.symmetric.encryptAndHash(this.localStatic.publicKey));
          break;
        case "ee":
          this.symmetric.mixKey(await this.dh(this.localEphemeral, this.remoteEphemeral));
          break;
        case "es":
          this.symmetric.mixKey(
            this.role === "initiator"
              ? await this.dh(this.localEphemeral, this.remoteStatic)
              : await this.dh(this.localStatic, this.remoteEphemeral),
          );
          break;
        case "se":
          this.symmetric.mixKey(
            this.role === "initiator"
              ? await this.dh(this.localStatic, this.remoteEphemeral)
              : await this.dh(this.localEphemeral, this.remoteStatic),
          );
          break;
      }
    }
    parts.push(await this.symmetric.encryptAndHash(payload));
    const message = concatBytes(...parts);
    if (message.length > MAX_HANDSHAKE_MESSAGE) {
      throw new PhoneBridgeError("handshake_failed", `handshake message too large: ${message.length}`);
    }
    this.index += 1;
    return message;
  }

  async readMessage(message: Uint8Array): Promise<Uint8Array> {
    if (!this.canRead) {
      throw new PhoneBridgeError("handshake_failed", `handshake message ${this.index} is not ours to read`);
    }
    if (message.length > MAX_HANDSHAKE_MESSAGE) {
      throw new PhoneBridgeError("handshake_failed", `handshake message too large: ${message.length}`);
    }
    let offset = 0;
    for (const token of PATTERN[this.index] as readonly Token[]) {
      switch (token) {
        case "e": {
          if (this.remoteEphemeral !== undefined) {
            throw new PhoneBridgeError("handshake_failed", "peer sent two ephemeral keys");
          }
          this.remoteEphemeral = slice(message, offset, DH_LEN);
          offset += DH_LEN;
          this.symmetric.mixHash(this.remoteEphemeral);
          break;
        }
        case "s": {
          const length = DH_LEN + (this.symmetric.hasKey ? TAG_BYTES : 0);
          const plaintext = await this.symmetric.decryptAndHash(slice(message, offset, length));
          offset += length;
          if (plaintext.length !== DH_LEN) {
            throw new PhoneBridgeError("handshake_failed", `peer static key has wrong length ${plaintext.length}`);
          }
          if (this.remoteStatic !== undefined) {
            throw new PhoneBridgeError("handshake_failed", "peer sent two static keys");
          }
          this.remoteStatic = plaintext;
          break;
        }
        case "ee":
          this.symmetric.mixKey(await this.dh(this.localEphemeral, this.remoteEphemeral));
          break;
        case "es":
          this.symmetric.mixKey(
            this.role === "initiator"
              ? await this.dh(this.localEphemeral, this.remoteStatic)
              : await this.dh(this.localStatic, this.remoteEphemeral),
          );
          break;
        case "se":
          this.symmetric.mixKey(
            this.role === "initiator"
              ? await this.dh(this.localStatic, this.remoteEphemeral)
              : await this.dh(this.localEphemeral, this.remoteStatic),
          );
          break;
      }
      if (offset > message.length) {
        throw new PhoneBridgeError("handshake_failed", "truncated handshake message");
      }
    }
    const payload = await this.symmetric.decryptAndHash(slice(message, offset, message.length - offset));
    this.index += 1;
    return payload;
  }

  /** Call once [isComplete]; returns the two-direction transport. */
  split(): NoiseTransport {
    if (!this.isComplete) throw new PhoneBridgeError("handshake_failed", "handshake is not complete");
    const peer = this.remoteStatic;
    if (peer === undefined) {
      throw new PhoneBridgeError("handshake_failed", "handshake finished without a peer static key");
    }
    const { initiatorToResponder, responderToInitiator } = this.symmetric.split();
    const sendFirst = this.role === "initiator";
    return new NoiseTransport(
      new NoiseCipherState(sendFirst ? initiatorToResponder : responderToInitiator),
      new NoiseCipherState(sendFirst ? responderToInitiator : initiatorToResponder),
      peer,
      this.symmetric.handshakeHash,
      this.role,
    );
  }

  private async dh(local: KeyPair | undefined, remote: Uint8Array | undefined): Promise<Uint8Array> {
    if (local === undefined) throw new PhoneBridgeError("handshake_failed", "missing local key for DH");
    if (remote === undefined) throw new PhoneBridgeError("handshake_failed", "missing remote key for DH");
    return x25519(local.secretKey, remote);
  }
}

/** Post-handshake transport: one key per direction, strictly increasing counters. */
export class NoiseTransport {
  private readonly sendCipher: NoiseCipherState;
  private readonly receiveCipher: NoiseCipherState;
  readonly peerStaticKey: Uint8Array;
  readonly handshakeHash: Uint8Array;
  readonly role: NoiseRole;

  constructor(
    sendCipher: NoiseCipherState,
    receiveCipher: NoiseCipherState,
    peerStaticKey: Uint8Array,
    handshakeHash: Uint8Array,
    role: NoiseRole,
  ) {
    this.sendCipher = sendCipher;
    this.receiveCipher = receiveCipher;
    this.peerStaticKey = new Uint8Array(peerStaticKey);
    this.handshakeHash = new Uint8Array(handshakeHash);
    this.role = role;
  }

  get sentMessages(): number {
    return this.sendCipher.messages;
  }

  get receivedMessages(): number {
    return this.receiveCipher.messages;
  }

  async seal(plaintext: Uint8Array): Promise<Uint8Array> {
    if (plaintext.length > MAX_PLAINTEXT_BYTES) {
      throw new PhoneBridgeError(
        "bad_args",
        `plaintext of ${plaintext.length} bytes exceeds ${MAX_PLAINTEXT_BYTES}; chunk it`,
      );
    }
    return this.sendCipher.encryptWithAd(EMPTY, plaintext);
  }

  async open(ciphertext: Uint8Array): Promise<Uint8Array> {
    if (ciphertext.length > MAX_PLAINTEXT_BYTES + TAG_BYTES) {
      throw new PhoneBridgeError("bad_args", "ciphertext exceeds the transport limit");
    }
    return this.receiveCipher.decryptWithAd(EMPTY, ciphertext);
  }

  verifyPeer(expectedPublicKey: Uint8Array): boolean {
    return bytesEqual(this.peerStaticKey, expectedPublicKey);
  }

  requirePeer(expectedPublicKey: Uint8Array): void {
    if (!this.verifyPeer(expectedPublicKey)) {
      throw new PhoneBridgeError("handshake_failed", "handshake peer key does not match the paired peer");
    }
  }
}

/** Drives the Noise `XX` handshake over relay frames and exposes the encrypted channel. */
export class E2ESession {
  readonly role: NoiseRole;
  private readonly handshake: NoiseXXHandshake;
  private transport: NoiseTransport | undefined;

  constructor(role: NoiseRole, localStatic: KeyPair, options: HandshakeOptions = {}) {
    this.role = role;
    this.handshake = new NoiseXXHandshake(role, localStatic, options);
  }

  get channel(): NoiseTransport | undefined {
    return this.transport;
  }

  get isEstablished(): boolean {
    return this.transport !== undefined;
  }

  get peerStaticKey(): Uint8Array | undefined {
    return this.handshake.peerStaticKey;
  }

  get handshakeHash(): Uint8Array {
    return this.handshake.handshakeHash;
  }

  get canWriteHandshake(): boolean {
    return this.handshake.canWrite;
  }

  get canReadHandshake(): boolean {
    return this.handshake.canRead;
  }

  async writeHandshakeFrame(payload: Uint8Array = EMPTY): Promise<Uint8Array> {
    const frame = frameHandshake(await this.handshake.writeMessage(payload));
    this.establishIfComplete();
    return frame;
  }

  async readHandshakeFrame(frame: Uint8Array): Promise<Uint8Array> {
    const payload = await this.handshake.readMessage(frameBody(frame, FRAME_HANDSHAKE));
    this.establishIfComplete();
    return payload;
  }

  async seal(plaintext: Uint8Array): Promise<Uint8Array> {
    return frameTransport(await this.requireTransport().seal(plaintext));
  }

  async open(frame: Uint8Array): Promise<Uint8Array> {
    return this.requireTransport().open(frameBody(frame, FRAME_TRANSPORT));
  }

  async sealText(text: string): Promise<Uint8Array> {
    return this.seal(utf8(text));
  }

  async openText(frame: Uint8Array): Promise<string> {
    return fromUtf8(await this.open(frame));
  }

  requirePeer(expectedPublicKey: Uint8Array): void {
    this.requireTransport().requirePeer(expectedPublicKey);
  }

  private establishIfComplete(): void {
    if (this.handshake.isComplete && this.transport === undefined) {
      this.transport = this.handshake.split();
    }
  }

  private requireTransport(): NoiseTransport {
    if (this.transport === undefined) {
      throw new PhoneBridgeError("handshake_failed", "handshake is not complete yet");
    }
    return this.transport;
  }
}

function padTo(bytes: Uint8Array, length: number): Uint8Array {
  const out = new Uint8Array(length);
  out.set(bytes.subarray(0, length));
  return out;
}

function slice(message: Uint8Array, offset: number, length: number): Uint8Array {
  if (offset < 0 || length < 0 || offset + length > message.length) {
    throw new PhoneBridgeError("handshake_failed", "truncated handshake message");
  }
  return message.subarray(offset, offset + length);
}

export { KEY_BYTES as NOISE_KEY_BYTES };
