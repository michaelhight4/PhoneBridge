package dev.phonebridge.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Tray and window icons, drawn in code so the app ships no binary assets. */
object Branding {
    fun trayImage(size: Int = 16): BufferedImage = bridgeGlyph(size, Color(0x2E, 0x7D, 0x32))

    fun appImage(size: Int = 64): BufferedImage = bridgeGlyph(size, Color(0x2E, 0x7D, 0x32))

    /** A dot on each side joined by an arch: one side is the bot, the other the PC. */
    private fun bridgeGlyph(size: Int, colour: Color): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val stroke = (size / 8f).coerceAtLeast(1.5f)
            graphics.stroke = BasicStroke(stroke)
            graphics.color = colour
            val margin = size * 0.18
            graphics.drawArc(
                (margin - size * 0.05).toInt(),
                (margin - size * 0.05).toInt(),
                (size * 0.7).toInt(),
                (size * 0.7).toInt(),
                0,
                180,
            )
            val dot = (size * 0.18).toInt().coerceAtLeast(2)
            graphics.fillOval((margin).toInt(), (size * 0.6).toInt(), dot, dot)
            graphics.fillOval((size - margin - dot).toInt(), (size * 0.6).toInt(), dot, dot)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
