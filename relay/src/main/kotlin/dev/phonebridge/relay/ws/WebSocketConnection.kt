package dev.phonebridge.relay.ws

import dev.phonebridge.core.crypto.Primitives
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException

/** A protocol level failure; [closeCode] is the WebSocket close code sent to the peer. */
class WebSocketException(message: String, val closeCode: Int = 1002) : IOException(message)

/**
 * A minimal RFC 6455 text/binary connection (no extensions, no compression).
 *
 * One thread reads with [readMessage]; any thread may write. Frames are size limited so an oversize
 * frame is rejected before it can be buffered.
 */
class WebSocketConnection internal constructor(
    val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    val isClient: Boolean,
    val maxMessageBytes: Int,
    val remoteAddress: String,
    private val log: (String) -> Unit = {}
) {
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    var closeCode: Int? = null
        private set

    @Volatile
    var closeReason: String? = null
        private set

    val isOpen: Boolean get() = !closed

    private var idlePings = 0

    /** Read timeout used while the connection is idle; 0 blocks forever. */
    fun setReadTimeout(millis: Int) {
        try {
            socket.soTimeout = millis
        } catch (_: Exception) {
        }
    }

    fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxMessageBytes) {
            throw WebSocketException("outgoing message is ${bytes.size} bytes (limit $maxMessageBytes)", CLOSE_TOO_BIG)
        }
        sendFrame(OP_TEXT, bytes)
    }

    fun sendBinary(bytes: ByteArray) {
        if (bytes.size > maxMessageBytes) {
            throw WebSocketException("outgoing message is ${bytes.size} bytes (limit $maxMessageBytes)", CLOSE_TOO_BIG)
        }
        sendFrame(OP_BINARY, bytes)
    }

    /** Sends a close frame (best effort) and closes the socket. Safe to call repeatedly. */
    fun close(code: Int = CLOSE_NORMAL, reason: String = "") {
        if (closed) return
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code ushr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
        try {
            sendFrame(OP_CLOSE, payload)
        } catch (_: Exception) {
        }
        closeQuietly(code, reason)
    }

    internal fun closeQuietly(code: Int, reason: String? = null) {
        if (closed) return
        closed = true
        closeCode = code
        closeReason = reason
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Blocks until a complete text or binary message arrives. Returns null when the peer closed the
     * connection. Idle read timeouts send a WebSocket ping (three unanswered pings abort).
     */
    fun readMessage(): ByteArray? {
        var accumulated: java.io.ByteArrayOutputStream? = null
        while (true) {
            val firstByte = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                if (!keepAlive()) return null
                continue
            }
            if (firstByte < 0) {
                closeQuietly(CLOSE_ABNORMAL)
                return null
            }
            idlePings = 0
            val frame = readFrame(firstByte)

            when (frame.opcode) {
                OP_PING -> {
                    sendFrame(OP_PONG, frame.payload)
                    continue
                }
                OP_PONG -> continue
                OP_CLOSE -> {
                    val code = if (frame.payload.size >= 2) {
                        ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                    } else {
                        CLOSE_NORMAL
                    }
                    closeQuietly(code, frame.payload.drop(2).toByteArray().toString(Charsets.UTF_8))
                    return null
                }
                OP_TEXT, OP_BINARY -> {
                    if (frame.fin) return frame.payload
                    accumulated = java.io.ByteArrayOutputStream()
                    appendChecked(accumulated, frame.payload)
                }
                OP_CONTINUATION -> {
                    val buffer = accumulated
                        ?: throw WebSocketException("continuation frame without a message", CLOSE_PROTOCOL)
                    appendChecked(buffer, frame.payload)
                    if (frame.fin) return buffer.toByteArray()
                }
                else -> throw WebSocketException("unsupported opcode ${frame.opcode}", CLOSE_PROTOCOL)
            }
        }
    }

    private fun appendChecked(buffer: java.io.ByteArrayOutputStream, chunk: ByteArray) {
        if (buffer.size().toLong() + chunk.size > maxMessageBytes) {
            throw WebSocketException("message exceeds $maxMessageBytes bytes", CLOSE_TOO_BIG)
        }
        buffer.write(chunk)
    }

    private fun keepAlive(): Boolean {
        if (idlePings >= 3) {
            log("closing $remoteAddress after $idlePings unanswered keep-alive pings")
            closeQuietly(CLOSE_GOING_AWAY, "idle")
            return false
        }
        idlePings++
        try {
            sendFrame(OP_PING, ByteArray(0))
        } catch (_: Exception) {
            closeQuietly(CLOSE_GOING_AWAY, "idle")
            return false
        }
        return true
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(firstByte: Int): Frame {
        val secondByte = readByte()
        val fin = (firstByte and 0x80) != 0
        if ((firstByte and 0x70) != 0) throw WebSocketException("reserved bits set", CLOSE_PROTOCOL)
        val opcode = firstByte and 0x0F
        val masked = (secondByte and 0x80) != 0
        var length = (secondByte and 0x7F).toLong()
        when (length) {
            126L -> length = ((readByte().toLong() shl 8) or readByte().toLong())
            127L -> {
                length = 0
                repeat(8) { length = (length shl 8) or readByte().toLong() }
            }
        }
        if (length < 0) throw WebSocketException("invalid frame length", CLOSE_PROTOCOL)

        val control = opcode >= 0x8
        if (control && (!fin || length > 125)) {
            throw WebSocketException("invalid control frame", CLOSE_PROTOCOL)
        }
        // RFC 6455 5.1: clients mask, servers do not.
        if (isClient && masked) throw WebSocketException("server frames must not be masked", CLOSE_PROTOCOL)
        if (!isClient && !masked) throw WebSocketException("client frames must be masked", CLOSE_PROTOCOL)

        val limit = if (control) 125L else maxMessageBytes.toLong()
        if (length > limit) throw WebSocketException("frame of $length bytes exceeds the limit of $limit", CLOSE_TOO_BIG)

        val maskKey = if (masked) readFully(4) else null
        val payload = if (length == 0L) ByteArray(0) else readFully(length.toInt())
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArray(14)
        var position = 0
        header[position++] = (0x80 or opcode).toByte()
        val maskBit = if (isClient) 0x80 else 0x00
        when {
            payload.size < 126 -> header[position++] = (payload.size or maskBit).toByte()
            payload.size <= 0xFFFF -> {
                header[position++] = (126 or maskBit).toByte()
                header[position++] = ((payload.size ushr 8) and 0xFF).toByte()
                header[position++] = (payload.size and 0xFF).toByte()
            }
            else -> {
                header[position++] = (127 or maskBit).toByte()
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header[position++] = ((size ushr shift) and 0xFF).toByte()
                }
            }
        }
        var body = payload
        if (isClient) {
            val key = Primitives.randomBytes(4)
            System.arraycopy(key, 0, header, position, 4)
            position += 4
            body = ByteArray(payload.size) { i -> (payload[i].toInt() xor key[i and 3].toInt()).toByte() }
        }
        synchronized(writeLock) {
            if (closed) throw IOException("connection is closed")
            output.write(header, 0, position)
            if (body.isNotEmpty()) output.write(body)
            output.flush()
        }
    }

    private fun readByte(): Int {
        val value = input.read()
        if (value < 0) throw EOFException("connection closed mid frame")
        return value
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("connection closed mid frame")
            offset += read
        }
        return buffer
    }

    companion object {
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        const val CLOSE_NORMAL = 1000
        const val CLOSE_GOING_AWAY = 1001
        const val CLOSE_PROTOCOL = 1002
        const val CLOSE_TOO_BIG = 1009
        const val CLOSE_ABNORMAL = 1006

        internal const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}

/** Buffered wrapper used by the server/client after a successful upgrade handshake. */
internal fun webSocketConnection(
    socket: Socket,
    maxMessageBytes: Int,
    isClient: Boolean,
    log: (String) -> Unit
): WebSocketConnection = WebSocketConnection(
    socket = socket,
    input = BufferedInputStream(socket.getInputStream(), 16 * 1024),
    output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024),
    isClient = isClient,
    maxMessageBytes = maxMessageBytes,
    remoteAddress = socket.remoteSocketAddress?.toString() ?: "unknown",
    log = log
)
