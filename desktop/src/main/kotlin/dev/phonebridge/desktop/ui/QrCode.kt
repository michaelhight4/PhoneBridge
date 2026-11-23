package dev.phonebridge.desktop.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.MultiFormatWriter
import java.awt.Color
import java.awt.image.BufferedImage

/** Renders the `phonebridge://pair?...` link as a QR bitmap for the pairing window. */
object QrCode {
    fun image(payload: String, size: Int = 240): BufferedImage {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return image
    }
}
