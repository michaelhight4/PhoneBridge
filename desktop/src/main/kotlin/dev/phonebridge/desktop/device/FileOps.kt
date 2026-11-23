package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PhoneBridgeException
import dev.phonebridge.core.model.FileEntry
import dev.phonebridge.core.model.FileOp
import dev.phonebridge.core.model.FilesResult
import dev.phonebridge.core.util.B64Url
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * `files` for the desktop: list, read, write and delete on this PC's file system.
 *
 * Full level only (the executor enforces that). Reads and writes are capped so a bot cannot pull a
 * multi-gigabyte disk image through the encrypted transport in one message, and a listing is capped so
 * a huge folder cannot build an enormous JSON message.
 */
class FileOps(
    private val maxReadBytes: Long = DEFAULT_MAX_READ,
    private val maxWriteBytes: Long = DEFAULT_MAX_WRITE,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val home: Path = Path.of(System.getProperty("user.home", ".")),
) {
    fun perform(op: FileOp, path: String, data: ByteArray?): FilesResult = when (op) {
        FileOp.LIST -> list(path)
        FileOp.READ -> read(path)
        FileOp.WRITE -> {
            val payload = data ?: throw BadArgsException("files write needs data_b64")
            write(path, payload)
        }

        FileOp.DELETE -> delete(path)
    }

    fun list(path: String): FilesResult {
        val target = resolve(path)
        if (!Files.isDirectory(target)) throw BadArgsException("not a directory: $target")
        val entries = ArrayList<FileEntry>()
        try {
            Files.newDirectoryStream(target).use { stream ->
                for (entry in stream) {
                    if (entries.size >= maxEntries) break
                    entries.add(describe(entry))
                }
            }
        } catch (e: IOException) {
            throw PhoneBridgeException("cannot list $target: ${e.message}", e)
        }
        return FilesResult(op = "list", entries = entries)
    }

    fun read(path: String): FilesResult {
        val target = resolve(path)
        if (!Files.isRegularFile(target)) throw BadArgsException("not a file: $target")
        val size = runCatching { Files.size(target) }.getOrDefault(0L)
        if (size > maxReadBytes) {
            throw BadArgsException("file is $size bytes; files read is capped at $maxReadBytes")
        }
        val bytes = try {
            Files.readAllBytes(target)
        } catch (e: IOException) {
            throw PhoneBridgeException("cannot read $target: ${e.message}", e)
        }
        if (bytes.size > maxReadBytes) {
            throw BadArgsException("file is ${bytes.size} bytes; files read is capped at $maxReadBytes")
        }
        return FilesResult(op = "read", dataB64 = B64Url.encode(bytes), bytes = bytes.size.toLong())
    }

    fun write(path: String, data: ByteArray): FilesResult {
        if (data.size > maxWriteBytes) {
            throw BadArgsException("write is ${data.size} bytes; files write is capped at $maxWriteBytes")
        }
        val target = resolve(path)
        try {
            target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.write(target, data)
        } catch (e: IOException) {
            throw PhoneBridgeException("cannot write $target: ${e.message}", e)
        }
        return FilesResult(op = "write", ok = true, bytes = data.size.toLong())
    }

    fun delete(path: String): FilesResult {
        val target = resolve(path)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw BadArgsException("no such file or directory: $target")
        }
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(target).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            } else {
                Files.deleteIfExists(target)
            }
        } catch (e: IOException) {
            throw PhoneBridgeException("cannot delete $target: ${e.message}", e)
        }
        return FilesResult(op = "delete", ok = true, bytes = null)
    }

    private fun describe(path: Path): FileEntry {
        val attributes: BasicFileAttributes? = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        return FileEntry(
            name = path.fileName?.toString() ?: path.toString(),
            path = path.toString(),
            isDir = attributes?.isDirectory ?: Files.isDirectory(path),
            size = if (attributes?.isRegularFile == true) attributes.size() else null,
            mtime = attributes?.lastModifiedTime()?.toMillis(),
        )
    }

    /** Absolute paths are used as given; relative ones resolve against the user's home directory. */
    private fun resolve(raw: String): Path {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw BadArgsException("path must not be blank")
        val path = try {
            Path.of(trimmed)
        } catch (e: Exception) {
            throw BadArgsException("path is not valid: $raw")
        }
        return if (path.isAbsolute) path.normalize() else home.resolve(path).normalize()
    }

    companion object {
        const val DEFAULT_MAX_READ = 4L * 1024 * 1024
        const val DEFAULT_MAX_WRITE = 8L * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 2000
    }
}
