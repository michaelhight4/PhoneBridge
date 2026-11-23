package dev.phonebridge.core.chunk

import dev.phonebridge.core.ProtocolException
import dev.phonebridge.core.crypto.Frame
import dev.phonebridge.core.crypto.Primitives
import dev.phonebridge.core.model.AppEvent
import dev.phonebridge.core.model.AppMessages
import dev.phonebridge.core.model.EnvelopeCodec
import dev.phonebridge.core.model.RelayEnvelope
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.core.util.PbJson
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChunkerTest {
    private val payload = ByteArray(3 * 1024 * 1024) { ((it * 31) xor (it shr 8)).toByte() }

    @Test
    fun threeMegabytesSplitIntoTwelveChunks() {
        val set = Chunker.chunk(payload)
        assertEquals(12, set.total)
        assertEquals(12, set.chunks.size)
        assertEquals(payload.size.toLong(), set.size)
        assertEquals(Primitives.toHex(Primitives.sha256(payload)), set.sha256)
        assertEquals((0 until 12).toList(), set.chunks.map { it.index })
        assertTrue(set.chunks.all { it.chunkId == set.chunkId })
        assertFalse(set.isEmpty)
    }

    @Test
    fun reassemblyWorksInAnyOrder() {
        val set = Chunker.chunk(payload)
        val assembler = ChunkAssembler()
        val shuffled = set.chunks.shuffled(Random(7))
        val completed = shuffled.map { assembler.accept(it) }.filterNotNull()
        assertEquals(1, completed.size, "exactly the last chunk completes the payload")
        assertContentEquals(payload, completed.single())
        assertTrue(assembler.pendingIds.isEmpty())
    }

    @Test
    fun progressIsVisible() {
        val set = Chunker.chunk(payload, chunkSize = 1024 * 1024)
        val assembler = ChunkAssembler()
        assertEquals(null, assembler.progress(set.chunkId))
        assembler.accept(set.chunks[0])
        assertEquals(1.0 / 3.0, assertNotNull(assembler.progress(set.chunkId)), 1e-9)
        assertFalse(assembler.isComplete(set.chunkId))
        assembler.discard(set.chunkId)
        assertTrue(assembler.pendingIds.isEmpty())
    }

    @Test
    fun sizeOfOneChunkMessageIsFarBelowBothCaps() {
        val set = Chunker.chunk(payload, chunkSize = Chunker.CHUNK_SIZE)
        val chunk = set.chunks.first()
        val chunkJson = PbJson.encodeToString(ChunkPayload.serializer(), chunk)
        assertTrue(chunkJson.length < 512 * 1024, "chunk JSON was ${chunkJson.length} bytes")

        val event = AppEvent("chunk", PbJson.encodeToJsonElement(ChunkPayload.serializer(), chunk).jsonObject)
        val frame = Frame.transport(AppMessages.encodeEvent(event).toByteArray())
        val envelope = RelayEnvelope.route(
            from = "aaaaaaaaaaaaaaaaaaaaaaaaaa",
            to = "bbbbbbbbbbbbbbbbbbbbbbbbbb",
            payload = frame,
        )
        val wire = EnvelopeCodec.encode(envelope)
        assertTrue(wire.length < Chunker.MAX_ENVELOPE_BYTES / 8, "envelope was ${wire.length} bytes")
        assertTrue(wire.length < 1024 * 1024)
    }

    @Test
    fun duplicateAndOutOfRangeChunksAreRejected() {
        val set = Chunker.chunk(payload, chunkSize = 1024 * 1024)
        val assembler = ChunkAssembler()
        assembler.accept(set.chunks[0])
        assertFailsWith<ProtocolException> { assembler.accept(set.chunks[0]) }

        assertFailsWith<ProtocolException> {
            ChunkAssembler().accept(set.chunks[0].copy(index = 3))
        }
        assertFailsWith<ProtocolException> {
            ChunkAssembler().accept(set.chunks[0].copy(total = 0))
        }
        assertFailsWith<ProtocolException> {
            ChunkAssembler().accept(set.chunks[0].copy(index = -1))
        }
    }

    @Test
    fun inconsistentTotalsAndChecksumsAreRejected() {
        val set = Chunker.chunk(payload, chunkSize = 1024 * 1024)
        val assembler = ChunkAssembler()
        assembler.accept(set.chunks[0])
        assertFailsWith<ProtocolException> {
            assembler.accept(set.chunks[1].copy(total = 9))
        }
        assertFailsWith<ProtocolException> {
            assembler.accept(set.chunks[1].copy(sha256 = "00"))
        }
    }

    @Test
    fun aCorruptedChunkFailsTheFinalChecksum() {
        val set = Chunker.chunk(payload, chunkSize = 1024 * 1024)
        val assembler = ChunkAssembler()
        val first = set.chunks[0]
        val bytes = first.bytes
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        val corrupted = first.copy(data = B64Url.encode(bytes))

        var completed: ByteArray? = null
        assertFailsWith<ProtocolException> {
            for (chunk in listOf(corrupted) + set.chunks.drop(1)) {
                completed = assembler.accept(chunk) ?: completed
            }
        }
        assertEquals(null, completed)
        assertTrue(assembler.pendingIds.isEmpty(), "a failed payload must not stay in memory")
    }

    @Test
    fun emptyPayloadsRoundTrip() {
        val set = Chunker.chunk(ByteArray(0))
        assertEquals(1, set.total)
        assertContentEquals(ByteArray(0), ChunkAssembler().accept(set.chunks.single()))
        assertEquals(0, Chunker.chunkCount(0))
        assertEquals(1, Chunker.chunkCount(1))
        assertEquals(2, Chunker.chunkCount(Chunker.CHUNK_SIZE + 1))
    }

    @Test
    fun oversizedPayloadsAndChunksAreRejected() {
        val small = ChunkAssembler(maxPayloadBytes = 1000)
        val set = Chunker.chunk(ByteArray(1200), chunkSize = 600)
        small.accept(set.chunks[0])
        assertFailsWith<ProtocolException> { small.accept(set.chunks[1]) }

        val tooBig = B64Url.encode(ByteArray(Chunker.MAX_CHUNK_SIZE + 1))
        assertFailsWith<ProtocolException> {
            ChunkAssembler().accept(ChunkPayload("id", 0, 1, tooBig))
        }
    }

    @Test
    fun chunkIdsAreRandom() {
        assertNotEquals(Chunker.newChunkId(), Chunker.newChunkId())
        val custom = Chunker.chunk(payload, chunkId = "fixed-id")
        assertEquals("fixed-id", custom.chunkId)
        assertTrue(custom.chunks.all { it.chunkId == "fixed-id" })
    }
}
