package dev.nexa.audio

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
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
import java.lang.ref.WeakReference
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
    private var progressRunnable: Runnable? = null
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerDeadlineMs: Long = 0L
    private var sleepTimerPauseAtTrackEndValue: Boolean = false
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

    override var volume: Double
        get() = AudioPlaybackVolume.volume.toDouble()
        set(value) {
            AudioPlaybackVolume.volume = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 1f
            dispatch { it.volume = AudioPlaybackVolume.outputVolume }
        }

    override var playbackSpeed: Double = 1.0
        set(value) {
            val speed = value.takeIf(Double::isFinite)?.coerceIn(0.25, 3.0) ?: 1.0
            field = speed
            dispatch { it.setPlaybackParameters(PlaybackParameters(speed.toFloat())) }
        }

    override var muted: Boolean
        get() = AudioPlaybackVolume.muted
        set(value) {
            AudioPlaybackVolume.muted = value
            dispatch { it.volume = AudioPlaybackVolume.outputVolume }
        }

    override var onStateChanged: ((AudioPlaybackState) -> Unit)? = null
    override var onEnded: (() -> Unit)? = null
    override var onSleepTimerEnded: (() -> Unit)? = null
    override var onSkipToNext: (() -> Unit)? = null
    override var onSkipToPrevious: (() -> Unit)? = null
    override var onProgressChanged: ((Double, Double) -> Unit)? = null

    override val isSleepTimerActive: Boolean
        get() = sleepTimerRunnable != null || sleepTimerPauseAtTrackEndValue

    override val sleepTimerRemainingSeconds: Long
        get() = ((sleepTimerDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L

    override val sleepTimerPauseAtTrackEnd: Boolean
        get() = sleepTimerPauseAtTrackEndValue

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> setState(AudioPlaybackState.idle)
                Player.STATE_BUFFERING -> setState(AudioPlaybackState.preparing)
                Player.STATE_READY -> updateReadyState()
                Player.STATE_ENDED -> {
                    setState(AudioPlaybackState.ended)
                    if (sleepTimerPauseAtTrackEndValue) {
                        finishSleepTimer(pausePlayback = false)
                    } else {
                        onEnded?.invoke()
                    }
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
        AudioPlaybackEvents.register(this)
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
                connected.volume = AudioPlaybackVolume.outputVolume
                connected.setPlaybackParameters(PlaybackParameters(playbackSpeed.toFloat()))
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

    override suspend fun prepare(source: AudioSource) {
        prepareMutex.withLock {
            val uri = runCatching { Uri.parse(source.url) }.getOrNull()
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
                AudioRequestHeaders.update(source.headers)
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
                    val itemBuilder = MediaItem.Builder()
                        .setUri(uri)
                        .setMediaMetadata(mediaMetadata(lastMetadata))
                        .setTag(AudioPlaybackMetadata(source.loudnessDb))
                    if (source.isHLS) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    val item = itemBuilder.build()
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

    override fun configure(skipSilence: Boolean, audioNormalization: Boolean, pauseOnMute: Boolean) {
        AudioPlaybackConfiguration.skipSilence = skipSilence
        AudioPlaybackConfiguration.audioNormalization = audioNormalization
        AudioPlaybackConfiguration.pauseOnMute = pauseOnMute
        AudioPlaybackServiceInstance.updateSkipSilence(skipSilence)
        AudioPlaybackServiceInstance.updateAudioNormalization(audioNormalization)
        AudioPlaybackServiceInstance.evaluateDeviceMute()
    }

    override fun openSystemEqualizer(): Boolean {
        if (disposed) return false
        val activity = NexaRuntimeCore.currentActivity() ?: return false
        return AudioPlaybackServiceInstance.openSystemEqualizer(activity)
    }

    override fun play() {
        dispatch { player ->
            hasPlayed = true
            player.setPlaybackParameters(PlaybackParameters(playbackSpeed.toFloat()))
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

    override fun startSleepTimer(minutes: Int) {
        clearSleepTimer()
        if (minutes <= 0 || disposed) return
        val durationMs = minutes.toLong() * 60_000L
        sleepTimerDeadlineMs = SystemClock.elapsedRealtime() + durationMs
        val runnable = Runnable { finishSleepTimer(pausePlayback = true) }
        sleepTimerRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }

    override fun startSleepTimerAtTrackEnd() {
        clearSleepTimer()
        if (!disposed) sleepTimerPauseAtTrackEndValue = true
    }

    override fun clearSleepTimer() {
        sleepTimerRunnable?.let(mainHandler::removeCallbacks)
        sleepTimerRunnable = null
        sleepTimerDeadlineMs = 0L
        sleepTimerPauseAtTrackEndValue = false
    }

    private fun finishSleepTimer(pausePlayback: Boolean) {
        if (disposed) return
        if (pausePlayback) pause()
        clearSleepTimer()
        onSleepTimerEnded?.invoke()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingCommands.clear()
        val isLastHandle = AudioPlaybackEvents.unregister(this)
        val connected = controller
        if (connected != null) {
            connected.removeListener(playerListener)
            if (isLastHandle) connected.stop()
            connected.release()
            controller = null
        } else {
            MediaController.releaseFuture(controllerFuture)
        }
        controllerReady.cancel()
        onEnded = null
        onSleepTimerEnded = null
        onSkipToNext = null
        onSkipToPrevious = null
        onProgressChanged = null
        progressRunnable?.let(mainHandler::removeCallbacks)
        progressRunnable = null
        clearSleepTimer()
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
        if (next == AudioPlaybackState.playing) {
            scheduleProgressUpdates()
        } else {
            progressRunnable?.let(mainHandler::removeCallbacks)
            progressRunnable = null
        }
    }

    internal fun requestSkipToNext() {
        onSkipToNext?.invoke()
    }

    internal fun requestSkipToPrevious() {
        onSkipToPrevious?.invoke()
    }

    private fun scheduleProgressUpdates() {
        if (progressRunnable != null || disposed) return
        val runnable = object : Runnable {
            override fun run() {
                if (disposed || stateValue != AudioPlaybackState.playing) {
                    progressRunnable = null
                    return
                }
                onProgressChanged?.invoke(currentTime, duration)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        progressRunnable = runnable
        mainHandler.postDelayed(runnable, 1_000L)
    }
}

/** Weak listeners let the foreground app own queue behavior without retaining handles. */
internal object AudioPlaybackEvents {
    private val handles = mutableListOf<WeakReference<AudioPlayerImpl>>()

    @Synchronized
    fun register(handle: AudioPlayerImpl) {
        discardReleasedHandles()
        if (handles.none { it.get() === handle }) handles.add(WeakReference(handle))
    }

    @Synchronized
    fun unregister(handle: AudioPlayerImpl): Boolean {
        handles.removeAll { it.get() == null || it.get() === handle }
        discardReleasedHandles()
        return handles.isEmpty()
    }

    fun requestSkipToNext() = forEachHandle(AudioPlayerImpl::requestSkipToNext)

    fun requestSkipToPrevious() = forEachHandle(AudioPlayerImpl::requestSkipToPrevious)

    private fun forEachHandle(action: (AudioPlayerImpl) -> Unit) {
        val snapshot = synchronized(this) {
            discardReleasedHandles()
            handles.mapNotNull { it.get() }
        }
        snapshot.forEach(action)
    }

    private fun discardReleasedHandles() {
        handles.removeAll { it.get() == null }
    }
}
