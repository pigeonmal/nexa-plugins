import AVFoundation
import Combine
import Foundation
import SwiftUI
import UIKit

/// Native visual implementation used by the generated `VideoView` wrapper.
public struct VideoViewImpl<Content: View>: View {
    public let player: VideoPlayer
    public let controls: Bool
    public let content: Content

    init(
        player: VideoPlayer,
        controls: Bool,
        softwareDecodingEnabled _: Bool,
        content: Content
    ) {
        self.player = player
        self.controls = controls
        self.content = content
    }

    public var body: some View {
        ZStack {
            if let player = player.player {
                VideoPlayerLayerHost(player: player)
            } else {
                Color.black
            }
            if controls, let player = player.player {
                VideoPlayerControlsOverlay(player: player)
            }
            content
        }
        .clipped()
    }
}

private final class VideoPlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }

    var playerLayer: AVPlayerLayer? { layer as? AVPlayerLayer }

    override init(frame: CGRect) {
        super.init(frame: frame)
        playerLayer?.videoGravity = .resizeAspectFill
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        playerLayer?.videoGravity = .resizeAspectFill
    }
}

private struct VideoPlayerLayerHost: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> VideoPlayerLayerView {
        let view = VideoPlayerLayerView()
        view.playerLayer?.player = player
        return view
    }

    func updateUIView(_ view: VideoPlayerLayerView, context: Context) {
        view.playerLayer?.player = player
    }
}

private struct VideoPlayerControlsOverlay: View {
    let player: AVPlayer

    @StateObject private var model = VideoPlayerControlsModel()

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Button(action: togglePlayback) {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 58, height: 58)
                    .background(.black.opacity(0.42), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.isPlaying ? "Pause video" : "Play video")
            Spacer()
            HStack(spacing: 8) {
                Text(timeLabel(model.playbackTime))
                    .monospacedDigit()
                Slider(
                    value: $model.playbackTime,
                    in: 0...max(model.duration, 1),
                    onEditingChanged: model.setScrubbing
                )
                Text(timeLabel(model.duration))
                    .monospacedDigit()
            }
            .font(.caption2)
            .foregroundStyle(.white)
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
        }
        .background(
            LinearGradient(
                colors: [.clear, .black.opacity(0.5)],
                startPoint: .center,
                endPoint: .bottom
            )
        )
        .onAppear { model.startObserving(player) }
        .onDisappear { model.stopObserving() }
    }

    private func togglePlayback() {
        model.togglePlayback(player)
    }

    private func timeLabel(_ seconds: Double) -> String {
        let value = Int(max(0, seconds.isFinite ? seconds : 0))
        return "\(value / 60):\(String(format: "%02d", value % 60))"
    }
}

@MainActor
private final class VideoPlayerControlsModel: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published var isScrubbing = false
    @Published var playbackTime = 0.0
    @Published private(set) var duration = 0.0

    private weak var player: AVPlayer?
    private var timeObserver: Any?

    func startObserving(_ player: AVPlayer) {
        guard self.player !== player || timeObserver == nil else { return }
        stopObserving()
        self.player = player
        updateDuration(for: player)
        isPlaying = player.timeControlStatus == .playing
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: .main
        ) { [weak self, weak player] time in
            Task { @MainActor [weak self, weak player] in
                guard let self, let player else { return }
                if !self.isScrubbing, time.seconds.isFinite {
                    self.playbackTime = max(0, time.seconds)
                }
                self.isPlaying = player.timeControlStatus == .playing
                self.updateDuration(for: player)
            }
        }
    }

    func setScrubbing(_ editing: Bool) {
        isScrubbing = editing
        if !editing {
            player?.seek(to: CMTime(seconds: playbackTime, preferredTimescale: 600))
        }
    }

    func togglePlayback(_ player: AVPlayer) {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
    }

    func stopObserving() {
        if let player, let timeObserver {
            player.removeTimeObserver(timeObserver)
        }
        player = nil
        timeObserver = nil
    }

    private func updateDuration(for player: AVPlayer) {
        let itemDuration = player.currentItem?.duration.seconds ?? 0
        duration = itemDuration.isFinite ? max(0, itemDuration) : 0
    }
}
