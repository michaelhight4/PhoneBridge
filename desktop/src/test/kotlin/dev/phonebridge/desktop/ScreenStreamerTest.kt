package dev.phonebridge.desktop

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.desktop.device.ScreenStreamer
import dev.phonebridge.desktop.device.StreamRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenStreamerTest {
    private var now = 0L
    private val device = FakeDesktopDevice()
    private val pauses = mutableListOf<Long>()
    private val streamer = ScreenStreamer(
        source = device,
        clock = { now },
        pause = { millis -> pauses.add(millis); now += millis },
        newStreamId = { "stream-1" },
    )

    @Test
    fun streamsTheRequestedFramesAtTheCappedRate() {
        val frames = mutableListOf<Int>()
        val result = streamer.run(StreamRequest(fps = 10, frames = 3, quality = 50, maxWidth = 800)) { id, _, index ->
            assertEquals("stream-1", id)
            frames.add(index)
            true
        }

        assertEquals(listOf(0, 1, 2), frames)
        assertEquals(3, result.framesSent)
        assertEquals("stream-1", result.streamId)
        assertEquals(2, pauses.size, "one pause before each frame after the first")
        assertTrue(pauses.all { it == 100L }, "10 fps means 100 ms between frames, got $pauses")
        assertEquals(3, device.captures.size)
        assertEquals("jpeg", device.captures.first().format)
        assertEquals(50, device.captures.first().quality)
        assertEquals(800, device.captures.first().maxWidth)
    }

    @Test
    fun stopsWhenTheReceiverAsks() {
        var calls = 0
        val result = streamer.run(StreamRequest(fps = 1, frames = 5)) { _, _, _ ->
            calls++
            calls < 2
        }
        assertEquals(2, result.framesSent)
        assertTrue(result.stoppedEarly)
        assertEquals(2, device.captures.size)
    }

    @Test
    fun neverExceedsTheFrameAndRateLimits() {
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(fps = 0, frames = 1)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(fps = 31, frames = 1)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(fps = 5, frames = 0)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(fps = 5, frames = 301)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(quality = 101)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(maxWidth = 8)) { _, _, _ -> true } }
        assertFailsWith<BadArgsException> { streamer.run(StreamRequest(format = "bmp")) { _, _, _ -> true } }
        assertTrue(device.captures.isEmpty(), "invalid requests never reach the screen")
    }

    @Test
    fun pngFormatIsPassedThrough() {
        streamer.run(StreamRequest(fps = 5, frames = 1, format = "png")) { _, _, _ -> true }
        assertEquals("png", device.captures.single().format)
    }
}
