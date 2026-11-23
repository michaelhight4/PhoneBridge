package dev.phonebridge.executor

/** One `files list` entry: `{name, path, is_dir, size, mtime}`. `mtime` is epoch milliseconds. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "path" to path,
        "is_dir" to isDir,
        "size" to size,
        "mtime" to mtime,
    )
}

/** `files list` output plus whether the directory had more entries than we are willing to send. */
data class FileListing(
    val entries: List<FileEntry>,
    val truncated: Boolean,
)

/** A shell command that ran but did not do what was asked. Mapped to `internal` by the handler. */
class ShellCommandException(message: String) : Exception(message)

/**
 * `files` implemented as `sh` commands run through Shizuku, as the shell user.
 *
 * The commands are deliberately boring POSIX/toybox: `stat` for metadata, `head -c` for a bounded
 * read, `cat` from stdin for a write and `rm -rf` for a delete. Paths are single-quoted (see
 * [ShellQuote]) and validated by the handler before they get here.
 */
class ShellFiles(private val shell: ShizukuShell) {

    /**
     * Lists [dir]. One entry per child, dotfiles included, capped at [maxEntries]; the result says so
     * when the cap was hit so a bot can ask for a narrower path instead of silently missing files.
     *
     * The script prints two lines per entry (name, then `isDir size mtimeSeconds`) so a filename with
     * spaces, quotes or a `%` cannot confuse the parser.
     */
    fun list(dir: String, maxEntries: Int = MAX_ENTRIES): FileListing {
        val quoted = ShellQuote.quote(dir)
        val script = buildString {
            append("d=").append(quoted).append("; ")
            append("if [ ! -d \"\$d\" ]; then echo \"not a directory: \$d\" >&2; exit 2; fi; ")
            append("for f in \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?*; do ")
            append("[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; ")
            append("if [ -d \"\$f\" ]; then t=1; else t=0; fi; ")
            append("printf '%s\\n%d ' \"\$f\" \"\$t\"; ")
            append("stat -c '%s %Y' -- \"\$f\" 2>/dev/null || echo '0 0'; ")
            append("done")
        }
        ShellQuote.checkScriptLength(script)

        val result = shell.exec(script, LIST_TIMEOUT_MS)
        if (result.timedOut) throw ShellCommandException("listing '$dir' timed out")
        if (result.launchFailed) throw ShellCommandException(result.stderr.ifBlank { "the shell could not be started" })
        if (result.exit != 0) {
            throw ShellCommandException(result.stderr.trim().ifBlank { "listing '$dir' failed with exit ${result.exit}" })
        }

        val lines = result.stdout.trimEnd('\n', '\r').split('\n')
        val entries = ArrayList<FileEntry>()
        var index = 0
        while (index + 1 < lines.size) {
            val name = lines[index]
            val meta = lines[index + 1].trim().split(' ')
            index += 2
            if (name.isEmpty() || meta.size < 3) continue
            val isDir = meta[0] == "1"
            val size = meta[1].toLongOrNull() ?: 0L
            val mtimeSeconds = meta[2].toLongOrNull() ?: 0L
            entries += FileEntry(
                name = name.substringAfterLast('/').ifEmpty { name },
                path = name,
                isDir = isDir,
                size = size,
                mtime = mtimeSeconds * 1000L,
            )
            if (entries.size >= maxEntries) {
                return FileListing(entries, truncated = true)
            }
        }
        return FileListing(entries, truncated = false)
    }

    /**
     * Reads at most [maxBytes] bytes. `head -c (maxBytes + 1)` lets us tell "exactly at the cap" from
     * "larger than the cap" without a second round trip.
     */
    fun read(path: String, maxBytes: Int = MAX_READ_BYTES): ByteArray {
        val script = "head -c ${maxBytes + 1} -- ${ShellQuote.quote(path)}"
        ShellQuote.checkScriptLength(script)
        val result = shell.exec(script, READ_TIMEOUT_MS)
        if (result.timedOut) throw ShellCommandException("reading '$path' timed out")
        if (result.launchFailed) throw ShellCommandException(result.stderr.ifBlank { "the shell could not be started" })
        if (result.exit != 0) {
            throw ShellCommandException(result.stderr.trim().ifBlank { "reading '$path' failed with exit ${result.exit}" })
        }
        if (result.stdoutBytes.size > maxBytes) {
            throw ShellCommandException("'$path' is larger than $maxBytes bytes; read it in parts with shell")
        }
        return result.stdoutBytes
    }

    /** Writes [data] to [path] byte for byte, creating or truncating it. Returns the byte count. */
    fun write(path: String, data: ByteArray): Int {
        val script = "cat > ${ShellQuote.quote(path)}"
        ShellQuote.checkScriptLength(script)
        val result = shell.exec(script, WRITE_TIMEOUT_MS, stdin = data)
        if (result.timedOut) throw ShellCommandException("writing '$path' timed out")
        if (result.launchFailed) throw ShellCommandException(result.stderr.ifBlank { "the shell could not be started" })
        if (result.exit != 0) {
            throw ShellCommandException(result.stderr.trim().ifBlank { "writing '$path' failed with exit ${result.exit}" })
        }
        return data.size
    }

    /** Deletes [path] and, when it is a directory, everything below it. */
    fun delete(path: String) {
        val script = "rm -rf -- ${ShellQuote.quote(path)}"
        ShellQuote.checkScriptLength(script)
        val result = shell.exec(script, DELETE_TIMEOUT_MS)
        if (result.timedOut) throw ShellCommandException("deleting '$path' timed out")
        if (result.launchFailed) throw ShellCommandException(result.stderr.ifBlank { "the shell could not be started" })
        if (result.exit != 0) {
            throw ShellCommandException(result.stderr.trim().ifBlank { "deleting '$path' failed with exit ${result.exit}" })
        }
    }

    companion object {
        const val MAX_ENTRIES: Int = 2_000

        /** Keeps a `files read` result inside one 8 MiB transport message. */
        const val MAX_READ_BYTES: Int = 4 * 1024 * 1024

        /** A write arrives over the same transport, so the payload is already bounded upstream. */
        const val MAX_WRITE_BYTES: Int = 32 * 1024 * 1024

        const val LIST_TIMEOUT_MS: Long = 30_000L
        const val READ_TIMEOUT_MS: Long = 60_000L
        const val WRITE_TIMEOUT_MS: Long = 120_000L
        const val DELETE_TIMEOUT_MS: Long = 60_000L
    }
}
