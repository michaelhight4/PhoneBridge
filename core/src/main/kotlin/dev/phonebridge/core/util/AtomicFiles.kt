package dev.phonebridge.core.util

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Replaces [target] with [temp] in one step, retrying briefly.
 *
 * The recipe every store in this project uses is "write a temp file, then rename it over the target",
 * which is atomic with `ATOMIC_MOVE` + `REPLACE_EXISTING`. On Windows that rename can fail with
 * `AccessDeniedException` while another process - a virus scanner, a sync client, or a reader that
 * opened the file a moment ago - still holds the target open. A few short retries turn that into a
 * non-event instead of a spurious crash, and the fallback covers file systems without atomic moves.
 */
object AtomicFiles {

    fun replace(temp: Path, target: Path, attempts: Int = 5, pauseMs: Long = 20) {
        var last: IOException? = null
        repeat(attempts) { attempt ->
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: IOException) {
                last = e
                if (attempt < attempts - 1) Thread.sleep(pauseMs)
            }
        }
        throw last ?: IOException("could not replace $target")
    }
}
