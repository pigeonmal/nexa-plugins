import AVFoundation

public enum PlayerState {
    case idle
    case preparing
    case ready
    case playing
    case paused
    case ended
    case failed
}

public enum PlayerError: Error {
    case invalidUrl
    case decodingFailed(message: String)
}

@MainActor
public protocol VideoPlayerSpec {
    var state: PlayerState { get }
    var duration: Double { get }
    var volume: Double { get set }
    var onEnded: (() -> Void)? { get set }

    func prepare(_ url: String) async throws(PlayerError)
    func play()
    func pause()
    func seek(_ position: Double)
    func dispose()
}

public struct VideoPlayer {}

@MainActor
private final class FakeVideoPlayerEngine: VideoPlayerEngine {
    private(set) var player: AVPlayer?
    var volume: Double = 1.0
    var onEnded: (() -> Void)?
    private let duration: Double
    private(set) var preparedURLs: [URL] = []
    private(set) var playCount = 0
    private(set) var pauseCount = 0
    private(set) var disposeCount = 0

    init(duration: Double) {
        self.duration = duration
    }

    func prepare(url: URL) async throws(PlayerError) -> Double {
        preparedURLs.append(url)
        return duration
    }

    func play() {
        playCount += 1
    }

    func pause() {
        pauseCount += 1
    }

    func seek(position: Double) {}

    func dispose() {
        disposeCount += 1
        onEnded = nil
    }

    func finish() {
        onEnded?()
    }
}

@main
struct VideoPlayerRuntimeSmoke {
    @MainActor
    static func main() async throws {
        let firstEngine = FakeVideoPlayerEngine(duration: 12)
        let secondEngine = FakeVideoPlayerEngine(duration: 38.5)
        let first = VideoPlayerImpl(engine: firstEngine)
        let second = VideoPlayerImpl(engine: secondEngine)
        var firstEnded = 0
        var secondEnded = 0
        first.onEnded = { firstEnded += 1 }
        second.onEnded = { secondEnded += 1 }
        first.volume = 0.25
        second.volume = 0.75

        async let firstPrepare: Void = first.prepare("https://media.example/first.mp4")
        async let secondPrepare: Void = second.prepare("https://media.example/second.mp4")
        try await firstPrepare
        try await secondPrepare

        precondition(firstEngine.preparedURLs.map(\.absoluteString) == ["https://media.example/first.mp4"])
        precondition(secondEngine.preparedURLs.map(\.absoluteString) == ["https://media.example/second.mp4"])
        precondition(first.state == .ready && second.state == .ready)
        precondition(first.duration == 12 && second.duration == 38.5)
        precondition(firstEngine.volume == 0.25 && secondEngine.volume == 0.75)

        first.play()
        precondition(first.state == .playing && second.state == .ready)
        firstEngine.finish()
        precondition(first.state == .ended && firstEnded == 1 && secondEnded == 0)

        first.dispose()
        first.dispose()
        precondition(first.state == .idle && firstEngine.disposeCount == 1)
        precondition(second.state == .ready)
        second.play()
        second.pause()
        precondition(second.state == .paused)
        precondition(secondEngine.playCount == 1 && secondEngine.pauseCount == 1)
        precondition(secondEngine.disposeCount == 0)

        second.dispose()
        precondition(second.state == .idle && secondEngine.disposeCount == 1)
    }
}
