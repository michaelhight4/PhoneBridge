package dev.phonebridge.desktop

import dev.phonebridge.desktop.ui.QrCode
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrCodeTest {

    @Test
    fun drawsASquareQrForAPairingLink() {
        val link = "phonebridge://pair?d=" + "A".repeat(180)
        val image = QrCode.image(link, 160)
        assertEquals(160, image.width)
        assertEquals(160, image.height)
        var black = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (image.getRGB(x, y) == Color.BLACK.rgb) black++
            }
        }
        assertTrue(black > 100, "a QR code needs dark modules, found $black")
    }

    @Test
    fun differentLinksProduceDifferentBitmaps() {
        val first = QrCode.image("phonebridge://pair?d=aaa", 96)
        val second = QrCode.image("phonebridge://pair?d=bbb", 96)
        val differing = (0 until first.height * first.width).count { index ->
            val x = index % first.width
            val y = index / first.width
            first.getRGB(x, y) != second.getRGB(x, y)
        }
        assertTrue(differing > 0)
    }
}
