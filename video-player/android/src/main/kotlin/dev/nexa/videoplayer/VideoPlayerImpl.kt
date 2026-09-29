package dev.nexa.videoplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class VideoPlayerImpl : VideoPlayerSpec {
    private val prepareMutex = Mutex()
    private var engine: VideoPlayerEngine? = null

    public override var state: PlayerState = PlayerState.idle
        private set
    public override var duration: Double = 0.0
        private set
    public override var volume: Double = 1.0
        set(value) {
            field = value
            engine?.setVolume(value)
        }
    public override var onEnded: (() -> Unit)? = null

    internal var nativePlayer: ExoPlayer? = null
        private set

    internal fun attachContext(context: Context) {
        if (engine != null) return
        val player = ExoPlayer.Builder(context.applicationContext).build()
        nativePlayer = player
        attachEngine(ExoPlayerVideoPlayerEngine(player))
        engine?.setVolume(volume)
    }

    internal fun attachEngine(value: VideoPlayerEngine) {
        if (engine != null) return
        engine = value
        value.setListener(object : VideoPlayerEngine.Listener {
            override fun onStateChanged(state: PlayerState, durationSeconds: Double?) {
                this@VideoPlayerImpl.state = state
                if (durationSeconds != null) this@VideoPlayerImpl.duration = durationSeconds
            }

            override fun onEnded() {
                this@VideoPlayerImpl.onEnded?.invoke()
            }
        })
        value.setVolume(volume)
    }

    public override suspend fun prepare(url: String) {
        prepareMutex.withLock {
            val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") {
                state = PlayerState.failed
                throw PlayerError.invalidUrl
            }
            val playerEngine = engine ?: run {
                state = PlayerState.failed
                throw PlayerError.decodingFailed("VideoView has not attached a player context")
            }

            state = PlayerState.preparing
            try {
                playerEngine.prepare(url)
            } catch (error: PlayerError) {
                state = PlayerState.failed
                throw error
            }
        }
    }

    public override fun play() {
        engine?.play()
    }

    public override fun pause() {
        engine?.pause()
    }

    public override fun seek(position: Double) {
        engine?.seek(position)
    }

    public override fun dispose() {
        onEnded = null
        val playerEngine = engine
        engine = null
        nativePlayer = null
        playerEngine?.release()
        state = PlayerState.idle
    }
}

internal interface VideoPlayerEngine {
    interface Listener {
        fun onStateChanged(state: PlayerState, durationSeconds: Double? = null)
        fun onEnded()
    }

    fun setListener(listener: Listener?)
    suspend fun prepare(url: String)
    fun setVolume(volume: Double)
    fun play()
    fun pause()
    fun seek(position: Double)
    fun release()
}

private class ExoPlayerVideoPlayerEngine(private val player: ExoPlayer) : VideoPlayerEngine {
    private var listener: VideoPlayerEngine.Listener? = null
    private var pendingPrepare: CancellableContinuation<Unit>? = null
    private var pendingPrepareListener: Player.Listener? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onStateChanged(if (isPlaying) PlayerState.playing else PlayerState.paused)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onStateChanged(PlayerState.preparing)
                Player.STATE_READY -> {
                    val durationMs = player.duration
                    val duration = if (durationMs == C.TIME_UNSET) 0.0 else durationMs / 1000.0
                    listener?.onStateChanged(PlayerState.ready, duration)
                }
                Player.STATE_ENDED -> {
                    listener?.onStateChanged(PlayerState.ended)
                    listener?.onEnded()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onStateChanged(PlayerState.failed)
        }
    }

    init {
        player.addListener(playerListener)
    }

    override fun setListener(listener: VideoPlayerEngine.Listener?) {
        this.listener = listener
    }

    override suspend fun prepare(url: String) {
        suspendCancellableCoroutine { continuation ->
            val prepareListener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        player.removeListener(this)
                        clearPendingPrepare(continuation)
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    player.removeListener(this)
                    clearPendingPrepare(continuation)
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            PlayerError.decodingFailed(error.message ?: "Video playback failed"),
                        )
                    }
                }
            }

            pendingPrepare = continuation
            pendingPrepareListener = prepareListener
            player.addListener(prepareListener)
            continuation.invokeOnCancellation {
                player.removeListener(prepareListener)
                clearPendingPrepare(continuation)
            }
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }
    }

    private fun clearPendingPrepare(continuation: CancellableContinuation<Unit>) {
        if (pendingPrepare === continuation) {
            pendingPrepare = null
            pendingPrepareListener = null
        }
    }

    override fun setVolume(volume: Double) {
        player.volume = volume.toFloat()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seek(position: Double) {
        player.seekTo((position * 1000).toLong())
    }

    override fun release() {
        setListener(null)
        pendingPrepare?.cancel(CancellationException("VideoPlayer was disposed"))
        pendingPrepareListener?.let(player::removeListener)
        pendingPrepare = null
        pendingPrepareListener = null
        player.removeListener(playerListener)
        player.release()
    }
}

/** Native visual implementation used by the generated VideoView wrapper. */
@Composable
public fun VideoViewImpl(
    player: VideoPlayer,
    controls: Boolean,
    onTapped: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box {
        AndroidView(
            modifier = if (onTapped == null) Modifier else Modifier.clickable { onTapped.invoke() },
            factory = { context ->
                player.attachContext(context)
                PlayerView(context).apply {
                    useController = controls
                    this.player = player.nativePlayer
                }
            },
            update = { view ->
                view.useController = controls
                view.player = player.nativePlayer
            },
        )
        content()
    }
}
