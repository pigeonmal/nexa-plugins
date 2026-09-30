import Foundation

private struct WebSocketTaskCallbacks: @unchecked Sendable {
    let onOpen: @Sendable () -> Void
    let onClose: @Sendable () -> Void
    let onFailure: @Sendable (String) -> Void
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
        callbacks(for: webSocketTask.taskIdentifier)?.onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        callbacks(for: webSocketTask.taskIdentifier)?.onClose()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        callbacks(for: task.taskIdentifier)?.onFailure(error.localizedDescription)
    }
}

@MainActor
public final class WebSocketImpl: WebSocketSpec {
    public private(set) var state: WebSocketState = .idle
    public var onStateChanged: ((WebSocketState) -> Void)?
    public var onMessageReceived: ((String) -> Void)?
    public var onBinaryReceived: ((Data) -> Void)?
    public var onFailed: ((String) -> Void)?

    private let urlString: String
    private var socket: URLSessionWebSocketTask?
    private var isDisposed = false

    public init(_ url: String) {
        urlString = url
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

        let socket = WebSocketSessionDelegate.shared.makeTask(
            url: url,
            callbacks: WebSocketTaskCallbacks(
                onOpen: { [weak self] in
                    Task { @MainActor [weak self] in
                        self?.setState(.open)
                    }
                },
                onClose: { [weak self] in
                    Task { @MainActor [weak self] in
                        self?.didClose()
                    }
                },
                onFailure: { [weak self] message in
                    Task { @MainActor [weak self] in
                        self?.fail(message: message)
                    }
                }
            )
        )
        self.socket = socket
        setState(.connecting)
        socket.resume()
        receiveNextMessage(from: socket)
    }

    public func send(_ text: String) -> Bool {
        guard !isDisposed, state == .open, let socket else { return false }
        socket.send(.string(text)) { [weak self] error in
            guard let error else { return }
            let message = error.localizedDescription
            Task { @MainActor [weak self] in
                self?.fail(message: message)
            }
        }
        return true
    }

    public func sendBytes(_ bytes: Data) -> Bool {
        guard !isDisposed, state == .open, let socket else { return false }
        socket.send(.data(bytes)) { [weak self] error in
            guard let error else { return }
            let message = error.localizedDescription
            Task { @MainActor [weak self] in
                self?.fail(message: message)
            }
        }
        return true
    }

    public func close() {
        guard !isDisposed, state != .closed, state != .failed else { return }
        if state == .idle {
            setState(.closed)
            return
        }
        setState(.closing)
        socket?.cancel(with: .normalClosure, reason: nil)
    }

    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        if let socket {
            WebSocketSessionDelegate.shared.removeCallbacks(for: socket.taskIdentifier)
        }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        state = .closed
        onStateChanged = nil
        onMessageReceived = nil
        onBinaryReceived = nil
        onFailed = nil
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
                    guard self.state != .closing, self.state != .closed else { return }
                    self.fail(message: error.localizedDescription)
                @unknown default:
                    self.receiveNextMessage(from: socket)
                }
            }
        }
    }

    private func didClose() {
        guard !isDisposed, state != .closed else { return }
        setState(.closed)
        if let socket {
            WebSocketSessionDelegate.shared.removeCallbacks(for: socket.taskIdentifier)
        }
        socket = nil
    }

    private func fail(message: String) {
        guard !isDisposed, state != .failed, state != .closed else { return }
        if let socket {
            WebSocketSessionDelegate.shared.removeCallbacks(for: socket.taskIdentifier)
        }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        setState(.failed)
        onFailed?(message)
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
            return to == .open || to == .closing || to == .closed || to == .failed
        case .open:
            return to == .closing || to == .closed || to == .failed
        case .closing:
            return to == .closed || to == .failed
        case .closed, .failed:
            return false
        }
    }
}
