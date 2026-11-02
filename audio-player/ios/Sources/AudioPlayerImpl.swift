import AVFoundation
import MediaPlayer

@MainActor
public final class AudioPlayerImpl: AudioPlayerSpec {
    private let session = AudioPlaybackSession.shared
    private var disposed = false

    public var onStateChanged: ((AudioPlaybackState) -> Void)?
    public var onEnded: (() -> Void)?
    public var onSleepTimerEnded: (() -> Void)?
    public var onSkipToNext: (() -> Void)?
    public var onSkipToPrevious: (() -> Void)?
    public var onProgressChanged: ((Double, Double) -> Void)?

    public var state: AudioPlaybackState { session.state }
    public var duration: Double { session.duration }
    public var currentTime: Double { session.currentTime }
    public var isSleepTimerActive: Bool { session.isSleepTimerActive }
    public var sleepTimerRemainingSeconds: Int64 { session.sleepTimerRemainingSeconds }
    public var sleepTimerPauseAtTrackEnd: Bool { session.sleepTimerPauseAtTrackEnd }

    public var playbackSpeed: Double {
        get { session.playbackSpeed }
        set { session.setPlaybackSpeed(newValue) }
    }

    public var muted: Bool {
        get { session.muted }
        set { session.setMuted(newValue) }
    }

    public var volume: Double {
        get { session.volume }
        set { session.setVolume(newValue) }
    }

    public required init() {
        session.register(self)
    }

    public func prepare(_ source: AudioSource) async throws(AudioPlayerError) {
        guard !disposed else {
            throw .playbackFailed(message: "AudioPlayer is disposed")
        }
        try await session.prepare(source)
    }

    public func configure(_ skipSilence: Bool, _ audioNormalization: Bool, _ pauseOnMute: Bool) {
        guard !disposed else { return }
        session.configure(skipSilence: skipSilence, audioNormalization: audioNormalization, pauseOnMute: pauseOnMute)
    }

    public func openSystemEqualizer() -> Bool {
        guard !disposed else { return false }
        return session.openSystemEqualizer()
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

    public func startSleepTimer(_ minutes: Int32) {
        guard !disposed else { return }
        session.startSleepTimer(minutes: minutes)
    }

    public func startSleepTimerAtTrackEnd() {
        guard !disposed else { return }
        session.startSleepTimerAtTrackEnd()
    }

    public func clearSleepTimer() {
        guard !disposed else { return }
        session.clearSleepTimer()
    }

    public func dispose() {
        guard !disposed else { return }
        disposed = true
        session.unregister(self)
        onStateChanged = nil
        onEnded = nil
        onSleepTimerEnded = nil
        onSkipToNext = nil
        onSkipToPrevious = nil
        onProgressChanged = nil
    }

    fileprivate func playbackStateDidChange(_ state: AudioPlaybackState) {
        onStateChanged?(state)
    }

    fileprivate func playbackDidEnd() {
        onEnded?()
    }

    fileprivate func sleepTimerDidEnd() {
        onSleepTimerEnded?()
    }

    fileprivate func skipToNextDidRequest() {
        onSkipToNext?()
    }

    fileprivate func skipToPreviousDidRequest() {
        onSkipToPrevious?()
    }

    fileprivate func playbackProgressDidChange(position: Double, duration: Double) {
        onProgressChanged?(position, duration)
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
    private var pendingAsset: AVURLAsset?
    private var prepareRevision: UInt64 = 0
    private var resourceLoader: AudioURLSessionResourceLoader?
    private var endObserver: NSObjectProtocol?
    private var timeObserver: Any?
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    private var metadata: (title: String, artist: String?, album: String?)?
    private var currentLoudnessDb: Double?
    private var sleepTimer: Timer?
    private var sleepTimerDeadlineUptime: TimeInterval?
    private var outputVolumeObservation: NSKeyValueObservation?
    private var pausedByDeviceMute = false
    private(set) var sleepTimerPauseAtTrackEnd = false
    private(set) var playbackSpeed = 1.0
    private(set) var muted = false
    private var audioNormalizationEnabled = true
    private var pauseOnMuteEnabled = true

    private(set) var state: AudioPlaybackState = .idle
    private(set) var duration: Double = 0
    private(set) var volume: Double = 1

    var isSleepTimerActive: Bool { sleepTimer != nil || sleepTimerPauseAtTrackEnd }
    var sleepTimerRemainingSeconds: Int64 {
        guard let sleepTimerDeadlineUptime else { return 0 }
        return max(0, Int64((sleepTimerDeadlineUptime - ProcessInfo.processInfo.systemUptime).rounded(.up)))
    }

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

    func prepare(_ source: AudioSource) async throws(AudioPlayerError) {
        let url = source.url
        guard let components = URLComponents(string: url),
              let scheme = components.scheme?.lowercased(),
              ["http", "https", "file"].contains(scheme),
              let parsedURL = components.url
        else {
            setState(.failed)
            throw .invalidUrl
        }

        prepareRevision &+= 1
        let revision = prepareRevision
        pendingAsset?.cancelLoading()
        pendingAsset = nil
        resourceLoader?.cancelAll()
        resourceLoader = nil
        if let player {
            player.pause()
            player.replaceCurrentItem(with: nil)
        }
        observeEnd(of: nil)
        duration = 0
        currentLoudnessDb = source.loudnessDb
        setState(.preparing)
        let asset: AVURLAsset
        var requestLoader: AudioURLSessionResourceLoader?
        if scheme == "http" || scheme == "https" {
            let loader = AudioURLSessionResourceLoader(headers: source.headers, isHLS: source.isHLS)
            guard let proxiedURL = loader.proxyURL(for: parsedURL) else {
                setState(.failed)
                throw .invalidUrl
            }
            let remoteAsset = AVURLAsset(url: proxiedURL)
            remoteAsset.resourceLoader.setDelegate(loader, queue: .main)
            resourceLoader = loader
            requestLoader = loader
            asset = remoteAsset
        } else {
            asset = AVURLAsset(url: parsedURL)
        }
        pendingAsset = asset
        do {
            guard try await asset.load(.isPlayable) else {
                guard revision == prepareRevision else { return }
                setState(.failed)
                throw AudioPlayerError.playbackFailed(message: "The media is not playable")
            }
            let loadedDuration = try await asset.load(.duration)
            let tracks = try await asset.load(.tracks)
            guard revision == prepareRevision else {
                requestLoader?.cancelAll()
                return
            }
            let activePlayer = ensurePlayer()
            let item = AVPlayerItem(asset: asset)
            item.audioMix = makeAudioMix(tracks: tracks, loudnessDb: source.loudnessDb)
            activePlayer.replaceCurrentItem(with: item)
            duration = loadedDuration.seconds.isFinite ? max(0, loadedDuration.seconds) : 0
            pendingAsset = nil
            observeEnd(of: activePlayer.currentItem)
            updateNowPlaying()
            setState(.ready)
        } catch let error as AudioPlayerError {
            guard revision == prepareRevision else { return }
            pendingAsset = nil
            requestLoader?.cancelAll()
            setState(.failed)
            throw error
        } catch {
            guard revision == prepareRevision else { return }
            pendingAsset = nil
            requestLoader?.cancelAll()
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
        player?.volume = muted ? 0 : Float(clamped)
    }

    func setMuted(_ value: Bool) {
        muted = value
        player?.volume = value ? 0 : Float(volume)
    }

    func setPlaybackSpeed(_ value: Double) {
        let speed = value.isFinite ? min(max(value, 0.25), 3.0) : 1.0
        playbackSpeed = speed
        if state == .playing {
            player?.rate = Float(speed)
            updateNowPlaying()
        }
    }

    func configure(skipSilence: Bool, audioNormalization: Bool, pauseOnMute: Bool) {
        // AVPlayer has no silence-skipping control; ExoPlayer applies this option natively on Android.
        _ = skipSilence
        audioNormalizationEnabled = audioNormalization
        pauseOnMuteEnabled = pauseOnMute
        if !pauseOnMute { pausedByDeviceMute = false }
        if let currentItem = player?.currentItem, let currentLoudnessDb {
            Task { @MainActor [weak self, weak currentItem] in
                guard let self, let currentItem else { return }
                do {
                    let tracks = try await currentItem.asset.load(.tracks)
                    guard self.player?.currentItem === currentItem else { return }
                    currentItem.audioMix = self.makeAudioMix(tracks: tracks, loudnessDb: currentLoudnessDb)
                } catch {
                    currentItem.audioMix = nil
                }
            }
        } else if let currentItem = player?.currentItem {
            currentItem.audioMix = nil
        }
    }

    func openSystemEqualizer() -> Bool {
        // iOS has no public system equalizer panel that third-party apps can launch.
        return false
    }

    func play() {
        guard let player, player.currentItem != nil else { return }
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default)
            try audioSession.setActive(true)
            player.playImmediately(atRate: Float(playbackSpeed))
            setState(.playing)
            handleDeviceVolumeChange(AVAudioSession.sharedInstance().outputVolume)
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

    func startSleepTimer(minutes: Int32) {
        clearSleepTimer()
        guard minutes > 0 else { return }
        let duration = TimeInterval(minutes) * 60
        sleepTimerDeadlineUptime = ProcessInfo.processInfo.systemUptime + duration
        sleepTimer = Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
            Task { @MainActor [weak self] in self?.finishSleepTimer(pausePlayback: true) }
        }
    }

    func startSleepTimerAtTrackEnd() {
        clearSleepTimer()
        sleepTimerPauseAtTrackEnd = true
    }

    func clearSleepTimer() {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepTimerDeadlineUptime = nil
        sleepTimerPauseAtTrackEnd = false
    }

    private func finishSleepTimer(pausePlayback: Bool) {
        if pausePlayback { pause() }
        clearSleepTimer()
        forEachHandle { $0.sleepTimerDidEnd() }
    }

    private func ensurePlayer() -> AVPlayer {
        if let player { return player }
        let player = AVPlayer()
        player.actionAtItemEnd = .pause
        player.volume = muted ? 0 : Float(volume)
        self.player = player

        outputVolumeObservation = AVAudioSession.sharedInstance().observe(
            \.outputVolume,
            options: [.new]
        ) { [weak self] session, _ in
            let outputVolume = session.outputVolume
            Task { @MainActor [weak self] in
                self?.handleDeviceVolumeChange(outputVolume)
            }
        }

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 1, preferredTimescale: 1_000),
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.updateNowPlaying()
                self.forEachHandle {
                    $0.playbackProgressDidChange(position: self.currentTime, duration: self.duration)
                }
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
                if self.sleepTimerPauseAtTrackEnd {
                    self.finishSleepTimer(pausePlayback: false)
                } else {
                    self.forEachHandle { $0.playbackDidEnd() }
                }
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
        let next = commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.pause()
                self?.forEachHandle { $0.skipToNextDidRequest() }
            }
            return .success
        }
        let previous = commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.pause()
                self?.forEachHandle { $0.skipToPreviousDidRequest() }
            }
            return .success
        }
        commandTargets = [
            (commandCenter.playCommand, play),
            (commandCenter.pauseCommand, pause),
            (commandCenter.changePlaybackPositionCommand, seek),
            (commandCenter.nextTrackCommand, next),
            (commandCenter.previousTrackCommand, previous),
        ]
        commandCenter.playCommand.isEnabled = true
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.changePlaybackPositionCommand.isEnabled = true
        commandCenter.nextTrackCommand.isEnabled = true
        commandCenter.previousTrackCommand.isEnabled = true
    }

    private func setState(_ next: AudioPlaybackState) {
        guard state != next else { return }
        state = next
        forEachHandle { $0.playbackStateDidChange(next) }
    }

    private func handleDeviceVolumeChange(_ outputVolume: Float) {
        guard pauseOnMuteEnabled, let player else { return }
        if outputVolume <= 0, player.rate > 0 {
            pausedByDeviceMute = true
            pause()
        } else if outputVolume > 0, pausedByDeviceMute {
            pausedByDeviceMute = false
            play()
        }
    }

    private func makeAudioMix(tracks: [AVAssetTrack], loudnessDb: Double?) -> AVAudioMix? {
        guard audioNormalizationEnabled, let loudnessDb, loudnessDb.isFinite, loudnessDb != 0 else { return nil }
        let gainMillibels = Int(min(max(-loudnessDb * 100.0, -1_500.0), 300.0).rounded())
        let linearGain = Float(pow(10.0, (Double(gainMillibels) / 100.0) / 20.0))
        let inputParameters = tracks
            .filter { $0.mediaType == .audio }
            .map { track in
                let parameters = AVMutableAudioMixInputParameters(track: track)
                parameters.setVolume(linearGain, at: .zero)
                return parameters
            }
        guard !inputParameters.isEmpty else { return nil }
        let audioMix = AVMutableAudioMix()
        audioMix.inputParameters = inputParameters
        return audioMix
    }

    private func updateNowPlaying() {
        guard player != nil else { return }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: state == .playing ? playbackSpeed : 0.0,
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
        clearSleepTimer()
        pendingAsset?.cancelLoading()
        pendingAsset = nil
        resourceLoader?.cancelAll()
        resourceLoader = nil
        outputVolumeObservation = nil
        commandCenter.playCommand.isEnabled = false
        commandCenter.pauseCommand.isEnabled = false
        commandCenter.changePlaybackPositionCommand.isEnabled = false
        commandCenter.nextTrackCommand.isEnabled = false
        commandCenter.previousTrackCommand.isEnabled = false
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        duration = 0
        currentLoudnessDb = nil
        metadata = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        setState(.idle)
    }
}
