import Foundation

private struct WebSocketTaskCallbacks: @unchecked Sendable {
    let onOpen: @Sendable (Int) -> Void
    let onClose: @Sendable (Int, Int) -> Void
    let onFailure: @Sendable (Int, String) -> Void
}

/// Owns one URLSession for the plugin and routes delegate callbacks by task.
/// Keeping callbacks per task preserves socket ownership while avoiding a
/// separate URLSession and delegate allocation for every WebSocket instance.
private final class WebSocketSessionDelegate: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    static let shared = WebSocketSessionDelegate()

    private let lock = NSLock()
    private var callbacksByTask: [Int: WebSocketTaskCallbacks] = [:]
    private lazy var session = URLSession(
        configuration: .default,
        delegate: self,
        delegateQueue: nil
    )

    private override init() {
        super.init()
    }

    func makeTask(url: URL, callbacks: WebSocketTaskCallbacks) -> URLSessionWebSocketTask {
        let task = session.webSocketTask(with: url)
        lock.lock()
        callbacksByTask[task.taskIdentifier] = callbacks
        lock.unlock()
        return task
    }

    func removeCallbacks(for taskIdentifier: Int) {
        lock.lock()
        callbacksByTask.removeValue(forKey: taskIdentifier)
        lock.unlock()
    }

    private func callbacks(for taskIdentifier: Int) -> WebSocketTaskCallbacks? {
        lock.lock()
        defer { lock.unlock() }
        return callbacksByTask[taskIdentifier]
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol subprotocol: String?
    ) {
        callbacks(for: webSocketTask.taskIdentifier)?.onOpen(webSocketTask.taskIdentifier)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        callbacks(for: webSocketTask.taskIdentifier)?.onClose(
            webSocketTask.taskIdentifier,
            closeCode.rawValue
        )
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        callbacks(for: task.taskIdentifier)?.onFailure(task.taskIdentifier, error.localizedDescription)
    }
}

@MainActor
public final class WebSocketImpl: WebSocketSpec {
    public private(set) var state: WebSocketState = .idle
    public var onStateChanged: ((WebSocketState) -> Void)?
    public var onReconnecting: ((Int32, Int32) -> Void)?
    public var onMessageReceived: ((String) -> Void)?
    public var onBinaryReceived: ((Data) -> Void)?
    public var onFailed: ((String) -> Void)?

    private let urlString: String
    private var maximumReconnectAttempts = 0
    private var socketURL: URL?
    private var socket: URLSessionWebSocketTask?
    private var retryTask: Task<Void, Never>?
    private var reconnectAttempts = 0
    private var closeRequested = false
    private var isDisposed = false

    public init(_ url: String) {
        urlString = url
    }

    public func configureReconnect(_ maxAttempts: Int32) {
        guard !isDisposed, state == .idle else { return }
        maximumReconnectAttempts = max(0, Int(maxAttempts))
    }

    public func connect() async throws(WebSocketError) {
        guard !isDisposed, state == .idle else {
            throw .alreadyConnected
        }
        guard let components = URLComponents(string: urlString),
              let scheme = components.scheme?.lowercased(),
              scheme == "ws" || scheme == "wss",
              components.host?.isEmpty == false,
              let url = components.url
        else {
            setState(.failed)
            throw .invalidUrl
        }

        socketURL = url
        startConnection(to: url)
    }

    public func send(_ text: String) -> Bool {
        guard !isDisposed, state == .open, let socket else { return false }
        socket.send(.string(text)) { [weak self, weak socket] error in
            guard let error, let socket else { return }
            let message = error.localizedDescription
            Task { @MainActor [weak self, weak socket] in
                guard let self, let socket else { return }
                self.fail(taskIdentifier: socket.taskIdentifier, message: message)
            }
        }
        return true
    }

    public func sendBytes(_ bytes: Data) -> Bool {
        guard !isDisposed, state == .open, let socket else { return false }
        socket.send(.data(bytes)) { [weak self, weak socket] error in
            guard let error, let socket else { return }
            let message = error.localizedDescription
            Task { @MainActor [weak self, weak socket] in
                guard let self, let socket else { return }
                self.fail(taskIdentifier: socket.taskIdentifier, message: message)
            }
        }
        return true
    }

    public func close() {
        guard !isDisposed, state != .closed, state != .failed else { return }
        closeRequested = true
        retryTask?.cancel()
        retryTask = nil
        if state == .idle || state == .reconnecting {
            setState(.closed)
            return
        }
        setState(.closing)
        socket?.cancel(with: .normalClosure, reason: nil)
    }

    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        closeRequested = true
        retryTask?.cancel()
        retryTask = nil
        if let socket {
            WebSocketSessionDelegate.shared.removeCallbacks(for: socket.taskIdentifier)
        }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        state = .closed
        onStateChanged = nil
        onReconnecting = nil
        onMessageReceived = nil
        onBinaryReceived = nil
        onFailed = nil
    }

    private func startConnection(to url: URL) {
        closeRequested = false
        let socket = WebSocketSessionDelegate.shared.makeTask(
            url: url,
            callbacks: WebSocketTaskCallbacks(
                onOpen: { [weak self] taskIdentifier in
                    Task { @MainActor [weak self] in
                        self?.didOpen(taskIdentifier: taskIdentifier)
                    }
                },
                onClose: { [weak self] taskIdentifier, closeCode in
                    Task { @MainActor [weak self] in
                        self?.didClose(taskIdentifier: taskIdentifier, closeCode: closeCode)
                    }
                },
                onFailure: { [weak self] taskIdentifier, message in
                    Task { @MainActor [weak self] in
                        self?.fail(taskIdentifier: taskIdentifier, message: message)
                    }
                }
            )
        )
        self.socket = socket
        setState(.connecting)
        socket.resume()
        receiveNextMessage(from: socket)
    }

    private func didOpen(taskIdentifier: Int) {
        guard !isDisposed, socket?.taskIdentifier == taskIdentifier else { return }
        reconnectAttempts = 0
        retryTask = nil
        setState(.open)
    }

    private func didClose(taskIdentifier: Int, closeCode: Int) {
        guard !isDisposed, socket?.taskIdentifier == taskIdentifier else { return }
        WebSocketSessionDelegate.shared.removeCallbacks(for: taskIdentifier)
        socket = nil
        if closeRequested || closeCode == 1000 {
            setState(.closed)
        } else {
            failOrReconnect(message: "WebSocket closed with code \(closeCode)")
        }
    }

    private func receiveNextMessage(from socket: URLSessionWebSocketTask) {
        guard !isDisposed, state != .closing, state != .closed, state != .failed else { return }
        socket.receive { [weak self, weak socket] result in
            guard let socket else { return }
            Task { @MainActor [weak self, weak socket] in
                guard let self, let socket,
                      !self.isDisposed,
                      self.socket === socket
                else {
                    return
                }

                switch result {
                case .success(.string(let text)):
                    self.onMessageReceived?(text)
                    self.receiveNextMessage(from: socket)
                case .success(.data(let bytes)):
                    self.onBinaryReceived?(bytes)
                    self.receiveNextMessage(from: socket)
                case .failure(let error):
                    self.fail(taskIdentifier: socket.taskIdentifier, message: error.localizedDescription)
                @unknown default:
                    self.receiveNextMessage(from: socket)
                }
            }
        }
    }

    private func fail(taskIdentifier: Int, message: String) {
        guard !isDisposed, socket?.taskIdentifier == taskIdentifier else { return }
        WebSocketSessionDelegate.shared.removeCallbacks(for: taskIdentifier)
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        if closeRequested {
            setState(.closed)
        } else {
            failOrReconnect(message: message)
        }
    }

    private func failOrReconnect(message: String) {
        guard !isDisposed else { return }
        guard reconnectAttempts < maximumReconnectAttempts else {
            setState(.failed)
            onFailed?(message)
            return
        }

        reconnectAttempts += 1
        let attempt = reconnectAttempts
        let delayMillis = reconnectDelayMillis(for: attempt)
        setState(.reconnecting)
        onReconnecting?(Int32(attempt), Int32(delayMillis))
        guard let socketURL else {
            setState(.failed)
            onFailed?(message)
            return
        }

        retryTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(delayMillis) * 1_000_000)
            } catch {
                return
            }
            guard let self,
                  !self.isDisposed,
                  self.state == .reconnecting,
                  self.reconnectAttempts == attempt
            else {
                return
            }
            self.startConnection(to: socketURL)
        }
    }

    private func reconnectDelayMillis(for attempt: Int) -> Int {
        let exponent = min(max(attempt - 1, 0), 5)
        return min(250 * (1 << exponent), 8_000)
    }

    private func setState(_ value: WebSocketState) {
        guard canTransition(from: state, to: value) else { return }
        state = value
        onStateChanged?(value)
    }

    private func canTransition(from: WebSocketState, to: WebSocketState) -> Bool {
        switch from {
        case .idle:
            return to == .connecting || to == .closed || to == .failed
        case .connecting:
            return to == .open || to == .reconnecting || to == .closing ||
                to == .closed || to == .failed
        case .open:
            return to == .reconnecting || to == .closing || to == .closed || to == .failed
        case .reconnecting:
            return to == .connecting || to == .closing || to == .closed || to == .failed
        case .closing:
            return to == .reconnecting || to == .closed || to == .failed
        case .closed, .failed:
            return false
        }
    }
}
