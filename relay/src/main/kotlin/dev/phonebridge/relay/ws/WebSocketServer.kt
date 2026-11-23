package dev.phonebridge.relay.ws

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * A tiny RFC 6455 server: it upgrades HTTP requests that ask for a WebSocket and hands the
 * connection to [start]'s handler on its own thread. Everything else gets a small HTTP response
 * ([healthProvider] answers `GET /healthz`), so a VPS can health check the relay.
 *
 * Plain `ws://` is intentional here: the relay itself decides whether `ws://` is allowed (loopback or
 * `--insecure-dev`), and TLS is supplied through [sslContext].
 */
class WebSocketServer(
    private val bindAddress: String,
    private val requestedPort: Int,
    private val sslContext: SSLContext?,
    private val maxMessageBytes: Int,
    private val idleTimeoutMs: Int = 120_000,
    private val handshakeTimeoutMs: Int = 15_000,
    private val log: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pb-relay-conn").apply { isDaemon = true }
    }
    private val connections = ConcurrentHashMap.newKeySet<WebSocketConnection>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    var port: Int = -1
        private set

    /** Returns the JSON body for `GET /healthz`, or null to answer 404. */
    @Volatile
    var healthProvider: (() -> String)? = null

    val connectionCount: Int get() = connections.size

    fun start(handler: (WebSocketConnection) -> Unit) {
        check(!running.get()) { "server is already started" }
        val address = InetAddress.getByName(bindAddress)
        val socket: ServerSocket = if (sslContext != null) {
            (sslContext.serverSocketFactory.createServerSocket(requestedPort, 64, address) as SSLServerSocket).apply {
                enabledProtocols = supportedProtocols
                    .filter { it.startsWith("TLSv1.") && it != "TLSv1" && it != "TLSv1.1" }
                    .ifEmpty { supportedProtocols.toList() }
                    .toTypedArray()
            }
        } else {
            ServerSocket(requestedPort, 64, address)
        }
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        acceptThread = Thread({ acceptLoop(socket, handler) }, "pb-relay-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        for (connection in connections) connection.closeQuietly(WebSocketConnection.CLOSE_GOING_AWAY, "relay stopping")
        connections.clear()
        executor.shutdownNow()
        acceptThread?.join(1500)
    }

    private fun acceptLoop(socket: ServerSocket, handler: (WebSocketConnection) -> Unit) {
        while (running.get()) {
            val accepted = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) log("accept failed: ${e.message}")
                break
            }
            try {
                executor.execute { handleAccepted(accepted, handler) }
            } catch (_: RejectedExecutionException) {
                try {
                    accepted.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handleAccepted(raw: Socket, handler: (WebSocketConnection) -> Unit) {
        var socket: Socket = raw
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = handshakeTimeoutMs
            if (sslContext != null) {
                val ssl = socket as? SSLSocket ?: throw IOException("TLS socket expected")
                ssl.startHandshake()
            }

            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()
            val request = readRequest(input) ?: run {
                socket.close()
                return
            }

            val upgradeRequested = request.headers["upgrade"]?.lowercase()?.contains("websocket") == true
            if (!upgradeRequested) {
                respondPlain(output, request.path)
                socket.close()
                return
            }

            val key = request.headers["sec-websocket-key"]
            if (key.isNullOrBlank()) {
                respondStatus(output, 400, "Bad Request", "missing Sec-WebSocket-Key")
                socket.close()
                return
            }
            val version = request.headers["sec-websocket-version"]
            if (version != null && version != "13") {
                respondStatus(output, 426, "Upgrade Required", "Sec-WebSocket-Version: 13")
                socket.close()
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key.trim() + WebSocketConnection.GUID).toByteArray(Charsets.US_ASCII))
            )
            val response = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            socket.soTimeout = idleTimeoutMs
            val connection = WebSocketConnection(
                socket = socket,
                input = input,
                output = output,
                isClient = false,
                maxMessageBytes = maxMessageBytes,
                remoteAddress = socket.remoteSocketAddress?.toString() ?: "unknown",
                log = log
            )
            connections.add(connection)
            try {
                handler(connection)
            } finally {
                connections.remove(connection)
                connection.closeQuietly(WebSocketConnection.CLOSE_NORMAL)
            }
        } catch (e: SocketTimeoutException) {
            log("handshake timed out for ${socket.remoteSocketAddress}")
            closeQuietly(socket)
        } catch (e: Exception) {
            log("connection failed: ${e.message}")
            closeQuietly(socket)
        }
    }

    private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): HttpRequest? {
        val head = ByteArray(16 * 1024)
        var length = 0
        var matched = 0
        while (length < head.size) {
            val value = input.read()
            if (value < 0) return null
            head[length++] = value.toByte()
            // Track the CRLFCRLF terminator.
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> if (value == '\r'.code) 1 else 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null
        val text = String(head, 0, length, Charsets.US_ASCII)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ')
        if (requestLine.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return HttpRequest(requestLine[0].uppercase(), requestLine[1], headers)
    }

    private fun respondPlain(output: OutputStream, path: String) {
        val health = healthProvider
        if (path.substringBefore('?') == "/healthz" && health != null) {
            respondStatus(output, 200, "OK", health(), "application/json")
        } else {
            respondStatus(output, 404, "Not Found", "{\"error\":\"not_found\"}", "application/json")
        }
    }

    private fun respondStatus(
        output: OutputStream,
        code: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8"
    ) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        try {
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}
