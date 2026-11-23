/**
 * Screenshot / file chunking, matching `core/.../chunk/Chunker.kt` and docs/PROTOCOL.md section 8.
 *
 * The device announces `{chunked:true, chunk_id, total, size, sha256}` and sends `chunk` events:
 *   { "event":"chunk", "data": { "chunk_id":"…", "index":0, "total":12,
 *                                "data_b64":"…", "sha256":"<hex of the whole payload>" } }
 *
 * Chunks may arrive in any order and before or after the announcing response; the receiver verifies
 * the SHA-256 and enforces the size and index limits before returning the bytes.
 */
import { fromBase64Loose, toBase64Url } from "./bytes.js";
import { randomBytes, sha256 } from "./crypto.js";
import { toHex } from "./bytes.js";
import { PhoneBridgeError } from "./errors.js";

export const CHUNK_SIZE = 256 * 1024;
export const MAX_CHUNK_SIZE = 1024 * 1024;
export const MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
export const MAX_CHUNKS = 8192;
export const CHUNK_ID_BYTES = 8;

export interface ChunkPayload {
  chunk_id: string;
  index: number;
  total: number;
  data_b64: string;
  sha256?: string;
}

export interface ChunkSet {
  chunkId: string;
  total: number;
  size: number;
  sha256: string;
  chunks: ChunkPayload[];
}

export function newChunkId(): string {
  return toBase64Url(randomBytes(CHUNK_ID_BYTES));
}

/** Splits a payload into protocol chunks; an empty payload becomes one empty chunk. */
export function buildChunks(
  data: Uint8Array,
  chunkSize = CHUNK_SIZE,
  chunkId = newChunkId(),
): ChunkSet {
  if (!Number.isInteger(chunkSize) || chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE) {
    throw new PhoneBridgeError("bad_args", "chunk size out of range");
  }
  const digest = toHex(sha256(data));
  const total = data.length === 0 ? 1 : Math.ceil(data.length / chunkSize);
  const chunks: ChunkPayload[] = [];
  for (let index = 0; index < total; index += 1) {
    const offset = index * chunkSize;
    const end = Math.min(offset + chunkSize, data.length);
    const sliceBytes = data.length === 0 ? new Uint8Array(0) : data.subarray(offset, end);
    chunks.push({
      chunk_id: chunkId,
      index,
      total,
      data_b64: toBase64Url(sliceBytes),
      sha256: digest,
    });
  }
  return { chunkId, total, size: data.length, sha256: digest, chunks };
}

interface PendingChunk {
  total: number;
  sha256: string | undefined;
  parts: Map<number, Uint8Array>;
  receivedBytes: number;
}

/** Reassembles chunks in any order, rejecting duplicates, gaps, oversized payloads and bad hashes. */
export class ChunkAssembler {
  private pending = new Map<string, PendingChunk>();
  private readonly maxPayloadBytes: number;
  private readonly maxChunks: number;

  constructor(maxPayloadBytes = MAX_PAYLOAD_BYTES, maxChunks = MAX_CHUNKS) {
    this.maxPayloadBytes = maxPayloadBytes;
    this.maxChunks = maxChunks;
  }

  get pendingCount(): number {
    return this.pending.size;
  }

  pendingIds(): string[] {
    return [...this.pending.keys()];
  }

  accept(chunk: ChunkPayload): Uint8Array | undefined {
    if (typeof chunk.chunk_id !== "string" || chunk.chunk_id === "") {
      throw new PhoneBridgeError("bad_args", "chunk without an id");
    }
    if (!Number.isInteger(chunk.total) || chunk.total < 1 || chunk.total > this.maxChunks) {
      throw new PhoneBridgeError("bad_args", `chunk total out of range: ${String(chunk.total)}`);
    }
    if (!Number.isInteger(chunk.index) || chunk.index < 0 || chunk.index >= chunk.total) {
      throw new PhoneBridgeError("bad_args", "chunk index outside 0..total-1");
    }
    let state = this.pending.get(chunk.chunk_id);
    if (state === undefined) {
      state = { total: chunk.total, sha256: chunk.sha256, parts: new Map(), receivedBytes: 0 };
      this.pending.set(chunk.chunk_id, state);
    }
    if (state.total !== chunk.total) {
      throw new PhoneBridgeError("bad_args", "chunk total changed for the same chunk id");
    }
    if (state.sha256 !== undefined && chunk.sha256 !== undefined && state.sha256 !== chunk.sha256) {
      throw new PhoneBridgeError("bad_args", "chunk checksum changed for the same chunk id");
    }
    const bytes = fromBase64Loose(chunk.data_b64);
    if (bytes.length > MAX_CHUNK_SIZE) {
      throw new PhoneBridgeError("bad_args", `chunk too large: ${bytes.length}`);
    }
    if (state.parts.has(chunk.index)) {
      throw new PhoneBridgeError("bad_args", `duplicate chunk ${chunk.index}`);
    }
    state.parts.set(chunk.index, bytes);
    state.receivedBytes += bytes.length;
    if (state.receivedBytes > this.maxPayloadBytes) {
      this.pending.delete(chunk.chunk_id);
      throw new PhoneBridgeError("bad_args", "reassembled payload is too large");
    }
    if (state.parts.size < state.total) return undefined;

    const out = new Uint8Array(state.receivedBytes);
    let offset = 0;
    for (let index = 0; index < state.total; index += 1) {
      const part = state.parts.get(index);
      if (part === undefined) throw new PhoneBridgeError("bad_args", `missing chunk ${index}`);
      out.set(part, offset);
      offset += part.length;
    }
    this.pending.delete(chunk.chunk_id);
    if (state.sha256 !== undefined) {
      const actual = toHex(sha256(out));
      if (actual.toLowerCase() !== state.sha256.toLowerCase()) {
        throw new PhoneBridgeError("bad_args", "reassembled payload failed its SHA-256 check");
      }
    }
    return out;
  }

  discard(chunkId: string): void {
    this.pending.delete(chunkId);
  }

  clear(): void {
    this.pending.clear();
  }
}
