# `@nexa/websocket`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-URLSession%20%2F%20OkHttp-blue.svg)](https://developer.apple.com/documentation/foundation/urlsessionwebsockettask)

High-performance native text and binary WebSockets over standard RFC 6455.

Backed by Apple `URLSessionWebSocketTask` on iOS and Square `OkHttp` on Android. Zero JavaScript bridges, direct socket multiplexing, and support for binary byte buffers.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.websocket" as Net

component LiveChatScreen() {
    let socket = Net.WebSocket("wss://chat.example.com/live")
    state messages: Array<String> = []
    state inputMessage: String = ""

    onAppear(() => {
        setupSocket()
    })

    onDisappear(() => {
        socket.close()
        socket.dispose()
    })

    fn setupSocket() {
        socket.onMessageReceived((text) => {
            messages = [...messages, text]
        })

        socket.onStateChanged((state) => {
            print("Socket state: \(state)")
        })

        try {
            await socket.connect()
        } catch Net.WebSocketError as err {
            print("Connection error: \(err)")
        }
    }

    fn sendMessage() {
        if inputMessage != "" {
            socket.send(inputMessage)
            inputMessage = ""
        }
    }

    VStack(spacing: 12) {
        FastList(messages) { msg in
            Text(msg, size: 14)
        }
        HStack {
            TextInput("Type message...", text: inputMessage)
            Button("Send", action: () => { sendMessage() })
        }
    }
}
```

---

## 2. API Reference

### `WebSocket` Native Class

```nexa
native class WebSocket {
    init(url: String)
}
```

#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `state` | `WebSocketState` | Read-only | Current lifecycle status of the socket connection |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `connect()` | `Void` | Initiates WebSocket HTTP upgrade handshake. Throws on invalid scheme. |
| `send(text: String)` | `Bool` | Queues UTF-8 text frame into native socket buffer. Returns `true` if accepted by queue. |
| `sendBytes(bytes: Bytes)` | `Bool` | Queues binary frame into native socket buffer. |
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
- `idle`: Socket instantiated but `connect()` has not been called.
- `connecting`: TCP handshake and HTTP upgrade in progress.
- `open`: Two-way bidirectional communication active.
- `closing`: Close frame sent or received; socket awaiting teardown.
- `closed`: Socket cleanly disconnected.
- `failed`: Terminal network or protocol error.

---

### Error Handling (`WebSocketError`)

| Variant | Description |
|---|---|
| `invalidUrl` | URL must specify a valid `ws://` or `wss://` URI |
| `alreadyConnected` | Cannot call `connect()` on a socket that is already connected or connecting |
