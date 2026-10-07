package dev.nexa.websocket

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
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
    private var maximumReconnectAttempts = 0

    @Volatile
    private var disposed = false

    @Volatile
    override var state: WebSocketState = WebSocketState.idle
        private set

    @Volatile
    private var socket: WebSocket? = null

    private var request: Request? = null
    private var closeRequested = false
    private var reconnectAttempts = 0
    private var pendingReconnect: Runnable? = null

    override var onStateChanged: ((WebSocketState) -> Unit)? = null
    override var onReconnecting: ((Int, Int) -> Unit)? = null
    override var onMessageReceived: ((String) -> Unit)? = null
    override var onBinaryReceived: ((ByteArray) -> Unit)? = null
    override var onFailed: ((String) -> Unit)? = null

    override fun configureReconnect(maxAttempts: Int) {
        synchronized(lock) {
            if (disposed || state != WebSocketState.idle) return
            maximumReconnectAttempts = maxAttempts.coerceAtLeast(0)
        }
    }

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

            request = try {
                Request.Builder().url(url).build()
            } catch (_: IllegalArgumentException) {
                transition(WebSocketState.failed)
                throw WebSocketError.invalidUrl
            }

            transition(WebSocketState.connecting)
            val connectionRequest = request
            if (connectionRequest == null) {
                transition(WebSocketState.failed)
                throw WebSocketError.invalidUrl
            }
            try {
                socket = WebSocketClient.instance.newWebSocket(connectionRequest, listener)
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
            closeRequested = true
            pendingReconnect?.let(mainHandler::removeCallbacks)
            pendingReconnect = null
            if (state == WebSocketState.idle || state == WebSocketState.reconnecting) {
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
            closeRequested = true
            pendingReconnect?.let(mainHandler::removeCallbacks)
            pendingReconnect = null
            val previous = socket
            socket = null
            state = WebSocketState.closed
            onStateChanged = null
            onReconnecting = null
            onMessageReceived = null
            onBinaryReceived = null
            onFailed = null
            previous
        }
        current?.cancel()
    }

    private val listener = WebSocketListenerAdapter(this)

    internal fun onSocketOpen(webSocket: WebSocket) {
        synchronized(lock) {
            if (socket !== webSocket || disposed) return
            closeRequested = false
            reconnectAttempts = 0
            transition(WebSocketState.open)
        }
    }

    internal fun onSocketMessage(webSocket: WebSocket, text: String) {
        if (socket === webSocket) dispatch { onMessageReceived?.invoke(text) }
    }

    internal fun onSocketMessage(webSocket: WebSocket, bytes: ByteString) {
        if (socket === webSocket) {
            val message = bytes.toByteArray()
            dispatch { onBinaryReceived?.invoke(message) }
        }
    }

    internal fun onSocketClosing(webSocket: WebSocket, code: Int, reason: String) {
        synchronized(lock) {
            if (socket !== webSocket || disposed) return
            transition(WebSocketState.closing)
        }
        webSocket.close(code, reason)
    }

    internal fun onSocketClosed(webSocket: WebSocket, code: Int) {
        synchronized(lock) {
            if (socket !== webSocket) return
            socket = null
            if (disposed) return
            if (closeRequested || code == 1000) {
                transition(WebSocketState.closed)
            } else {
                failOrReconnectLocked("WebSocket closed with code $code")
            }
        }
    }

    internal fun onSocketFailure(webSocket: WebSocket, error: Throwable) {
        synchronized(lock) {
            if (socket !== webSocket) return
            socket = null
            if (disposed) return
            if (closeRequested) {
                transition(WebSocketState.closed)
            } else {
                failOrReconnectLocked(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun failOrReconnectLocked(message: String) {
        if (scheduleReconnectLocked(message)) return
        if (transition(WebSocketState.failed)) {
            dispatch {
                if (state == WebSocketState.failed) onFailed?.invoke(message)
            }
        }
    }

    private fun scheduleReconnectLocked(message: String): Boolean {
        if (reconnectAttempts >= maximumReconnectAttempts) return false
        reconnectAttempts += 1
        val attempt = reconnectAttempts
        val delayMillis = reconnectDelayMillis(attempt)
        if (!transition(WebSocketState.reconnecting)) return false
        dispatch {
            if (state == WebSocketState.reconnecting && reconnectAttempts == attempt) {
                onReconnecting?.invoke(attempt, delayMillis)
            }
        }

        val callback = Runnable { startReconnect(attempt, message) }
        pendingReconnect = callback
        if (!mainHandler.postDelayed(callback, delayMillis.toLong())) {
            pendingReconnect = null
            if (transition(WebSocketState.failed)) {
                dispatch { onFailed?.invoke(message) }
            }
        }
        return true
    }

    private fun startReconnect(attempt: Int, previousFailure: String) {
        synchronized(lock) {
            if (disposed || state != WebSocketState.reconnecting || reconnectAttempts != attempt) return
            pendingReconnect = null
            val currentRequest = request
            if (currentRequest == null) {
                failOrReconnectLocked(previousFailure)
                return
            }
            if (!transition(WebSocketState.connecting)) return
            try {
                socket = WebSocketClient.instance.newWebSocket(currentRequest, listener)
            } catch (error: IllegalArgumentException) {
                socket = null
                failOrReconnectLocked(error.message ?: previousFailure)
            }
        }
    }

    private fun reconnectDelayMillis(attempt: Int): Int {
        val exponent = (attempt - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (INITIAL_RECONNECT_DELAY_MILLIS shl exponent).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
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

    private inline fun dispatch(crossinline callback: () -> Unit) {
        mainHandler.post {
            if (!disposed) callback()
        }
    }

    private fun canTransition(from: WebSocketState, to: WebSocketState): Boolean = when (from) {
        WebSocketState.idle -> to == WebSocketState.connecting ||
            to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.connecting -> to == WebSocketState.open ||
            to == WebSocketState.reconnecting || to == WebSocketState.closing ||
            to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.open -> to == WebSocketState.reconnecting ||
            to == WebSocketState.closing || to == WebSocketState.closed ||
            to == WebSocketState.failed
        WebSocketState.reconnecting -> to == WebSocketState.connecting ||
            to == WebSocketState.closing || to == WebSocketState.closed ||
            to == WebSocketState.failed
        WebSocketState.closing -> to == WebSocketState.reconnecting ||
            to == WebSocketState.closed || to == WebSocketState.failed
        WebSocketState.closed, WebSocketState.failed -> false
    }
}

/**
 * OkHttp retains listeners for the lifetime of a connection. Keep that
 * registration from extending the native plugin instance's lifetime.
 */
private class WebSocketListenerAdapter(owner: WebSocketImpl) : WebSocketListener() {
    private val owner = WeakReference(owner)

    override fun onOpen(webSocket: WebSocket, response: Response) {
        owner.get()?.onSocketOpen(webSocket) ?: webSocket.cancel()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        owner.get()?.onSocketMessage(webSocket, text) ?: webSocket.cancel()
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        owner.get()?.onSocketMessage(webSocket, bytes) ?: webSocket.cancel()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        owner.get()?.onSocketClosing(webSocket, code, reason) ?: webSocket.cancel()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        owner.get()?.onSocketClosed(webSocket, code) ?: webSocket.cancel()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        owner.get()?.onSocketFailure(webSocket, t)
    }
}

private const val INITIAL_RECONNECT_DELAY_MILLIS = 250
private const val MAX_RECONNECT_DELAY_MILLIS = 8_000
private const val MAX_BACKOFF_EXPONENT = 5
