import AVFoundation
import AVKit
import SwiftUI

@MainActor
protocol VideoPlayerEngine: AnyObject {
    var player: AVPlayer? { get }
    var volume: Double { get set }
    var onEnded: (() -> Void)? { get set }

    func prepare(url: URL) async throws(PlayerError) -> Double
    func play()
    func pause()
    func seek(position: Double)
    func dispose()
}

@MainActor
private final class AVPlayerEngine: VideoPlayerEngine {
    private(set) var player: AVPlayer?
    var volume: Double = 1.0 {
        didSet { player?.volume = Float(volume) }
    }
    var onEnded: (() -> Void)?
    private var endedObserver: NSObjectProtocol?

    func prepare(url: URL) async throws(PlayerError) -> Double {
        let item = AVPlayerItem(url: url)
        let mediaDuration: CMTime
        do {
            mediaDuration = try await item.asset.load(.duration)
        } catch {
            throw PlayerError.decodingFailed(message: error.localizedDescription)
        }

        removeEndedObserver()
        player?.pause()
        let player = AVPlayer(playerItem: item)
        player.volume = Float(volume)
        self.player = player
        endedObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.onEnded?()
            }
        }
        return mediaDuration.seconds
    }

    func play() {
        player?.play()
    }

    func pause() {
        player?.pause()
    }

    func seek(position: Double) {
        player?.seek(to: CMTime(seconds: position, preferredTimescale: 600))
    }

    func dispose() {
        player?.pause()
        player = nil
        removeEndedObserver()
        onEnded = nil
    }

    private func removeEndedObserver() {
        if let endedObserver {
            NotificationCenter.default.removeObserver(endedObserver)
            self.endedObserver = nil
        }
    }
}

@MainActor
public final class VideoPlayerImpl: VideoPlayerSpec {
    public private(set) var state: PlayerState = .idle
    public private(set) var duration: Double = 0
    public var volume: Double {
        didSet { engine.volume = volume }
    }
    public var onEnded: (() -> Void)?

    fileprivate var player: AVPlayer? { engine.player }
    private let engine: any VideoPlayerEngine
    private var prepareGeneration: UInt64 = 0
    private var isDisposed = false

    public convenience init() {
        self.init(engine: AVPlayerEngine())
    }

    init(engine: any VideoPlayerEngine) {
        self.engine = engine
        self.volume = engine.volume
        engine.onEnded = { [weak self] in
            guard let self else { return }
            self.state = .ended
            self.onEnded?()
        }
    }

    public func prepare(_ url: String) async throws(PlayerError) {
        prepareGeneration &+= 1
        let generation = prepareGeneration
        guard !isDisposed else {
            state = .failed
            throw PlayerError.decodingFailed(message: "VideoPlayer has been disposed")
        }
        guard let url = URL(string: url),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            state = .failed
            throw PlayerError.invalidUrl
        }
        state = .preparing
        do {
            let preparedDuration = try await engine.prepare(url: url)
            guard generation == prepareGeneration else { return }
            duration = preparedDuration
            state = .ready
        } catch {
            if generation == prepareGeneration {
                state = .failed
            }
            throw error
        }
    }

    public func play() {
        guard !isDisposed else { return }
        engine.play()
        state = .playing
    }

    public func pause() {
        guard !isDisposed else { return }
        engine.pause()
        state = .paused
    }

    public func seek(_ position: Double) {
        guard !isDisposed else { return }
        engine.seek(position: position)
    }

    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        prepareGeneration &+= 1
        onEnded = nil
        engine.onEnded = nil
        engine.dispose()
        state = .idle
    }
}

/// Native visual implementation used by the generated `VideoView` wrapper.
/// A production plugin can replace this body with AVPlayerViewController
/// interoperability while keeping the generated Nexa-facing contract stable.
public struct VideoViewImpl<Content: View>: View {
    public let player: VideoPlayer
    public let controls: Bool
    public let onTapped: (() -> Void)?
    public let content: Content

    public var body: some View {
        Group {
#if os(iOS)
            if let player = player.player {
                VideoPlayerController(player: player, controls: controls)
            } else {
                Color.black
            }
#else
            Color.black
#endif
        }
        .overlay(alignment: .topLeading) { content }
        .contentShape(Rectangle())
        .onTapGesture { onTapped?() }
    }
}

#if os(iOS)
private struct VideoPlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    let controls: Bool

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.showsPlaybackControls = controls
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = player
        controller.showsPlaybackControls = controls
    }
}
#endif
