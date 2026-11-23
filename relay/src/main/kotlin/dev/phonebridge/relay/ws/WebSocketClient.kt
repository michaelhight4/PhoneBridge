package dev.phonebridge.relay.ws

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Minimal RFC 6455 client used by [dev.phonebridge.relay.client.RelayClient] and by tests.
 *
 * `ws://` and `wss://` are supported; TLS trust (pinned fingerprint or system CAs) is supplied by
 * [dev.phonebridge.relay.crypto.Tls.clientContext].
 */
object WebSocketClient {
    fun connect(
        uri: URI,
        sslContext: SSLContext?,
        timeoutMs: Int,
        maxMessageBytes: Int,
        idleTimeoutMs: Int = 120_000,
        extraHeaders: Map<String, String> = emptyMap(),
        log: (String) -> Unit = {}
    ): WebSocketConnection {
        val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("URI has no scheme: $uri")
        require(scheme == "ws" || scheme == "wss") { "only ws:// and wss:// are supported, got $scheme" }
        val host = uri.host ?: throw IllegalArgumentException("URI has no host: $uri")
        val secure = scheme == "wss"
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val path = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        var socket: Socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            if (secure) {
                val context = sslContext ?: SSLContext.getDefault()
                val ssl = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
                ssl.startHandshake()
                socket = ssl
            }

            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()

            val keyBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                for ((name, value) in extraHeaders) append(name).append(": ").append(value).append("\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val response = readResponse(input)
            if (response.status != 101) {
                throw IOException("WebSocket upgrade failed: HTTP ${response.status} ${response.reason}")
            }
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WebSocketConnection.GUID).toByteArray(Charsets.US_ASCII))
            )
            val accept = response.headers["sec-websocket-accept"]
            if (accept != expected) {
                throw IOException("WebSocket upgrade failed: bad Sec-WebSocket-Accept")
            }

            socket.soTimeout = idleTimeoutMs
            return WebSocketConnection(
                socket = socket,
                input = input,
                output = output,
                isClient = true,
                maxMessageBytes = maxMessageBytes,
                remoteAddress = socket.remoteSocketAddress?.toString() ?: host,
                log = log
            )
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    private class Response(val status: Int, val reason: String, val headers: Map<String, String>)

    private fun readResponse(input: BufferedInputStream): Response {
        val head = java.io.ByteArrayOutputStream()
        var matched = 0
        var total = 0
        while (total < 16 * 1024) {
            val value = input.read()
            if (value < 0) throw IOException("connection closed during the WebSocket handshake")
            head.write(value)
            total++
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> if (value == '\r'.code) 1 else 0
            }
            if (matched == 4) {
                val text = head.toString(Charsets.US_ASCII.name())
                val lines = text.split("\r\n").filter { it.isNotEmpty() }
                val statusLine = lines.firstOrNull()?.split(' ') ?: throw IOException("empty handshake response")
                if (statusLine.size < 2) throw IOException("bad handshake status line: $text")
                val status = statusLine[1].toIntOrNull() ?: throw IOException("bad handshake status: ${statusLine[1]}")
                val headers = LinkedHashMap<String, String>()
                for (line in lines.drop(1)) {
                    val separator = line.indexOf(':')
                    if (separator <= 0) continue
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
                return Response(status, statusLine.drop(2).joinToString(" "), headers)
            }
        }
        throw IOException("handshake response headers too large")
    }
}
