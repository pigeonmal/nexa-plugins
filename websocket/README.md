# WebSocket

Native text and binary WebSockets for Nexa apps.

```nexa
plugin "dev.nexa.websocket" as WebSocket

let socket = WebSocket.WebSocket("wss://echo.websocket.events")

app Chat {
    state message = "Hello from Nexa"
    state received = ""
    state connection = "Idle"
    state failure = ""

    body {
        OnAppear async {
            socket.stateChanged { current ->
                connection = "Connection state changed"
            }
            socket.messageReceived { text ->
                received = text
            }
            socket.failed { error ->
                failure = error
            }
            try {
                await socket.connect()
            } catch {
                case WebSocket.WebSocketError.invalidUrl {
                    failure = "The WebSocket URL is invalid."
                }
                case WebSocket.WebSocketError.alreadyConnected {
                    failure = "This WebSocket was already started."
                }
            }
        }

        OnDisappear {
            socket.dispose()
        }

        Column(spacing: 12) {
            Text(connection)
            Text(received)
            Text(failure)
            Button("Send") {
                if (socket.send(message)) {
                    connection = "Message queued"
                } else {
                    connection = "Not connected"
                }
            }
        }
    }
}
```

## API behavior

- `connect()` validates the URL and starts the native handshake. It returns after
  starting the connection; observe `stateChanged`, `failed`, and the current
  `state` for the handshake result.
- `send(text)` and `sendBytes(bytes)` return `false` unless the connection is
  open or the platform rejects the local enqueue. `true` means accepted by the
  local networking client; it does not mean the peer received the message.
- `messageReceived` and `binaryReceived` deliver incoming WebSocket messages.
- `close()` starts a normal RFC 6455 close handshake. `dispose()` cancels work
  and releases the instance's callbacks and native connection resources.
- Register event handlers before calling `connect()` to observe every state
  transition. Native callbacks are delivered on the main thread on both
  platforms.

## Platform implementation

- iOS uses `URLSessionWebSocketTask` and callback-based APIs, so the plugin keeps
  its iOS deployment minimum at 13.0.
- Android uses the isolated OkHttp WebSocket client dependency, version 5.5.0,
  with Android API 21 or later. The app's effective minimum API remains the
  higher of the app and plugin requirements. The dependency is Apache-2.0.
- One shared OkHttp client reuses its dispatcher and connection pool. Each
  `WebSocket` owns its connection, callbacks, and close lifecycle.
