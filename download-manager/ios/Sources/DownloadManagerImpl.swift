import Foundation

@MainActor
public final class DownloadManagerImpl: NSObject, DownloadManagerSpec, URLSessionDownloadDelegate {
    private struct Transfer {
        let id: String
        let destination: URL
        var continuation: CheckedContinuation<DownloadSnapshot, DownloadManagerError>?
        var lastProgressAt: TimeInterval
        var lastPersistedAt: TimeInterval
    }

    private static let sessionIdentifier = "dev.nexa.download-manager.session"
    private let defaults = UserDefaults.standard
    private var transfers: [Int: Transfer] = [:]
    private var tasksByID: [String: URLSessionDownloadTask] = [:]
    private var hlsTasks: [String: Task<DownloadSnapshot, Error>] = [:]
    private var disposed = false
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        configuration.allowsCellularAccess = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    public var onProgress: ((String, Int64, Int64) -> Void)?
    public var onCompleted: ((DownloadSnapshot) -> Void)?
    public var onFailed: ((String, String) -> Void)?

    public required override init() {
        super.init()
        _ = session
    }

    public func start(_ id: String, _ url: String, _ fileName: String, _ headers: [String: String]) async throws(DownloadManagerError) -> DownloadSnapshot {
        guard !disposed else { throw .downloadFailed(message: "Download manager is disposed") }
        guard isValidIdentifier(id) else { throw .invalidIdentifier }
        guard let parsedURL = URL(string: url),
              let scheme = parsedURL.scheme?.lowercased(),
              ["http", "https"].contains(scheme)
        else { throw .invalidURL }

        let safeName = fileName.components(separatedBy: CharacterSet(charactersIn: "/\\")).last ?? ""
        guard !safeName.isEmpty, safeName != ".", safeName != ".." else { throw .invalidIdentifier }
        defaults.removeObject(forKey: Self.key(id, "removed"))
        let directory = Self.downloadDirectory()
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableDirectory = directory
            try mutableDirectory.setResourceValues(values)
        } catch {
            throw .destinationUnavailable
        }
        let destination = directory.appendingPathComponent(safeName, isDirectory: false)

        if FileManager.default.fileExists(atPath: destination.path) {
            let snapshot = DownloadSnapshot(
                id: id,
                state: .completed,
                receivedBytes: fileSize(destination),
                expectedBytes: fileSize(destination),
                localPath: destination.path,
                error: ""
            )
            persist(snapshot, fileName: safeName)
            return snapshot
        }

        await restoreTasks()
        if let existingTask = tasksByID[id] {
            guard transfers[existingTask.taskIdentifier]?.continuation == nil else {
                throw .downloadFailed(message: "A download with this identifier is already being observed")
            }
            return try await waitForCompletion(id: id, task: existingTask, destination: destination)
        }

        defaults.set(safeName, forKey: Self.key(id, "filename"))
        var request = URLRequest(url: parsedURL)
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let task = session.downloadTask(with: request)
        task.taskDescription = id
        tasksByID[id] = task
        return try await withCheckedThrowingContinuation { continuation in
            transfers[task.taskIdentifier] = Transfer(
                id: id,
                destination: destination,
                continuation: continuation,
                lastProgressAt: 0,
                lastPersistedAt: 0
            )
            persist(DownloadSnapshot(
                id: id,
                state: .downloading,
                receivedBytes: 0,
                expectedBytes: 0,
                localPath: destination.path,
                error: ""
            ), fileName: safeName)
            task.resume()
        }
    }

    public func startHls(
        _ id: String,
        _ url: String,
        _ packageName: String,
        _ headers: [String: String],
        _ expectedBytes: Int64?
    ) async throws(DownloadManagerError) -> DownloadSnapshot {
        guard !disposed else { throw .downloadFailed(message: "Download manager is disposed") }
        guard isValidIdentifier(id) else { throw .invalidIdentifier }
        guard let parsedURL = URL(string: url),
              let scheme = parsedURL.scheme?.lowercased(),
              ["http", "https"].contains(scheme)
        else { throw .invalidURL }

        let safeName = packageName.components(separatedBy: CharacterSet(charactersIn: "/\\")).last ?? ""
        guard !safeName.isEmpty, safeName != ".", safeName != ".." else { throw .invalidIdentifier }
        defaults.set(false, forKey: Self.key(id, "removed"))
        let directory = Self.downloadDirectory()
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            throw .destinationUnavailable
        }

        if let saved = readSnapshot(id), saved.state == .completed,
           FileManager.default.fileExists(atPath: saved.localPath) {
            return saved
        }
        if let task = tasksByID.removeValue(forKey: id) {
            task.cancel()
            transfers.removeValue(forKey: task.taskIdentifier)?.continuation?.resume(
                throwing: DownloadManagerError.downloadFailed(message: "Download replaced by HLS package")
            )
        }
        guard hlsTasks[id] == nil else {
            throw .downloadFailed(message: "An HLS download with this identifier is already active")
        }

        let finalDirectory = directory.appendingPathComponent("\(safeName).hls", isDirectory: true)
        let stagingDirectory = directory.appendingPathComponent(".\(safeName).hls.tmp", isDirectory: true)
        defaults.set("\(safeName).hls", forKey: Self.key(id, "filename"))
        let expectedHint = expectedBytes.flatMap { $0 > 0 ? $0 : nil }
        let expected = expectedHint ?? 0
        persist(DownloadSnapshot(
            id: id,
            state: .downloading,
            receivedBytes: 0,
            expectedBytes: expected,
            localPath: "",
            error: ""
        ), fileName: "\(safeName).hls")

        let task: Task<DownloadSnapshot, Error> = Task { @MainActor [weak self] in
            guard let self else { throw DownloadManagerError.downloadFailed(message: "Download manager was released") }
            do {
                try? FileManager.default.removeItem(at: stagingDirectory)
                try FileManager.default.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
                let result = try await HlsPackageDownloader().download(
                    sourceURL: url,
                    destination: stagingDirectory,
                    headers: headers
                ) { received in
                    let total = expectedHint.map { max($0, received) } ?? 0
                    let progress = DownloadSnapshot(
                        id: id,
                        state: .downloading,
                        receivedBytes: received,
                        expectedBytes: total,
                        localPath: "",
                        error: ""
                    )
                    self.onProgress?(id, received, total)
                    self.persist(progress, fileName: "\(safeName).hls")
                }
                try Task.checkCancellation()
                if FileManager.default.fileExists(atPath: finalDirectory.path) {
                    try FileManager.default.removeItem(at: finalDirectory)
                }
                try FileManager.default.moveItem(at: stagingDirectory, to: finalDirectory)
                let localPath = finalDirectory.appendingPathComponent(result.playlist.lastPathComponent).path
                let totalBytes = result.receivedBytes
                let snapshot = DownloadSnapshot(
                    id: id,
                    state: .completed,
                    receivedBytes: totalBytes,
                    expectedBytes: max(expectedHint ?? totalBytes, totalBytes),
                    localPath: localPath,
                    error: ""
                )
                self.persist(snapshot, fileName: "\(safeName).hls")
                self.onCompleted?(snapshot)
                return snapshot
            } catch is CancellationError {
                try? FileManager.default.removeItem(at: stagingDirectory)
                let snapshot = DownloadSnapshot(
                    id: id,
                    state: .cancelled,
                    receivedBytes: 0,
                    expectedBytes: expected,
                    localPath: "",
                    error: "Download cancelled"
                )
                self.persist(snapshot, fileName: "\(safeName).hls")
                throw DownloadManagerError.downloadFailed(message: "Download cancelled")
            } catch let error as DownloadManagerError {
                try? FileManager.default.removeItem(at: stagingDirectory)
                throw error
            } catch {
                try? FileManager.default.removeItem(at: stagingDirectory)
                let message = error.localizedDescription
                let snapshot = DownloadSnapshot(
                    id: id,
                    state: .failed,
                    receivedBytes: 0,
                    expectedBytes: expected,
                    localPath: "",
                    error: message
                )
                self.persist(snapshot, fileName: "\(safeName).hls")
                self.onFailed?(id, message)
                throw DownloadManagerError.downloadFailed(message: message)
            }
        }
        hlsTasks[id] = task
        do {
            let snapshot = try await task.value
            hlsTasks.removeValue(forKey: id)
            return snapshot
        } catch let error as DownloadManagerError {
            hlsTasks.removeValue(forKey: id)
            throw error
        } catch {
            hlsTasks.removeValue(forKey: id)
            throw .downloadFailed(message: error.localizedDescription)
        }
    }

    public func status(_ id: String) async throws(DownloadManagerError) -> DownloadSnapshot? {
        guard isValidIdentifier(id) else { throw .invalidIdentifier }
        await restoreTasks()
        if let task = tasksByID[id], let transfer = transfers[task.taskIdentifier] {
            return DownloadSnapshot(
                id: id,
                state: .downloading,
                receivedBytes: task.countOfBytesReceived,
                expectedBytes: task.countOfBytesExpectedToReceive,
                localPath: transfer.destination.path,
                error: ""
            )
        }
        if let snapshot = readSnapshot(id) { return snapshot }
        return nil
    }

    public func completedPath(_ id: String) async throws(DownloadManagerError) -> String? {
        guard let snapshot = try await status(id), snapshot.state == .completed,
              !snapshot.localPath.isEmpty,
              FileManager.default.fileExists(atPath: snapshot.localPath)
        else { return nil }
        return snapshot.localPath
    }

    public func adoptExistingDownloads(_ ids: [String]) async throws(DownloadManagerError) {
    }

    public func cancel(_ id: String) {
        guard isValidIdentifier(id) else { return }
        hlsTasks.removeValue(forKey: id)?.cancel()
        if let packageName = defaults.string(forKey: Self.key(id, "filename")), packageName.hasSuffix(".hls") {
            try? FileManager.default.removeItem(at: Self.downloadDirectory().appendingPathComponent(".\(packageName).tmp", isDirectory: true))
            try? FileManager.default.removeItem(at: Self.downloadDirectory().appendingPathComponent(packageName, isDirectory: true))
        }
        if let task = tasksByID[id] {
            task.cancel()
            return
        }
        Task { @MainActor [weak self] in
            await self?.restoreTasks()
            self?.tasksByID[id]?.cancel()
        }
    }

    public func remove(_ id: String) async throws(DownloadManagerError) -> Bool {
        guard isValidIdentifier(id) else { throw .invalidIdentifier }
        await restoreTasks()
        defaults.set(true, forKey: Self.key(id, "removed"))
        let task = tasksByID.removeValue(forKey: id)
        let transfer = task.flatMap { transfers.removeValue(forKey: $0.taskIdentifier) }
        task?.cancel()
        hlsTasks.removeValue(forKey: id)?.cancel()
        transfer?.continuation?.resume(throwing: .downloadFailed(message: "Download removed"))
        let savedPath = readSnapshot(id)?.localPath
        let savedURL = savedPath.map(URL.init(fileURLWithPath:))
        let parentDirectory = savedURL?.deletingLastPathComponent()
        let hlsPackage = parentDirectory.flatMap { $0.lastPathComponent.hasSuffix(".hls") ? $0 : nil }
        let destination = hlsPackage
            ?? savedURL
            ?? Self.downloadDirectory().appendingPathComponent(
                defaults.string(forKey: Self.key(id, "filename")) ?? "\(id).m4a",
                isDirectory: false
            )
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
        } catch {
            throw .downloadFailed(message: error.localizedDescription)
        }
        clearPersisted(id)
        return true
    }

    public func dispose() {
        guard !disposed else { return }
        disposed = true
        onProgress = nil
        onCompleted = nil
        onFailed = nil
        for transfer in transfers.values {
            transfer.continuation?.resume(throwing: .downloadFailed(message: "Download observer released"))
        }
        transfers.removeAll(keepingCapacity: false)
        tasksByID.removeAll(keepingCapacity: false)
        hlsTasks.values.forEach { $0.cancel() }
        hlsTasks.removeAll(keepingCapacity: false)
        session.finishTasksAndInvalidate()
    }

    nonisolated public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let id = downloadTask.taskDescription else { return }
        Task { @MainActor [weak self] in
            self?.recordProgress(id: id, taskID: downloadTask.taskIdentifier, received: totalBytesWritten, expected: totalBytesExpectedToWrite)
        }
    }

    nonisolated public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let id = downloadTask.taskDescription else { return }
        if UserDefaults.standard.bool(forKey: Self.key(id, "removed")) { return }
        let filename = UserDefaults.standard.string(forKey: Self.key(id, "filename")) ?? "\(id).m4a"
        let destination = Self.downloadDirectory().appendingPathComponent(filename, isDirectory: false)
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.moveItem(at: location, to: destination)
            let size = Self.fileSize(destination)
            let snapshot = DownloadSnapshot(
                id: id,
                state: .completed,
                receivedBytes: size,
                expectedBytes: size,
                localPath: destination.path,
                error: ""
            )
            Self.persist(snapshot, fileName: filename)
            Task { @MainActor [weak self] in
                self?.complete(snapshot, taskID: downloadTask.taskIdentifier)
            }
        } catch {
            let message = error.localizedDescription
            Task { @MainActor [weak self] in
                self?.fail(id: id, taskID: downloadTask.taskIdentifier, message: message, cancelled: false)
            }
        }
    }

    nonisolated public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: (any Error)?
    ) {
        guard let error, let id = task.taskDescription else { return }
        let cancelled = (error as? URLError)?.code == .cancelled
        let message = cancelled ? "Download cancelled" : error.localizedDescription
        Task { @MainActor [weak self] in
            self?.fail(id: id, taskID: task.taskIdentifier, message: message, cancelled: cancelled)
        }
    }

    private func waitForCompletion(id: String, task: URLSessionDownloadTask, destination: URL) async throws(DownloadManagerError) -> DownloadSnapshot {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<DownloadSnapshot, DownloadManagerError>) in
            transfers[task.taskIdentifier] = Transfer(
                id: id,
                destination: destination,
                continuation: continuation,
                lastProgressAt: 0,
                lastPersistedAt: 0
            )
        }
    }

    private func restoreTasks() async {
        let tasks = await withCheckedContinuation { continuation in
            session.getAllTasks { continuation.resume(returning: $0) }
        }
        for case let task as URLSessionDownloadTask in tasks {
            guard let id = task.taskDescription, isValidIdentifier(id) else { continue }
            tasksByID[id] = task
            if transfers[task.taskIdentifier] == nil {
                let fileName = defaults.string(forKey: Self.key(id, "filename")) ?? "\(id).m4a"
                transfers[task.taskIdentifier] = Transfer(
                    id: id,
                    destination: Self.downloadDirectory().appendingPathComponent(fileName, isDirectory: false),
                    continuation: nil,
                    lastProgressAt: 0,
                    lastPersistedAt: 0
                )
            }
        }
    }

    private func recordProgress(id: String, taskID: Int, received: Int64, expected: Int64) {
        guard var transfer = transfers[taskID] else { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard now - transfer.lastProgressAt >= 0.2 || (expected > 0 && received >= expected) else { return }
        transfer.lastProgressAt = now
        transfers[taskID] = transfer
        onProgress?(id, received, expected)
        if now - transfer.lastPersistedAt >= 2.0 {
            transfer.lastPersistedAt = now
            transfers[taskID] = transfer
            persist(DownloadSnapshot(
                id: id,
                state: .downloading,
                receivedBytes: received,
                expectedBytes: expected,
                localPath: transfer.destination.path,
                error: ""
            ), fileName: transfer.destination.lastPathComponent)
        }
    }

    private func complete(_ snapshot: DownloadSnapshot, taskID: Int) {
        guard let transfer = transfers.removeValue(forKey: taskID) else { return }
        tasksByID.removeValue(forKey: transfer.id)
        if isRemoved(transfer.id) {
            try? FileManager.default.removeItem(at: transfer.destination)
            transfer.continuation?.resume(throwing: .downloadFailed(message: "Download removed"))
            return
        }
        onCompleted?(snapshot)
        transfer.continuation?.resume(returning: snapshot)
    }

    private func fail(id: String, taskID: Int, message: String, cancelled: Bool) {
        guard let transfer = transfers.removeValue(forKey: taskID) else { return }
        tasksByID.removeValue(forKey: id)
        if isRemoved(id) {
            try? FileManager.default.removeItem(at: transfer.destination)
            transfer.continuation?.resume(throwing: .downloadFailed(message: "Download removed"))
            return
        }
        let state: DownloadState = cancelled ? .cancelled : .failed
        let snapshot = DownloadSnapshot(
            id: id,
            state: state,
            receivedBytes: 0,
            expectedBytes: 0,
            localPath: transfer.destination.path,
            error: message
        )
        persist(snapshot, fileName: transfer.destination.lastPathComponent)
        if !cancelled { onFailed?(id, message) }
        transfer.continuation?.resume(throwing: DownloadManagerError.downloadFailed(message: message))
    }

    private func readSnapshot(_ id: String) -> DownloadSnapshot? {
        guard let rawState = defaults.string(forKey: Self.key(id, "state")) else { return nil }
        let state: DownloadState
        switch rawState {
        case "queued": state = .queued
        case "downloading": state = .downloading
        case "completed": state = .completed
        case "failed": state = .failed
        case "cancelled": state = .cancelled
        default: return nil
        }
        return DownloadSnapshot(
            id: id,
            state: state,
            receivedBytes: Int64(defaults.integer(forKey: Self.key(id, "received"))),
            expectedBytes: Int64(defaults.integer(forKey: Self.key(id, "expected"))),
            localPath: defaults.string(forKey: Self.key(id, "path")) ?? "",
            error: defaults.string(forKey: Self.key(id, "error")) ?? ""
        )
    }

    private func persist(_ snapshot: DownloadSnapshot, fileName: String) {
        Self.persist(snapshot, fileName: fileName, defaults: defaults)
    }

    nonisolated private static func persist(_ snapshot: DownloadSnapshot, fileName: String) {
        persist(snapshot, fileName: fileName, defaults: .standard)
    }

    nonisolated private static func persist(_ snapshot: DownloadSnapshot, fileName: String, defaults: UserDefaults) {
        guard !defaults.bool(forKey: key(snapshot.id, "removed")) else { return }
        defaults.set(fileName, forKey: key(snapshot.id, "filename"))
        defaults.set(stateName(snapshot.state), forKey: key(snapshot.id, "state"))
        defaults.set(snapshot.receivedBytes, forKey: key(snapshot.id, "received"))
        defaults.set(snapshot.expectedBytes, forKey: key(snapshot.id, "expected"))
        defaults.set(snapshot.localPath, forKey: key(snapshot.id, "path"))
        defaults.set(snapshot.error, forKey: key(snapshot.id, "error"))
    }

    private func clearPersisted(_ id: String) {
        for field in ["filename", "state", "received", "expected", "path", "error"] {
            defaults.removeObject(forKey: Self.key(id, field))
        }
    }

    private func isRemoved(_ id: String) -> Bool {
        defaults.bool(forKey: Self.key(id, "removed"))
    }

    private func isValidIdentifier(_ id: String) -> Bool {
        !id.isEmpty && id.rangeOfCharacter(from: CharacterSet(charactersIn: "/\\")) == nil
    }

    nonisolated private static func key(_ id: String, _ field: String) -> String {
        "dev.nexa.download-manager.\(id).\(field)"
    }

    nonisolated private static func stateName(_ state: DownloadState) -> String {
        switch state {
        case .queued: "queued"
        case .downloading: "downloading"
        case .completed: "completed"
        case .failed: "failed"
        case .cancelled: "cancelled"
        }
    }

    nonisolated private static func downloadDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("MazicaDownloads", isDirectory: true)
    }

    nonisolated private static func fileSize(_ url: URL) -> Int64 {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func fileSize(_ url: URL) -> Int64 {
        Self.fileSize(url)
    }
}
