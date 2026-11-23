/**
 * Encrypted frame layer between bot and device.
 *
 * Every application message is JSON, sealed with XChaCha20-Poly1305 using a per-direction key and a
 * monotonically increasing 64-bit counter (the counter is the last 8 bytes of the 24-byte nonce,
 * the first 16 bytes are zero). AAD is the fixed label in DATA_AAD_LABEL.
 *
 * Messages larger than MAX_CHUNK_BYTES are split into `{t:"chunk",cid,i,n,d}` frames that the peer
 * reassembles before parsing as JSON. The relay drops frames over 16 MiB, and a 32 KiB plaintext
 * chunk stays far below that after base64url and the AEAD tag.
 */
import { DATA_AAD_LABEL } from "./crypto.js";
import { randomUUID } from "node:crypto";
import { concatBytes, fromBase64Url, fromUtf8, toBase64Url, utf8 } from "./bytes.js";
import { counterNonce, open as aeadOpen, seal as aeadSeal } from "./crypto.js";
import { PhoneBridgeError } from "./errors.js";

export const MAX_CHUNK_BYTES = 32 * 1024;

const AAD = utf8(DATA_AAD_LABEL);

export class SecureChannel {
  private sendKey: Uint8Array;
  private recvKey: Uint8Array;
  private sendCounter: number;
  private recvCounter: number;

  constructor(
    sendKey: Uint8Array,
    recvKey: Uint8Array,
    counters: { send?: number; recv?: number } = {},
  ) {
    if (sendKey.length !== 32 || recvKey.length !== 32) {
      throw new PhoneBridgeError("handshake_failed", "session keys must be 32 bytes");
    }
    this.sendKey = sendKey;
    this.recvKey = recvKey;
    this.sendCounter = counters.send ?? 0;
    this.recvCounter = counters.recv ?? 0;
  }

  get sent(): number {
    return this.sendCounter;
  }

  get received(): number {
    return this.recvCounter;
  }

  async sealBytes(plaintext: Uint8Array): Promise<Uint8Array> {
    const nonce = counterNonce(this.sendCounter);
    const ciphertext = await aeadSeal(this.sendKey, nonce, plaintext, AAD);
    this.sendCounter += 1;
    return ciphertext;
  }

  async openBytes(ciphertext: Uint8Array): Promise<Uint8Array> {
    const nonce = counterNonce(this.recvCounter);
    let plaintext: Uint8Array;
    try {
      plaintext = await aeadOpen(this.recvKey, nonce, ciphertext, AAD);
    } catch {
      throw new PhoneBridgeError(
        "handshake_failed",
        "could not decrypt an end-to-end frame (wrong key, wrong counter or tampering)",
      );
    }
    this.recvCounter += 1;
    return plaintext;
  }

  async sealJson(value: unknown): Promise<Uint8Array> {
    return this.sealBytes(utf8(JSON.stringify(value)));
  }

  async openJson(ciphertext: Uint8Array): Promise<unknown> {
    const bytes = await this.openBytes(ciphertext);
    try {
      return JSON.parse(fromUtf8(bytes)) as unknown;
    } catch {
      throw new PhoneBridgeError("bad_args", "peer sent invalid JSON inside an encrypted frame");
    }
  }
}

interface ChunkFrame {
  t: "chunk";
  cid: string;
  i: number;
  n: number;
  d: string;
}

function isChunkFrame(value: unknown): value is ChunkFrame {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Record<string, unknown>;
  return (
    record["t"] === "chunk" &&
    typeof record["cid"] === "string" &&
    typeof record["i"] === "number" &&
    typeof record["n"] === "number" &&
    typeof record["d"] === "string"
  );
}

/** Splits a JSON value into one or more sealed frames. */
export async function sealMessage(
  channel: SecureChannel,
  value: unknown,
  chunkId: () => string = randomUUID,
): Promise<Uint8Array[]> {
  const bytes = utf8(JSON.stringify(value));
  if (bytes.length <= MAX_CHUNK_BYTES) {
    return [await channel.sealBytes(bytes)];
  }
  const total = Math.ceil(bytes.length / MAX_CHUNK_BYTES);
  const cid = chunkId();
  const frames: Uint8Array[] = [];
  for (let index = 0; index < total; index += 1) {
    const slice = bytes.subarray(index * MAX_CHUNK_BYTES, (index + 1) * MAX_CHUNK_BYTES);
    const frame: ChunkFrame = { t: "chunk", cid, i: index, n: total, d: toBase64Url(slice) };
    frames.push(await channel.sealJson(frame));
  }
  return frames;
}

/** Reassembles chunked messages received from a peer. */
export class ChunkAssembler {
  private pending = new Map<string, { total: number; parts: Array<Uint8Array | undefined> }>();

  /** Returns the complete decoded value, or `undefined` while more chunks are outstanding. */
  push(value: unknown): unknown {
    if (!isChunkFrame(value)) return value;
    const { cid, i, n } = value;
    if (!Number.isInteger(n) || n < 1 || n > 1024 || !Number.isInteger(i) || i < 0 || i >= n) {
      throw new PhoneBridgeError("bad_args", "peer sent a malformed chunk frame");
    }
    let entry = this.pending.get(cid);
    if (entry === undefined) {
      entry = { total: n, parts: new Array<Uint8Array | undefined>(n).fill(undefined) };
      this.pending.set(cid, entry);
    }
    if (entry.total !== n) {
      this.pending.delete(cid);
      throw new PhoneBridgeError("bad_args", "peer changed the chunk count mid-message");
    }
    entry.parts[i] = fromBase64Url(value.d);
    let size = 0;
    for (const part of entry.parts) {
      if (part === undefined) return undefined;
      size += part.length;
    }
    this.pending.delete(cid);
    const joined = concatBytes(...(entry.parts as Uint8Array[]));
    if (joined.length !== size) {
      throw new PhoneBridgeError("bad_args", "chunk reassembly failed");
    }
    try {
      return JSON.parse(fromUtf8(joined)) as unknown;
    } catch {
      throw new PhoneBridgeError("bad_args", "reassembled message is not valid JSON");
    }
  }

  get pendingCount(): number {
    return this.pending.size;
  }
}
