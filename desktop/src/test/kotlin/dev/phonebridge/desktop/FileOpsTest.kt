package dev.phonebridge.desktop

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.util.B64Url
import dev.phonebridge.desktop.device.FileOps
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileOpsTest {
    private val dir = Files.createTempDirectory("pb-fileops").also { it.toFile().deleteOnExit() }
    private val ops = FileOps(home = dir)

    @Test
    fun listsADirectoryWithMetadata() {
        dir.resolve("notes.txt").toFile().writeText("hello")
        dir.resolve("sub").createDirectories()

        val result = ops.perform(FileOp.LIST, dir.toString(), null)
        assertEquals("list", result.op)
        val entries = assertNotNull(result.entries)
        assertEquals(setOf("notes.txt", "sub"), entries.map { it.name }.toSet())
        val file = entries.first { it.name == "notes.txt" }
        assertEquals(false, file.isDir)
        assertEquals(5L, file.size)
        assertNotNull(file.mtime)
        assertTrue(entries.first { it.name == "sub" }.isDir)
    }

    @Test
    fun readsAndWritesBase64Payloads() {
        val target = dir.resolve("image.bin")
        val bytes = ByteArray(300) { (it % 256).toByte() }

        val write = ops.perform(FileOp.WRITE, target.toString(), bytes)
        assertEquals("write", write.op)
        assertEquals(300L, write.bytes)

        val read = ops.perform(FileOp.READ, target.toString(), null)
        assertEquals("read", read.op)
        assertNotNull(read.dataB64)
        assertTrue(bytes.contentEquals(B64Url.decode(read.dataB64!!)))
    }

    @Test
    fun resolvesRelativePathsAgainstTheHomeDirectory() {
        ops.perform(FileOp.WRITE, "relative.txt", "abc".toByteArray())
        assertTrue(Files.exists(dir.resolve("relative.txt")))
    }

    @Test
    fun capsReadsAndWrites() {
        val small = FileOps(maxReadBytes = 10, maxWriteBytes = 10, home = dir)
        val file = dir.resolve("big.txt").also { Files.write(it, ByteArray(50)) }
        assertFailsWith<BadArgsException> { small.perform(FileOp.READ, file.toString(), null) }
        assertFailsWith<BadArgsException> { small.perform(FileOp.WRITE, dir.resolve("new.txt").toString(), ByteArray(50)) }
    }

    @Test
    fun deletesFilesAndDirectories() {
        val file = dir.resolve("gone.txt").also { Files.write(it, byteArrayOf(1)) }
        assertEquals(true, ops.perform(FileOp.DELETE, file.toString(), null).ok)
        assertTrue(!Files.exists(file))

        val nested = dir.resolve("tree/deep").createDirectories()
        Files.write(nested.resolve("x.txt"), byteArrayOf(2))
        ops.perform(FileOp.DELETE, dir.resolve("tree").toString(), null)
        assertTrue(!Files.exists(dir.resolve("tree")))
    }

    @Test
    fun refusesImpossibleRequests() {
        assertFailsWith<BadArgsException> { ops.perform(FileOp.LIST, dir.resolve("missing").toString(), null) }
        assertFailsWith<BadArgsException> { ops.perform(FileOp.READ, dir.toString(), null) }
        assertFailsWith<BadArgsException> { ops.perform(FileOp.DELETE, dir.resolve("missing").toString(), null) }
        assertFailsWith<BadArgsException> { ops.perform(FileOp.WRITE, dir.resolve("x").toString(), null) }
        assertFailsWith<BadArgsException> { ops.perform(FileOp.LIST, " ", null) }
    }

    @Test
    fun stopsAListingAtTheEntryCap() {
        val crowded = dir.resolve("crowded").createDirectories()
        for (index in 0 until 6) Files.write(crowded.resolve("f$index.txt"), byteArrayOf(0))
        val capped = FileOps(maxEntries = 3, home = dir)
        assertEquals(3, capped.perform(FileOp.LIST, crowded.toString(), null).entries?.size)
    }

    @Test
    fun readRejectsADirectoryButAcceptsAnEmptyFile() {
        val empty = dir.resolve("empty.txt").also { Files.write(it, ByteArray(0)) }
        val result = ops.perform(FileOp.READ, empty.toString(), null)
        assertEquals(0L, result.bytes)
        assertEquals("", B64Url.decode(result.dataB64!!).let { String(it) })
    }

    @Test
    fun pathOfTempDirIsAbsolute() {
        assertTrue(Path.of(dir.toString()).isAbsolute)
    }
}
