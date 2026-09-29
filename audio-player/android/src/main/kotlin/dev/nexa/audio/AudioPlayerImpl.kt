package dev.nexa.audio

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.nexa.core.NexaRuntimeCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A typed client for the app-wide MediaSession hosted by [AudioPlaybackService]. */
public class AudioPlayerImpl : AudioPlayerSpec {
    private data class TrackMetadata(val title: String, val artist: String?, val album: String?)

    private val context = NexaRuntimeCore.context().applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        mainHandler.post(command)
    }
    private val prepareMutex = Mutex()
    private val controllerReady = CompletableDeferred<MediaController>()
    private val pendingCommands = ArrayDeque<(MediaController) -> Unit>()
    private val controllerFuture = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, AudioPlaybackService::class.java)),
    ).buildAsync()

    private var controller: MediaController? = null
    private var disposed = false
    private var hasPlayed = false
    private var lastMetadata: TrackMetadata? = null
    private var stateValue = AudioPlaybackState.idle

    override val state: AudioPlaybackState
        get() = stateValue

    override val duration: Double
        get() = controller?.duration
            ?.takeIf { it != C.TIME_UNSET && it >= 0L }
            ?.div(1_000.0)
            ?: 0.0

    override val currentTime: Double
        get() = controller?.currentPosition?.coerceAtLeast(0L)?.div(1_000.0) ?: 0.0

    override var volume: Double = 1.0
        set(value) {
            val clamped = value.coerceIn(0.0, 1.0)
            field = clamped
            dispatch { it.volume = clamped.toFloat() }
        }

    override var onStateChanged: ((AudioPlaybackState) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> setState(AudioPlaybackState.idle)
                Player.STATE_BUFFERING -> setState(AudioPlaybackState.preparing)
                Player.STATE_READY -> updateReadyState()
                Player.STATE_ENDED -> {
                    setState(AudioPlaybackState.ended)
                    onEnded?.invoke()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlayed = true
                setState(AudioPlaybackState.playing)
            } else {
                updateReadyState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            setState(AudioPlaybackState.failed)
        }
    }

    init {
        controllerFuture.addListener({
            try {
                val connected = controllerFuture.get()
                if (disposed) {
                    connected.release()
                    controllerReady.cancel()
                    return@addListener
                }
                controller = connected
                connected.addListener(playerListener)
                connected.volume = volume.toFloat()
                if (lastMetadata != null) applyMetadata(connected)
                while (pendingCommands.isNotEmpty()) {
                    pendingCommands.removeFirst()(connected)
                }
                controllerReady.complete(connected)
            } catch (error: Throwable) {
                controllerReady.completeExceptionally(error)
                setState(AudioPlaybackState.failed)
            }
        }, mainExecutor)
    }

    override suspend fun prepare(url: String) {
        prepareMutex.withLock {
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri == null || scheme !in setOf("http", "https", "file")) {
                withContext(Dispatchers.Main.immediate) { setState(AudioPlaybackState.failed) }
                throw AudioPlayerError.invalidUrl
            }

            val mediaController = try {
                controllerReady.await()
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                withContext(Dispatchers.Main.immediate) { setState(AudioPlaybackState.failed) }
                throw AudioPlayerError.playbackFailed(error.message ?: "Could not connect to audio service")
            }

            withContext(Dispatchers.Main.immediate) {
                if (disposed) throw AudioPlayerError.playbackFailed("AudioPlayer is disposed")
                setState(AudioPlaybackState.preparing)
                suspendCancellableCoroutine { continuation ->
                    val prepareListener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    mediaController.removeListener(this)
                                    if (continuation.isActive) {
                                        setState(AudioPlaybackState.ready)
                                        continuation.resume(Unit)
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    mediaController.removeListener(this)
                                    if (continuation.isActive) {
                                        setState(AudioPlaybackState.ended)
                                        continuation.resume(Unit)
                                    }
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            mediaController.removeListener(this)
                            if (continuation.isActive) {
                                setState(AudioPlaybackState.failed)
                                continuation.resumeWithException(
                                    AudioPlayerError.playbackFailed(error.message ?: "Audio playback failed"),
                                )
                            }
                        }
                    }

                    mediaController.addListener(prepareListener)
                    continuation.invokeOnCancellation {
                        mainHandler.post { mediaController.removeListener(prepareListener) }
                    }
                    val item = MediaItem.Builder()
                        .setUri(uri)
                        .setMediaMetadata(mediaMetadata(lastMetadata))
                        .build()
                    mediaController.setMediaItem(item)
                    mediaController.prepare()
                }
            }
        }
    }

    override fun updateMetadata(title: String, artist: String?, album: String?) {
        lastMetadata = TrackMetadata(title, artist, album)
        dispatch(::applyMetadata)
    }

    override fun play() {
        dispatch { player ->
            hasPlayed = true
            player.play()
        }
    }

    override fun pause() {
        dispatch(MediaController::pause)
    }

    override fun seek(position: Double) {
        if (!position.isFinite()) return
        dispatch { player ->
            val upperBound = duration.takeIf { it > 0.0 } ?: position
            player.seekTo((position.coerceIn(0.0, upperBound) * 1_000.0).toLong())
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingCommands.clear()
        val connected = controller
        if (connected != null) {
            connected.removeListener(playerListener)
            connected.stop()
            connected.release()
            controller = null
        } else {
            MediaController.releaseFuture(controllerFuture)
        }
        controllerReady.cancel()
        onEnded = null
        onStateChanged = null
        stateValue = AudioPlaybackState.idle
    }

    private fun updateReadyState() {
        val player = controller ?: return
        if (player.playbackState != Player.STATE_READY) return
        setState(
            when {
                player.isPlaying -> AudioPlaybackState.playing
                hasPlayed -> AudioPlaybackState.paused
                else -> AudioPlaybackState.ready
            },
        )
    }

    private fun dispatch(command: (MediaController) -> Unit) {
        if (disposed) return
        val connected = controller
        if (connected != null) {
            command(connected)
        } else {
            pendingCommands.addLast(command)
        }
    }

    private fun applyMetadata(player: MediaController) {
        val current = player.currentMediaItem ?: return
        val index = player.currentMediaItemIndex
        player.replaceMediaItem(
            index,
            current.buildUpon().setMediaMetadata(mediaMetadata(lastMetadata)).build(),
        )
    }

    private fun mediaMetadata(metadata: TrackMetadata?): MediaMetadata =
        MediaMetadata.Builder().apply {
            metadata?.let {
                setTitle(it.title)
                setArtist(it.artist)
                setAlbumTitle(it.album)
            }
        }.build()

    private fun setState(next: AudioPlaybackState) {
        if (stateValue == next) return
        stateValue = next
        onStateChanged?.invoke(next)
    }
}
