# `dev.nexa.websocket`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-URLSession%20%2F%20OkHttp-blue.svg)](https://developer.apple.com/documentation/foundation/urlsessionwebsockettask)

High-performance native text and binary WebSockets over standard RFC 6455.

Backed by Apple `URLSessionWebSocketTask` on iOS and Square `OkHttp` on Android. Zero JavaScript bridges, direct socket multiplexing, and support for binary byte buffers.

---

> **Android minimum API:** 21. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/websocket" as WebSocket

app EchoChat {
    let socket = WebSocket.WebSocket("wss://echo.websocket.events")
    state message: String = "Hello from Nexa"
    state received: String = ""
    state status: String = "Connecting"
    state connectTask: TaskHandle? = null

    body {
        OnAppear {
            socket.messageReceived { text -> received = text }
            socket.stateChanged { current -> status = "Connection state changed" }
            Task.launch(handle: connectTask, executor: TaskExecutor.Main) {
                try {
                    await socket.connect()
                    status = "Connected"
                } catch {
                    status = "Could not connect to the echo service"
                }
            }
        }
        OnDisappear { socket.dispose() }
        Column(spacing: 12) {
            Text(status)
            Text("Last reply: " + received)
            Button("Send message") {
                let queued = socket.send(message)
                if queued {
                    status = "Message queued for sending"
                } else {
                    status = "Not connected"
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `WebSocket` handle

The asynchronous `connect()` call begins the connection attempt; connection state and transport failures arrive through events. Dispose the handle when the owning screen or service ends.

| Constructor | Signature | Description |
|---|---|---|
| `WebSocket` | `WebSocket(url: String)` | Creates a socket handle for the supplied `ws://` or `wss://` URL. |


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `state` | `WebSocketState` | Read-only | Current lifecycle status of the socket connection |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `connect()` | `async -> Void throws WebSocketError` | Starts the WebSocket handshake; throws `invalidUrl` for an unsupported or malformed URL and `alreadyConnected` when a connection is already active. |
| `send(text: String)` | `Bool` | Queues a UTF-8 text frame. `true` means the local queue accepted it, not that the peer received it. |
| `sendBytes(bytes: Bytes)` | `Bool` | Queues a binary frame. `true` means the local queue accepted it, not that the peer received it. |
| `close()` | `Void` | Performs normal RFC 6455 close handshake (Code 1000). |
| `dispose()` | `Void` | Forcibly terminates socket task, closes network sockets, and unregisters listeners. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `stateChanged` | `state: WebSocketState` | Fired when connection transitions between handshake, open, closing, or closed |
| `messageReceived` | `text: String` | Fired upon arrival of a completed UTF-8 text message |
| `binaryReceived` | `bytes: Bytes` | Fired upon arrival of a binary message buffer |
| `failed` | `message: String` | Fired on transport aborts, DNS errors, or protocol violations |

---

### Data Structures & Enums

#### `WebSocketState`

| Case | Description |
|---|---|
| `idle` | Socket was created, but `connect()` has not been called. |
| `connecting` | TCP connection and WebSocket upgrade are in progress. |
| `open` | Bidirectional communication is active. |
| `closing` | A close frame was sent or received; teardown is pending. |
| `closed` | Socket disconnected cleanly. |
| `failed` | A transport or protocol failure occurred. |

---

### Error Handling (`WebSocketError`)

| Variant | Description |
|---|---|
| `invalidUrl` | URL must specify a valid `ws://` or `wss://` URI |
| `alreadyConnected` | Cannot call `connect()` on a socket that is already connected or connecting |
