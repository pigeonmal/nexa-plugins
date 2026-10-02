import AVFoundation
import Foundation

@MainActor
protocol VideoPlayerEngine: AnyObject {
    var player: AVPlayer? { get }
    var volume: Double { get set }
    var onEnded: (() -> Void)? { get set }

    func prepare(url: URL) async throws(PlayerError) -> Double
    func preload(url: String, index: Int32)
    func setPreloadPosition(_ index: Int32)
    func play()
    func pause()
    func seek(position: Double)
    func dispose()
}

@MainActor
extension VideoPlayerEngine {
    func preload(url: String, index: Int32) {}
    func setPreloadPosition(_ index: Int32) {}
}

@MainActor
private final class AVPlayerEngine: VideoPlayerEngine {
    private(set) var player: AVPlayer? = AVPlayer()
    var volume: Double = 1.0 {
        didSet { player?.volume = Float(volume) }
    }
    var onEnded: (() -> Void)?
    private var endedObserver: NSObjectProtocol?
    private var currentPreloadPosition: Int32 = 0
    private var activeURL: String?
    private var preloadedPlayers: [String: AVPlayer] = [:]
    private var preloadedIndexes: [String: Int32] = [:]
    private var preloadObservers: [String: (AVPlayer, Any)] = [:]

    func prepare(url: URL) async throws(PlayerError) -> Double {
        let key = url.absoluteString
        let warmPlayer = preloadedPlayers.removeValue(forKey: key)
        let item = warmPlayer?.currentItem ?? AVPlayerItem(url: url)
        let mediaDuration: CMTime
        do {
            mediaDuration = try await item.asset.load(.duration)
        } catch {
            throw PlayerError.decodingFailed(message: error.localizedDescription)
        }

        removeEndedObserver()
        let activePlayer = warmPlayer ?? self.player ?? AVPlayer()
        if self.player !== activePlayer {
            if let previousPlayer = self.player {
                previousPlayer.pause()
                if let previousURL = activeURL,
                   currentPreloadPosition > 0,
                   preloadedPlayers[previousURL] == nil
                {
                    preloadedPlayers[previousURL] = previousPlayer
                    preloadedIndexes[previousURL] = currentPreloadPosition - 1
                }
            }
        }
        activePlayer.pause()
        activePlayer.isMuted = false
        if warmPlayer == nil {
            activePlayer.replaceCurrentItem(with: item)
        } else {
            removePreloadObserver(for: key)
            await activePlayer.seek(to: .zero)
        }
        activePlayer.volume = Float(volume)
        self.player = activePlayer
        activeURL = key
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

    func preload(url: String, index: Int32) {
        guard index >= currentPreloadPosition + 1,
              index <= currentPreloadPosition + 2,
              preloadedPlayers[url] == nil,
              let candidateURL = URL(string: url),
              candidateURL.scheme == "https" || candidateURL.scheme == "http"
        else {
            return
        }

        let item = AVPlayerItem(url: candidateURL)
        item.preferredForwardBufferDuration = 1.5
        let candidate = AVPlayer(playerItem: item)
        candidate.isMuted = true
        candidate.automaticallyWaitsToMinimizeStalling = true
        preloadedPlayers[url] = candidate
        preloadedIndexes[url] = index
        let observer = candidate.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self, weak candidate] time in
            guard time.seconds >= 1.0,
                  let self,
                  let candidate
            else {
                return
            }
            candidate.pause()
            candidate.seek(to: .zero)
            Task { @MainActor [weak self] in
                self?.removePreloadObserver(for: url)
            }
        }
        preloadObservers[url] = (candidate, observer)
        candidate.play()
    }

    func setPreloadPosition(_ index: Int32) {
        currentPreloadPosition = max(0, index)
        let staleURLs = preloadedIndexes.compactMap { url, position in
            position < currentPreloadPosition - 2 || position > currentPreloadPosition + 2 ? url : nil
        }
        for url in staleURLs {
            if let candidate = preloadedPlayers.removeValue(forKey: url) {
                candidate.pause()
                candidate.replaceCurrentItem(with: nil)
            }
            preloadedIndexes.removeValue(forKey: url)
            removePreloadObserver(for: url)
        }
    }

    private func removePreloadObserver(for url: String) {
        guard let (candidate, observer) = preloadObservers.removeValue(forKey: url) else { return }
        candidate.removeTimeObserver(observer)
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
        for candidate in preloadedPlayers.values {
            candidate.pause()
            candidate.replaceCurrentItem(with: nil)
        }
        for url in preloadObservers.keys {
            removePreloadObserver(for: url)
        }
        preloadedPlayers.removeAll()
        preloadedIndexes.removeAll()
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
    public var looping = false
    public var onEnded: (() -> Void)?

    var player: AVPlayer? { engine.player }
    private var engine: any VideoPlayerEngine
    private var prepareGeneration: UInt64 = 0
    private var isDisposed = false
    private var preparedURL: String?

    public convenience init() {
        self.init(engine: AVPlayerEngine())
    }

    init(engine: any VideoPlayerEngine) {
        self.engine = engine
        self.volume = engine.volume
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
        if preparedURL == url.absoluteString,
           state == .ready || state == .paused || state == .playing {
            return
        }
        state = .preparing
        do {
            let preparedDuration = try await engine.prepare(url: url)
            guard generation == prepareGeneration else { return }
            preparedURL = url.absoluteString
            duration = preparedDuration
            state = .ready
        } catch {
            if generation == prepareGeneration {
                preparedURL = nil
                state = .failed
            }
            throw error
        }
    }

    public func preload(_ url: String, _ index: Int32) {
        engine.preload(url: url, index: index)
    }

    public func setPreloadPosition(_ index: Int32) {
        engine.setPreloadPosition(index)
    }

    private func bindEngineEvents(_ engine: any VideoPlayerEngine) {
        engine.onEnded = { [weak self] in
            guard let self else { return }
            if self.looping {
                self.engine.seek(position: 0)
                self.engine.play()
                self.state = .playing
                return
            }
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
        preparedURL = nil
        onEnded = nil
        engine.onEnded = nil
        engine.dispose()
        state = .idle
    }
}
