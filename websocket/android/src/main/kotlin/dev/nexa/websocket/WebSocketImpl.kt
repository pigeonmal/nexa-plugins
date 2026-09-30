package dev.nexa.websocket

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

private object WebSocketClient {
    // Reuse OkHttp's dispatcher and connection pool across plugin instances.
    val instance: OkHttpClient = OkHttpClient()
}

public class WebSocketImpl(private val url: String) : WebSocketSpec {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var disposed = false

    @Volatile
    override var state: WebSocketState = WebSocketState.idle
        private set

    @Volatile
    private var socket: WebSocket? = null

    override var onStateChanged: ((WebSocketState) -> Unit)? = null
    override var onMessageReceived: ((String) -> Unit)? = null
    override var onBinaryReceived: ((ByteArray) -> Unit)? = null
    override var onFailed: ((String) -> Unit)? = null

    override suspend fun connect() {
        synchronized(lock) {
            if (disposed || state != WebSocketState.idle) {
                throw WebSocketError.alreadyConnected
            }

            if (!url.startsWith("ws://", ignoreCase = true) &&
                !url.startsWith("wss://", ignoreCase = true)
            ) {
                transition(WebSocketState.failed)
                throw WebSocketError.invalidUrl
            }

            val request = try {
                Request.Builder().url(url).build()
            } catch (_: IllegalArgumentException) {
                transition(WebSocketState.failed)
                throw WebSocketError.invalidUrl
            }

            transition(WebSocketState.connecting)
            try {
                socket = WebSocketClient.instance.newWebSocket(request, listener)
            } catch (_: IllegalArgumentException) {
                transition(WebSocketState.failed)
                throw WebSocketError.invalidUrl
            }
        }
    }

    override fun send(text: String): Boolean = synchronized(lock) {
        if (disposed || state != WebSocketState.open) return@synchronized false
        socket?.send(text) ?: false
    }

    override fun sendBytes(bytes: ByteArray): Boolean = synchronized(lock) {
        if (disposed || state != WebSocketState.open) return@synchronized false
        socket?.send(bytes.toByteString()) ?: false
    }

    override fun close() {
        val current = synchronized(lock) {
            if (disposed || state == WebSocketState.closed || state == WebSocketState.failed) {
                return
            }
            if (state == WebSocketState.idle) {
                transition(WebSocketState.closed)
                return
            }
            if (state != WebSocketState.closing) transition(WebSocketState.closing)
            socket
        }

        if (current == null) {
            transition(WebSocketState.closed)
        } else if (!current.close(1000, null)) {
            current.cancel()
        }
    }

    override fun dispose() {
        val current = synchronized(lock) {
            if (disposed) return
            disposed = true
            val previous = socket
            socket = null
            state = WebSocketState.closed
            onStateChanged = null
            onMessageReceived = null
            onBinaryReceived = null
            onFailed = null
            previous
        }
        current?.cancel()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            transition(WebSocketState.open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatch { onMessageReceived?.invoke(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val message = bytes.toByteArray()
            dispatch { onBinaryReceived?.invoke(message) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            transition(WebSocketState.closing)
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) { socket = null }
            transition(WebSocketState.closed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) { socket = null }
            if (disposed) return
            if (!transition(WebSocketState.failed)) return
            val message = t.message ?: t.javaClass.simpleName
            dispatch { onFailed?.invoke(message) }
        }
    }

    private fun transition(value: WebSocketState): Boolean {
        synchronized(lock) {
            if (disposed || !canTransition(state, value)) return false
            state = value
            mainHandler.post {
                if (!disposed) onStateChanged?.invoke(value)
            }
            return true
        }
    }

    private fun canTransition(from: WebSocketState, to: WebSocketState): Boolean = when (from) {
        WebSocketState.idle -> to == WebSocketState.connecting ||
            to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.connecting -> to == WebSocketState.open ||
            to == WebSocketState.closing || to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.open -> to == WebSocketState.closing ||
            to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.closing -> to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.closed, WebSocketState.failed -> false
    }

    private inline fun dispatch(crossinline callback: () -> Unit) {
        mainHandler.post {
            if (!disposed) callback()
        }
    }
}
