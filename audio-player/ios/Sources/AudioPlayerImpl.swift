import AVFoundation
import MediaPlayer

@MainActor
public final class AudioPlayerImpl: AudioPlayerSpec {
    private let session = AudioPlaybackSession.shared
    private var disposed = false

    public var onStateChanged: ((AudioPlaybackState) -> Void)?
    public var onEnded: (() -> Void)?

    public var state: AudioPlaybackState { session.state }
    public var duration: Double { session.duration }
    public var currentTime: Double { session.currentTime }

    public var volume: Double {
        get { session.volume }
        set { session.setVolume(newValue) }
    }

    public required init() {
        session.register(self)
    }

    public func prepare(_ url: String) async throws(AudioPlayerError) {
        guard !disposed else {
            throw .playbackFailed(message: "AudioPlayer is disposed")
        }
        try await session.prepare(url)
    }

    public func updateMetadata(_ title: String, _ artist: String?, _ album: String?) {
        guard !disposed else { return }
        session.updateMetadata(title: title, artist: artist, album: album)
    }

    public func play() {
        guard !disposed else { return }
        session.play()
    }

    public func pause() {
        guard !disposed else { return }
        session.pause()
    }

    public func seek(_ position: Double) {
        guard !disposed else { return }
        session.seek(position)
    }

    public func dispose() {
        guard !disposed else { return }
        disposed = true
        session.unregister(self)
        onStateChanged = nil
        onEnded = nil
    }

    fileprivate func playbackStateDidChange(_ state: AudioPlaybackState) {
        onStateChanged?(state)
    }

    fileprivate func playbackDidEnd() {
        onEnded?()
    }
}

@MainActor
private final class AudioPlaybackSession {
    static let shared = AudioPlaybackSession()

    private final class WeakPlayer {
        weak var value: AudioPlayerImpl?

        init(_ value: AudioPlayerImpl) {
            self.value = value
        }
    }

    private let commandCenter = MPRemoteCommandCenter.shared()
    private var handles: [ObjectIdentifier: WeakPlayer] = [:]
    private var player: AVPlayer?
    private var endObserver: NSObjectProtocol?
    private var timeObserver: Any?
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    private var metadata: (title: String, artist: String?, album: String?)?

    private(set) var state: AudioPlaybackState = .idle
    private(set) var duration: Double = 0
    private(set) var volume: Double = 1

    var currentTime: Double {
        guard let player else { return 0 }
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, seconds) : 0
    }

    func register(_ handle: AudioPlayerImpl) {
        handles[ObjectIdentifier(handle)] = WeakPlayer(handle)
    }

    func unregister(_ handle: AudioPlayerImpl) {
        handles.removeValue(forKey: ObjectIdentifier(handle))
        discardReleasedHandles()
        if handles.isEmpty {
            shutdown()
        }
    }

    func prepare(_ url: String) async throws(AudioPlayerError) {
        guard let components = URLComponents(string: url),
              let scheme = components.scheme?.lowercased(),
              ["http", "https", "file"].contains(scheme),
              let parsedURL = components.url
        else {
            setState(.failed)
            throw .invalidUrl
        }

        setState(.preparing)
        let asset = AVURLAsset(url: parsedURL)
        do {
            guard try await asset.load(.isPlayable) else {
                setState(.failed)
                throw AudioPlayerError.playbackFailed(message: "The media is not playable")
            }
            let loadedDuration = try await asset.load(.duration)
            let activePlayer = ensurePlayer()
            activePlayer.replaceCurrentItem(with: AVPlayerItem(asset: asset))
            duration = loadedDuration.seconds.isFinite ? max(0, loadedDuration.seconds) : 0
            observeEnd(of: activePlayer.currentItem)
            updateNowPlaying()
            setState(.ready)
        } catch let error as AudioPlayerError {
            setState(.failed)
            throw error
        } catch {
            setState(.failed)
            throw .playbackFailed(message: error.localizedDescription)
        }
    }

    func updateMetadata(title: String, artist: String?, album: String?) {
        metadata = (title, artist, album)
        updateNowPlaying()
    }

    func setVolume(_ value: Double) {
        let clamped = value.isFinite ? min(max(value, 0), 1) : 1
        volume = clamped
        player?.volume = Float(clamped)
    }

    func play() {
        guard let player, player.currentItem != nil else { return }
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default)
            try audioSession.setActive(true)
            player.play()
            setState(.playing)
            updateNowPlaying()
        } catch {
            NSLog("Nexa AudioPlayer could not activate playback: %@", error.localizedDescription)
            setState(.failed)
        }
    }

    func pause() {
        guard let player else { return }
        player.pause()
        if state != .idle && state != .ended {
            setState(.paused)
        }
        updateNowPlaying()
    }

    func seek(_ position: Double) {
        guard let player, position.isFinite else { return }
        let upperBound = duration > 0 ? duration : position
        let target = min(max(position, 0), upperBound)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600)) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.updateNowPlaying()
            }
        }
    }

    private func ensurePlayer() -> AVPlayer {
        if let player { return player }
        let player = AVPlayer()
        player.actionAtItemEnd = .pause
        player.volume = Float(volume)
        self.player = player

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 1_000),
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.updateNowPlaying()
            }
        }
        installRemoteCommands()
        return player
    }

    private func observeEnd(of item: AVPlayerItem?) {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        guard let item else { return }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.setState(.ended)
                self.updateNowPlaying()
                self.forEachHandle { $0.playbackDidEnd() }
            }
        }
    }

    private func installRemoteCommands() {
        let play = commandCenter.playCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.play() }
            return .success
        }
        let pause = commandCenter.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in self?.pause() }
            return .success
        }
        let seek = commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else {
                return .commandFailed
            }
            Task { @MainActor [weak self] in self?.seek(event.positionTime) }
            return .success
        }
        commandTargets = [
            (commandCenter.playCommand, play),
            (commandCenter.pauseCommand, pause),
            (commandCenter.changePlaybackPositionCommand, seek),
        ]
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.isEnabled = true
    }

    private func setState(_ next: AudioPlaybackState) {
        guard state != next else { return }
        state = next
        forEachHandle { $0.playbackStateDidChange(next) }
    }

    private func updateNowPlaying() {
        guard player != nil else { return }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: state == .playing ? 1.0 : 0.0,
            MPMediaItemPropertyPlaybackDuration: duration,
        ]
        if let title = metadata?.title {
            info[MPMediaItemPropertyTitle] = title
        }
        if let artist = metadata?.artist {
            info[MPMediaItemPropertyArtist] = artist
        }
        if let album = metadata?.album {
            info[MPMediaItemPropertyAlbumTitle] = album
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func forEachHandle(_ body: (AudioPlayerImpl) -> Void) {
        discardReleasedHandles()
        for handle in handles.values.compactMap(\.value) {
            body(handle)
        }
    }

    private func discardReleasedHandles() {
        handles = handles.filter { $0.value.value != nil }
    }

    private func shutdown() {
        if let timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
            self.endObserver = nil
        }
        for (command, target) in commandTargets {
            command.removeTarget(target)
        }
        commandTargets.removeAll(keepingCapacity: false)
        commandCenter.playCommand.isEnabled = false
        commandCenter.pauseCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = false
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        duration = 0
        metadata = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        setState(.idle)
    }
}
