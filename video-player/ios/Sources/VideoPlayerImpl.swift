import AVFoundation
import AVKit
import SwiftUI
#if os(iOS)
import KSPlayer
#endif

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
    private(set) var player: AVPlayer? = AVPlayer()
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
        let player = self.player ?? AVPlayer()
        player.replaceCurrentItem(with: item)
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
        player?.replaceCurrentItem(with: nil)
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
    fileprivate var softwareDecodingEnabled = true
    public var onEnded: (() -> Void)?

    fileprivate var player: AVPlayer? { engine.player }
#if os(iOS)
    fileprivate var ksPlayerEngine: KSPlayerEngine? { engine as? KSPlayerEngine }
#endif
    private var engine: any VideoPlayerEngine
    private let followsSoftwareDecodingConfiguration: Bool
    private var selectedSoftwareDecodingEnabled: Bool?
    private var prepareGeneration: UInt64 = 0
    private var isDisposed = false

    public convenience init() {
        self.init(engine: AVPlayerEngine(), followsSoftwareDecodingConfiguration: true)
    }

    convenience init(engine: any VideoPlayerEngine) {
        self.init(engine: engine, followsSoftwareDecodingConfiguration: false)
    }

    private init(
        engine: any VideoPlayerEngine,
        followsSoftwareDecodingConfiguration: Bool
    ) {
        self.engine = engine
        self.volume = engine.volume
        self.followsSoftwareDecodingConfiguration = followsSoftwareDecodingConfiguration
        self.selectedSoftwareDecodingEnabled = followsSoftwareDecodingConfiguration ? nil : false
        bindEngineEvents(engine)
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
        configureEngineIfNeeded()
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

    fileprivate func configureEngineIfNeeded() {
        guard followsSoftwareDecodingConfiguration,
              selectedSoftwareDecodingEnabled != softwareDecodingEnabled else { return }

        engine.onEnded = nil
        engine.dispose()
#if os(iOS)
        let configuredEngine: any VideoPlayerEngine = softwareDecodingEnabled
            ? KSPlayerEngine()
            : AVPlayerEngine()
#else
        let configuredEngine: any VideoPlayerEngine = AVPlayerEngine()
#endif
        engine = configuredEngine
        selectedSoftwareDecodingEnabled = softwareDecodingEnabled
        configuredEngine.volume = volume
        bindEngineEvents(configuredEngine)
    }

    private func bindEngineEvents(_ engine: any VideoPlayerEngine) {
        engine.onEnded = { [weak self] in
            guard let self else { return }
            self.state = .ended
            self.onEnded?()
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
    public let softwareDecodingEnabled: Bool
    public let onTapped: (() -> Void)?
    public let content: Content

    init(
        player: VideoPlayer,
        controls: Bool,
        softwareDecodingEnabled: Bool,
        onTapped: (() -> Void)?,
        content: Content
    ) {
        self.player = player
        self.controls = controls
        self.softwareDecodingEnabled = softwareDecodingEnabled
        self.onTapped = onTapped
        self.content = content
        player.softwareDecodingEnabled = softwareDecodingEnabled
        player.configureEngineIfNeeded()
    }

    public var body: some View {
        Group {
#if os(iOS)
            if let engine = player.ksPlayerEngine {
                KSVideoPlayerController(engine: engine, controls: controls)
            } else if let player = player.player {
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
@MainActor
fileprivate final class KSPlayerEngine: VideoPlayerEngine, KSPlayerLayerDelegate {
    private typealias PrepareResult = Result<Double, PlayerError>

    private(set) var layer: KSPlayerLayer?
    private let delegateProxy: KSPlayerDelegateProxy
    var volume: Double = 1.0 {
        didSet { layer?.player.playbackVolume = Float(volume) }
    }
    var onEnded: (() -> Void)?

    private var wantsPlayback = false
    private var pendingPrepare: CheckedContinuation<PrepareResult, Never>?

    var player: AVPlayer? { nil }

    init() {
        let delegateProxy = KSPlayerDelegateProxy()
        self.delegateProxy = delegateProxy
        delegateProxy.engine = self
    }

    func prepare(url: URL) async throws(PlayerError) -> Double {
        finishPrepare(.failure(.decodingFailed(message: "Video preparation was replaced")))
        stopLayer()

        let options = KSOptions()
        options.registerRemoteControll = false
        options.userAgent = "Nexa"
        wantsPlayback = false

        let result = await withCheckedContinuation { (continuation: CheckedContinuation<PrepareResult, Never>) in
            pendingPrepare = continuation
            // KSPlayer uses AVPlayer first and retries with its FFmpeg-backed
            // renderer only when native playback fails. Its autoplay flag is
            // needed for that retry path; pause synchronously once ready if
            // Nexa's caller has not requested playback.
            let playerLayer = KSPlayerLayer(url: url, isAutoPlay: true, options: options, delegate: delegateProxy)
            playerLayer.player.playbackVolume = Float(volume)
            layer = playerLayer
            if let controlView = delegateProxy.controlView {
                controlView.playerLayer = playerLayer
                playerLayer.delegate = delegateProxy
            }
        }

        switch result {
        case .success(let duration):
            return duration
        case .failure(let error):
            throw error
        }
    }

    func play() {
        wantsPlayback = true
        layer?.play()
    }

    func pause() {
        wantsPlayback = false
        layer?.pause()
    }

    func seek(position: Double) {
        layer?.seek(time: position, autoPlay: wantsPlayback) { _ in }
    }

    func dispose() {
        finishPrepare(.failure(.decodingFailed(message: "VideoPlayer has been disposed")))
        stopLayer()
        onEnded = nil
    }

    func player(layer: KSPlayerLayer, state: KSPlayerState) {
        if state == .readyToPlay {
            let rawDuration = layer.player.duration
            finishPrepare(.success(rawDuration.isFinite ? max(0, rawDuration) : 0))
            if !wantsPlayback {
                // The ready callback runs before KSPlayer's autoplay check.
                // Pausing here prevents a prepare-only request from playing.
                layer.pause()
            }
        }
    }

    func player(layer: KSPlayerLayer, currentTime: TimeInterval, totalTime: TimeInterval) {}

    func player(layer: KSPlayerLayer, finish error: Error?) {
        if let error {
            finishPrepare(.failure(.decodingFailed(message: error.localizedDescription)))
        } else {
            onEnded?()
        }
    }

    func player(layer: KSPlayerLayer, bufferedCount: Int, consumeTime: TimeInterval) {}

    func attachControlView(_ view: IOSVideoPlayerView) {
        delegateProxy.controlView = view
        if let layer {
            if view.playerLayer !== layer {
                view.playerLayer = layer
            }
            layer.delegate = delegateProxy
        }
    }

    private func finishPrepare(_ result: PrepareResult) {
        guard let pendingPrepare else { return }
        self.pendingPrepare = nil
        pendingPrepare.resume(returning: result)
    }

    private func stopLayer() {
        guard let layer else { return }
        layer.delegate = nil
        layer.stop()
        self.layer = nil
    }
}

@MainActor
private final class KSPlayerDelegateProxy: KSPlayerLayerDelegate {
    weak var engine: KSPlayerEngine?
    weak var controlView: IOSVideoPlayerView?

    func player(layer: KSPlayerLayer, state: KSPlayerState) {
        controlView?.player(layer: layer, state: state)
        engine?.player(layer: layer, state: state)
    }

    func player(layer: KSPlayerLayer, currentTime: TimeInterval, totalTime: TimeInterval) {
        controlView?.player(layer: layer, currentTime: currentTime, totalTime: totalTime)
        engine?.player(layer: layer, currentTime: currentTime, totalTime: totalTime)
    }

    func player(layer: KSPlayerLayer, finish error: Error?) {
        controlView?.player(layer: layer, finish: error)
        engine?.player(layer: layer, finish: error)
    }

    func player(layer: KSPlayerLayer, bufferedCount: Int, consumeTime: TimeInterval) {
        controlView?.player(layer: layer, bufferedCount: bufferedCount, consumeTime: consumeTime)
        engine?.player(layer: layer, bufferedCount: bufferedCount, consumeTime: consumeTime)
    }
}

private struct KSVideoPlayerController: UIViewRepresentable {
    let engine: KSPlayerEngine
    let controls: Bool

    func makeUIView(context: Context) -> IOSVideoPlayerView {
        let view = IOSVideoPlayerView(frame: .zero)
        view.controllerView.isHidden = !controls
        engine.attachControlView(view)
        return view
    }

    func updateUIView(_ view: IOSVideoPlayerView, context: Context) {
        view.controllerView.isHidden = !controls
        engine.attachControlView(view)
    }
}

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
