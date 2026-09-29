package dev.nexa.videoplayer

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import java.net.URI
import java.util.WeakHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class VideoPlayerImpl : VideoPlayerSpec {
    private val prepareMutex = Mutex()
    private var engine: VideoPlayerEngine? = null
    private var pictureInPictureBindings: MutableList<AutoCloseable>? = null

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

    internal fun attachContext(context: Context, softwareDecodingEnabled: Boolean = true) {
        if (engine != null) return
        val applicationContext = context.applicationContext
        val player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(applicationContext)
                    .setDataSourceFactory(VideoPlayerCronetRuntime.dataSourceFactory(applicationContext)),
            )
            .setRenderersFactory(
                NextRenderersFactory(applicationContext).setExtensionRendererMode(
                    if (softwareDecodingEnabled) {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    } else {
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    },
                ),
            )
            .build()
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

    internal fun attachPictureInPictureBinding(binding: AutoCloseable) {
        val bindings = pictureInPictureBindings ?: ArrayList<AutoCloseable>(1).also {
            pictureInPictureBindings = it
        }
        bindings += binding
    }

    internal fun detachPictureInPictureBinding(binding: AutoCloseable) {
        pictureInPictureBindings?.remove(binding)
        binding.close()
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
        pictureInPictureBindings?.toList()?.forEach(AutoCloseable::close)
        pictureInPictureBindings = null
        val playerEngine = engine
        engine = null
        nativePlayer = null
        playerEngine?.release()
        state = PlayerState.idle
    }
}

/** One embedded Cronet engine and response executor are shared by all players. */
private object VideoPlayerCronetRuntime {
    private val responseExecutor: Executor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "NexaVideoCronet").apply { isDaemon = true }
    }

    @Volatile
    private var sharedEngine: CronetEngine? = null

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val cronetFactory = CronetDataSource.Factory(engine(context), responseExecutor)
        return DefaultDataSource.Factory(context.applicationContext, cronetFactory)
    }

    private fun engine(context: Context): CronetEngine {
        sharedEngine?.let { return it }
        return synchronized(this) {
            sharedEngine ?: requireNotNull(
                CronetUtil.buildCronetEngine(context.applicationContext, "NexaVideoPlayer", false),
            ) {
                "Embedded Cronet is unavailable; ensure cronet-embedded is packaged in the Android app"
            }.also { sharedEngine = it }
        }
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
    softwareDecodingEnabled: Boolean,
    onTapped: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val pictureInPicture = remember(player) {
        mutableStateOf(activity?.isInPictureInPictureMode == true)
    }
    Box {
        AndroidView(
            modifier = if (onTapped == null) Modifier else Modifier.clickable { onTapped.invoke() },
            factory = { context ->
                player.attachContext(context, softwareDecodingEnabled)
                NexaPlayerView(context).apply {
                    useController = controls
                    this.player = player.nativePlayer
                    val exoPlayer = player.nativePlayer
                    if (activity != null && exoPlayer != null) {
                        pipRegistration = PictureInPictureRegistry.register(
                            activity,
                            exoPlayer,
                            this,
                        ) { isInPictureInPictureMode ->
                            pictureInPicture.value = isInPictureInPictureMode
                        }
                        pipRegistration?.let {
                            pipOwner = player
                            player.attachPictureInPictureBinding(it)
                        }
                    }
                }
            },
            update = { view ->
                view.useController = controls && !pictureInPicture.value
                view.player = player.nativePlayer
            },
            onRelease = { view ->
                view.pipRegistration?.let { view.pipOwner?.detachPictureInPictureBinding(it) }
                view.pipRegistration = null
                view.pipOwner = null
            },
        )
        if (!pictureInPicture.value) content()
    }
}

private class NexaPlayerView(context: Context) : PlayerView(context) {
    var pipRegistration: PictureInPictureRegistry.Registration? = null
    var pipOwner: VideoPlayerImpl? = null
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? ComponentActivity
}

/** Shares the one Activity PiP parameter slot across the player's attached views. */
private object PictureInPictureRegistry {
    private val registries = WeakHashMap<ComponentActivity, java.lang.ref.WeakReference<Controller>>()

    @Synchronized
    fun register(
        activity: ComponentActivity,
        player: ExoPlayer,
        view: PlayerView,
        onModeChanged: (Boolean) -> Unit,
    ): Registration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return null
        }
        val controller = registries[activity]?.get() ?: Controller(activity).also {
            registries[activity] = java.lang.ref.WeakReference(it)
        }
        return controller.register(player, view, onModeChanged)
    }

    class Registration internal constructor(
        private val controller: Controller,
        internal val player: ExoPlayer,
        internal val view: PlayerView,
        private val onModeChanged: (Boolean) -> Unit,
    ) : AutoCloseable {
        internal var priority: Long = 0
        private var closed = false

        private val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.refresh(this@Registration, becameActive = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                controller.refresh(this@Registration)
            }
        }

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            controller.refresh(this@Registration)
        }

        init {
            player.addListener(playerListener)
            view.addOnLayoutChangeListener(layoutListener)
        }

        internal fun dispatchPictureInPictureMode(isInPictureInPictureMode: Boolean) {
            if (!closed) onModeChanged(isInPictureInPictureMode)
        }

        override fun close() {
            if (closed) return
            closed = true
            player.removeListener(playerListener)
            view.removeOnLayoutChangeListener(layoutListener)
            controller.unregister(this)
        }
    }

    internal class Controller(private val activity: ComponentActivity) {
        private val registrations = ArrayList<Registration>(1)
        private var nextPriority = 0L
        private var lastParams: ParamsKey? = null

        private val onUserLeaveHint = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val active = selectActive()
                if (active != null && !activity.isInPictureInPictureMode && !activity.isFinishing) {
                    val params = buildParams(active, autoEnter = false)
                    activity.enterPictureInPictureMode(params)
                }
            }
        }

        private val onPictureInPictureModeChanged = Consumer<PictureInPictureModeChangedInfo> { info ->
            registrations.forEach {
                it.dispatchPictureInPictureMode(info.isInPictureInPictureMode)
            }
        }

        init {
            activity.addOnPictureInPictureModeChangedListener(onPictureInPictureModeChanged)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                activity.addOnUserLeaveHintListener(onUserLeaveHint)
            }
        }

        fun register(
            player: ExoPlayer,
            view: PlayerView,
            onModeChanged: (Boolean) -> Unit,
        ): Registration = Registration(this, player, view, onModeChanged).also {
            registrations += it
            if (player.isPlaying) it.priority = ++nextPriority
            it.dispatchPictureInPictureMode(activity.isInPictureInPictureMode)
            refresh(it)
        }

        fun unregister(registration: Registration) {
            registrations.remove(registration)
            if (registrations.isEmpty()) {
                activity.removeOnPictureInPictureModeChangedListener(onPictureInPictureModeChanged)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    activity.removeOnUserLeaveHintListener(onUserLeaveHint)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !activity.isFinishing) {
                    activity.setPictureInPictureParams(
                        PictureInPictureParams.Builder().setAutoEnterEnabled(false).build(),
                    )
                }
            } else {
                refresh()
            }
        }

        fun refresh(registration: Registration? = null, becameActive: Boolean = false) {
            if (registration != null && registrations.contains(registration)) {
                if (becameActive) registration.priority = ++nextPriority
            }
            val active = selectActive()
            val key = active?.let(::paramsKey) ?: ParamsKey(null, null, 0, 0, false)
            if (key == lastParams) return
            lastParams = key
            if (!activity.isFinishing) {
                activity.setPictureInPictureParams(
                    active?.let { buildParams(it, autoEnter = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) }
                        ?: PictureInPictureParams.Builder().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                setAutoEnterEnabled(false)
                            }
                        }.build(),
                )
            }
        }

        private fun selectActive(): Registration? = registrations
            .asSequence()
            .filter { it.player.isPlaying && it.view.isShown && it.view.isAttachedToWindow && it.view.width > 0 && it.view.height > 0 }
            .maxByOrNull { it.priority }

        private fun paramsKey(registration: Registration): ParamsKey {
            val bounds = Rect()
            val visibleBounds = registration.view.getGlobalVisibleRect(bounds)
            val rect = bounds.takeIf { visibleBounds && !it.isEmpty }
            val ratio = aspectRatio(registration.view.width, registration.view.height)
            return ParamsKey(registration.view, rect, ratio.numerator, ratio.denominator, true)
        }

        private fun buildParams(registration: Registration, autoEnter: Boolean): PictureInPictureParams {
            val key = paramsKey(registration)
            return PictureInPictureParams.Builder().apply {
                key.sourceRect?.let(::setSourceRectHint)
                if (key.aspectNumerator > 0 && key.aspectDenominator > 0) {
                    setAspectRatio(Rational(key.aspectNumerator, key.aspectDenominator))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(autoEnter)
                }
            }.build()
        }

        private data class ParamsKey(
            val view: PlayerView?,
            val sourceRect: Rect?,
            val aspectNumerator: Int,
            val aspectDenominator: Int,
            val active: Boolean,
        )
    }

    private fun aspectRatio(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toDouble() / height.toDouble()
        return when {
            ratio > 2.39 -> Rational(239, 100)
            ratio < 1.0 / 2.39 -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }
}
