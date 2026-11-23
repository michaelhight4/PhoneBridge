package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.chunk.Chunker

/** What a bot asked `screen_stream` for. All limits are enforced by [ScreenStreamer.run]. */
data class StreamRequest(
    val fps: Int = DEFAULT_FPS,
    val frames: Int = DEFAULT_FRAMES,
    val quality: Int = DEFAULT_QUALITY,
    val maxWidth: Int? = DEFAULT_MAX_WIDTH,
    val format: String = "jpeg",
) {
    fun validate() {
        if (fps !in 1..MAX_FPS) throw BadArgsException("fps must be 1..$MAX_FPS")
        if (frames !in 1..MAX_FRAMES) throw BadArgsException("frames must be 1..$MAX_FRAMES")
        if (quality !in 1..100) throw BadArgsException("quality must be 1..100")
        maxWidth?.let {
            if (it !in 16..16384) throw BadArgsException("max_width must be 16..16384")
        }
        if (format != "jpeg" && format != "png") throw BadArgsException("format must be jpeg or png")
    }

    companion object {
        const val MAX_FPS = 30
        const val MAX_FRAMES = 300
        const val DEFAULT_FPS = 5
        const val DEFAULT_FRAMES = 10
        const val DEFAULT_QUALITY = 60
        const val DEFAULT_MAX_WIDTH = 1280
    }
}

/** Summary returned once a stream finished (or the receiver stopped asking for frames). */
data class StreamResult(
    val streamId: String,
    val framesSent: Int,
    val width: Int,
    val height: Int,
    val mime: String,
    val stoppedEarly: Boolean,
) {
    val frames: Int
        get() = framesSent
}

/**
 * Captures JPEG (or PNG) frames at a capped frame rate and hands each one to [run]'s emitter.
 *
 * The clock and the sleep function are injected, so the timing rules — never faster than the requested
 * fps, never more than the requested frames — are unit tested without waiting in real time.
 */
class ScreenStreamer(
    private val source: ScreenSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    private val newStreamId: () -> String = { Chunker.newChunkId() },
) {
    /**
     * Streams [request.frames] frames and returns when the frame budget is used or [emit] returns false
     * (the bot disconnected or stopped the stream).
     */
    fun run(
        request: StreamRequest,
        screenId: String? = null,
        emit: (streamId: String, frame: ImageFrame, index: Int) -> Boolean,
    ): StreamResult {
        request.validate()
        val intervalMs = (1000.0 / request.fps).toLong().coerceAtLeast(1L)
        val streamId = newStreamId()
        val startedAt = clock()
        var last: ImageFrame? = null
        var sent = 0
        var stoppedEarly = false
        for (index in 0 until request.frames) {
            if (index > 0) {
                val due = startedAt + intervalMs * index
                val wait = due - clock()
                if (wait > 0) pause(wait)
            }
            val frame = source.capture(screenId, request.format, request.quality, request.maxWidth)
            last = frame
            sent++
            if (!emit(streamId, frame, index)) {
                stoppedEarly = true
                break
            }
        }
        return StreamResult(
            streamId = streamId,
            framesSent = sent,
            width = last?.width ?: 0,
            height = last?.height ?: 0,
            mime = last?.mime ?: if (request.format == "png") "image/png" else "image/jpeg",
            stoppedEarly = stoppedEarly,
        )
    }
}
