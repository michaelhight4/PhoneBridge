package dev.phonebridge.desktop.device

import dev.phonebridge.core.BadArgsException
import dev.phonebridge.core.PhoneBridgeException
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Screen capture through `java.awt.Robot`, which works for every monitor of a Windows desktop in one
 * coordinate space.
 *
 * - `screen_id` null captures the whole virtual desktop (all monitors stitched together).
 * - `screen_id` is one of the ids from [screens] (`"0"`, `"1"`, …).
 * - JPEG quality 1..100 and `max_width` downscaling are applied before encoding.
 */
class AwtScreenSource(
    private val robotProvider: () -> Robot = { Robot() },
) : ScreenSource {
    private val robot: Robot by lazy { robotProvider() }

    override fun screens(): List<ScreenInfo> {
        val environment = try {
            GraphicsEnvironment.getLocalGraphicsEnvironment()
        } catch (e: Exception) {
            return emptyList()
        }
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        val primary = runCatching { environment.defaultScreenDevice }.getOrNull()
        return environment.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            ScreenInfo(
                id = index.toString(),
                x = bounds.x,
                y = bounds.y,
                width = bounds.width,
                height = bounds.height,
                primary = device == primary,
            )
        }
    }

    override fun capture(screenId: String?, format: String, quality: Int, maxWidth: Int?): ImageFrame {
        if (format != "png" && format != "jpeg") throw BadArgsException("format must be png or jpeg")
        if (quality !in 1..100) throw BadArgsException("quality must be 1..100")
        maxWidth?.let {
            if (it !in 16..16384) throw BadArgsException("max_width must be 16..16384")
        }
        val rect = when (val id = screenId?.trim()?.takeIf { it.isNotEmpty() }) {
            null -> virtualBounds() ?: throw PhoneBridgeException("no display is available")
            else -> {
                val screen = screens().firstOrNull { it.id == id }
                    ?: throw BadArgsException("no screen with id $id; ask for `screens` first")
                Rectangle(screen.x, screen.y, screen.width, screen.height)
            }
        }
        val captured = try {
            robot.createScreenCapture(rect)
        } catch (e: Exception) {
            throw PhoneBridgeException("screen capture failed: ${e.message}", e)
        }
        val scaled = scaleDown(captured, maxWidth)
        val bytes = encode(scaled, format, quality)
        return ImageFrame(
            bytes = bytes,
            width = scaled.width,
            height = scaled.height,
            mime = if (format == "png") "image/png" else "image/jpeg",
        )
    }

    private fun virtualBounds(): Rectangle? {
        val all = screens()
        if (all.isEmpty()) return null
        var union = Rectangle(all.first().x, all.first().y, all.first().width, all.first().height)
        for (screen in all.drop(1)) union = union.union(Rectangle(screen.x, screen.y, screen.width, screen.height))
        return union
    }

    private fun scaleDown(image: BufferedImage, maxWidth: Int?): BufferedImage {
        if (maxWidth == null || image.width <= maxWidth) return image
        val height = (image.height.toDouble() * maxWidth / image.width).toInt().coerceAtLeast(1)
        val target = BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB)
        val graphics: Graphics2D = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, 0, 0, maxWidth, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encode(image: BufferedImage, format: String, quality: Int): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image else toRgb(image)
        val out = ByteArrayOutputStream(256 * 1024)
        if (format == "png") {
            ImageIO.write(rgb, "png", out)
            return out.toByteArray()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val params = writer.defaultWriteParam
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = quality / 100f
            ImageIO.createImageOutputStream(out).use { imageOut ->
                writer.output = imageOut
                writer.write(null, IIOImage(rgb, null, null), params)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun toRgb(image: BufferedImage): BufferedImage {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return rgb
    }
}
