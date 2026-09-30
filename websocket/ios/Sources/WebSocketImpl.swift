import Foundation

private final class WebSocketSessionDelegate: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let onOpen: @Sendable () -> Void
    private let onClose: @Sendable () -> Void
    private let onFailure: @Sendable (String) -> Void

    init(
        onOpen: @escaping @Sendable () -> Void,
        onClose: @escaping @Sendable () -> Void,
        onFailure: @escaping @Sendable (String) -> Void
    ) {
        self.onOpen = onOpen
        self.onClose = onClose
        self.onFailure = onFailure
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol subprotocol: String?
    ) {
        onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        onClose()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        onFailure(error.localizedDescription)
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
    private var session: URLSession?
    private var sessionDelegate: WebSocketSessionDelegate?
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

        let delegate = WebSocketSessionDelegate(
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
        let session = URLSession(
            configuration: .default,
            delegate: delegate,
            delegateQueue: nil
        )
        let socket = session.webSocketTask(with: url)
        self.sessionDelegate = delegate
        self.session = session
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
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        sessionDelegate = nil
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
        session?.finishTasksAndInvalidate()
        session = nil
        sessionDelegate = nil
        socket = nil
    }

    private func fail(message: String) {
        guard !isDisposed, state != .failed, state != .closed else { return }
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        sessionDelegate = nil
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
