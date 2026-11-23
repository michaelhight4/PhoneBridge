package dev.phonebridge.core.chunk

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.util.B64Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * One piece of a large payload (a screenshot, a big UI tree). Chunks are sent as `chunk` events with a
 * shared [chunkId]; the receiver reassembles them with [ChunkAssembler].
 */
@Serializable
data class ChunkPayload(
    @SerialName("chunk_id")
    val chunkId: String,
    val index: Int,
    val total: Int,
    @SerialName("data_b64")
    val data: String,
    /** Lowercase hex SHA-256 of the whole payload, repeated on every chunk for early corruption checks. */
    val sha256: String? = null,
) {
    val bytes: ByteArray
        get() = B64Url.decode(data)
}

/** A payload split into chunks, ready to be sent in order. */
class ChunkSet internal constructor(
    val chunkId: String,
    val total: Int,
    val size: Long,
    val sha256: String,
    val chunks: List<ChunkPayload>,
) {
    val isEmpty: Boolean
        get() = total == 0
}

/**
 * Splits payloads so every message stays comfortably below both limits:
 *
 * - the relay's 16 MiB envelope cap ([Chunker.MAX_ENVELOPE_BYTES]),
 * - the end-to-end transport's 8 MiB plaintext cap, and
 * - what a phone wants to hold in memory at once.
 *
 * A 256 KiB chunk becomes roughly 350 KB of base64 and well under 1 MB once the envelope wraps it.
 */
object Chunker {
    const val MAX_ENVELOPE_BYTES = 16 * 1024 * 1024

    /** Raw bytes per chunk. */
    const val CHUNK_SIZE = 256 * 1024

    /** Hard ceiling on a chunk's raw size; bigger chunks are rejected by [ChunkAssembler]. */
    const val MAX_CHUNK_SIZE = 1024 * 1024

    /** Ceiling for one reassembled payload, to keep a hostile peer from exhausting memory. */
    const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

    const val MAX_CHUNKS = 8192

    const val CHUNK_ID_BYTES = 8

    fun newChunkId(random: SecureRandom = Primitives.defaultRandom()): String =
        B64Url.encode(Primitives.randomBytes(CHUNK_ID_BYTES, random))

    fun chunkCount(size: Int, chunkSize: Int = CHUNK_SIZE): Int {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "chunk size out of range" }
        if (size <= 0) return 0
        return (size + chunkSize - 1) / chunkSize
    }

    /**
     * Splits [data] into [ChunkSet]. An empty payload produces one empty chunk, so the receiving side
     * can distinguish "empty screenshot" from "no screenshot".
     */
    fun chunk(
        data: ByteArray,
        chunkSize: Int = CHUNK_SIZE,
        chunkId: String = newChunkId(),
    ): ChunkSet {
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "chunk size out of range" }
        if (chunkId.isBlank()) throw BadArgsException("chunk id must not be blank")
        val digest = Primitives.toHex(Primitives.sha256(data))
        val total = if (data.isEmpty()) 1 else chunkCount(data.size, chunkSize)
        val chunks = ArrayList<ChunkPayload>(total)
        for (index in 0 until total) {
            val offset = index * chunkSize
            val end = minOf(offset + chunkSize, data.size)
            val slice = if (data.isEmpty()) ByteArray(0) else data.copyOfRange(offset, end)
            chunks.add(
                ChunkPayload(
                    chunkId = chunkId,
                    index = index,
                    total = total,
                    data = B64Url.encode(slice),
                    sha256 = digest,
                ),
            )
        }
        return ChunkSet(chunkId, total, data.size.toLong(), digest, chunks)
    }
}

/**
 * Reassembles chunks in any order. Enforces a consistent total and checksum, rejects duplicates, index
 * gaps and oversized payloads, and verifies the SHA-256 before handing the bytes back.
 */
class ChunkAssembler(
    private val maxPayloadBytes: Long = Chunker.MAX_PAYLOAD_BYTES,
    private val maxChunks: Int = Chunker.MAX_CHUNKS,
) {
    private class Pending(val total: Int, val sha256: String?) {
        val parts = HashMap<Int, ByteArray>()
        var receivedBytes = 0L
    }

    private val pending = LinkedHashMap<String, Pending>()
    private val lock = Any()

    /** Feeds one chunk; returns the whole payload once every index arrived, else null. */
    fun accept(chunk: ChunkPayload): ByteArray? = synchronized(lock) {
        if (chunk.total < 1 || chunk.total > maxChunks) {
            throw ProtocolException("chunk total out of range: ${chunk.total}")
        }
        if (chunk.index !in 0 until chunk.total) {
            throw ProtocolException("chunk index ${chunk.index} outside 0..${chunk.total - 1}")
        }
        val state = pending.getOrPut(chunk.chunkId) { Pending(chunk.total, chunk.sha256) }
        if (state.total != chunk.total) throw ProtocolException("chunk total changed for ${chunk.chunkId}")
        if (state.sha256 != null && chunk.sha256 != null && state.sha256 != chunk.sha256) {
            throw ProtocolException("chunk checksum changed for ${chunk.chunkId}")
        }
        val bytes = chunk.bytes
        if (bytes.size > Chunker.MAX_CHUNK_SIZE) throw ProtocolException("chunk too large: ${bytes.size}")
        if (state.parts.containsKey(chunk.index)) throw ProtocolException("duplicate chunk ${chunk.index}")
        state.parts[chunk.index] = bytes
        state.receivedBytes += bytes.size
        if (state.receivedBytes > maxPayloadBytes) {
            pending.remove(chunk.chunkId)
            throw ProtocolException("reassembled payload exceeds $maxPayloadBytes bytes")
        }
        if (state.parts.size < state.total) return null

        val out = ByteArrayOutputStream(state.receivedBytes.toInt())
        for (index in 0 until state.total) {
            val part = state.parts[index] ?: throw ProtocolException("missing chunk $index")
            out.write(part)
        }
        pending.remove(chunk.chunkId)
        val payload = out.toByteArray()
        val expected = state.sha256
        if (expected != null) {
            val actual = Primitives.toHex(Primitives.sha256(payload))
            if (!actual.equals(expected, ignoreCase = true)) {
                throw ProtocolException("reassembled payload failed its SHA-256 check")
            }
        }
        payload
    }

    fun isComplete(chunkId: String): Boolean = synchronized(lock) {
        val state = pending[chunkId] ?: return false
        state.parts.size == state.total
    }

    /** Fraction of chunks received for [chunkId], or null when nothing arrived yet. */
    fun progress(chunkId: String): Double? = synchronized(lock) {
        val state = pending[chunkId] ?: return null
        state.parts.size.toDouble() / state.total
    }

    fun discard(chunkId: String) {
        synchronized(lock) { pending.remove(chunkId) }
    }

    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    val pendingIds: Set<String>
        get() = synchronized(lock) { pending.keys.toSet() }
}
