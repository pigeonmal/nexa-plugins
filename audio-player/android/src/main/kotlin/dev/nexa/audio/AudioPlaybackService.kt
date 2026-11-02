package dev.nexa.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.nexa.core.NexaNetwork

/** Owns the single app-wide player so Media3 can keep playback alive in background. */
@UnstableApi
public class AudioPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var pausedByDeviceMute = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            evaluateDeviceMute()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val httpFactory = DynamicHttpDataSourceFactory(applicationContext)
        val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(applicationContext).setDataSourceFactory(dataSourceFactory)
        val exoPlayer = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        exoPlayer.skipSilenceEnabled = AudioPlaybackConfiguration.skipSilence
        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                refreshAudioNormalization()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshAudioNormalization()
            }
        })
        player = exoPlayer
        val sessionPlayer = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun seekToNext() {
                exoPlayer.pause()
                AudioPlaybackEvents.requestSkipToNext()
            }

            override fun seekToNextMediaItem() {
                exoPlayer.pause()
                AudioPlaybackEvents.requestSkipToNext()
            }

            override fun seekToPrevious() {
                exoPlayer.pause()
                AudioPlaybackEvents.requestSkipToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                exoPlayer.pause()
                AudioPlaybackEvents.requestSkipToPrevious()
            }
        }
        mediaSession = MediaSession.Builder(this, sessionPlayer).build()
        AudioPlaybackServiceInstance.current = this
        val volumeFilter = IntentFilter(AUDIO_VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, volumeFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(volumeReceiver, volumeFilter)
        }
        evaluateDeviceMute()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    internal fun evaluateDeviceMute() {
        val activePlayer = player ?: return
        if (!AudioPlaybackConfiguration.pauseOnMute) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val deviceMuted = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        if (deviceMuted && activePlayer.isPlaying) {
            pausedByDeviceMute = true
            activePlayer.pause()
        } else if (!deviceMuted && pausedByDeviceMute) {
            pausedByDeviceMute = false
            activePlayer.play()
        }
    }

    private fun refreshAudioNormalization() {
        val activePlayer = player ?: return
        val sessionId = activePlayer.audioSessionId
        val metadata = activePlayer.currentMediaItem?.localConfiguration?.tag as? AudioPlaybackMetadata
        val loudness = metadata?.loudnessDb
        if (!AudioPlaybackConfiguration.audioNormalization || sessionId <= 0 || loudness == null || !loudness.isFinite()) {
            runCatching { loudnessEnhancer?.enabled = false }
            return
        }

        try {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(sessionId)
            val gainMillibels = (-loudness * 100.0).toInt().coerceIn(-1_500, 300)
            loudnessEnhancer?.setTargetGain(gainMillibels)
            loudnessEnhancer?.enabled = true
        } catch (_: Exception) {
            runCatching { loudnessEnhancer?.release() }
            loudnessEnhancer = null
        }
    }

    internal fun updateAudioNormalization(enabled: Boolean) {
        if (!enabled) {
            runCatching { loudnessEnhancer?.enabled = false }
        } else {
            refreshAudioNormalization()
        }
    }

    internal fun updateSkipSilence(enabled: Boolean) {
        player?.skipSilenceEnabled = enabled
    }

    internal fun openSystemEqualizer(activity: android.app.Activity): Boolean {
        val activePlayer = player ?: return false
        val audioSessionId = activePlayer.audioSessionId
        if (audioSessionId <= 0) return false

        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(volumeReceiver) }
        AudioPlaybackServiceInstance.current = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        super.onDestroy()
    }

    private companion object {
        const val AUDIO_VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}

internal object AudioPlaybackServiceInstance {
    @Volatile
    var current: AudioPlaybackService? = null

    fun updateAudioNormalization(enabled: Boolean) = current?.updateAudioNormalization(enabled)

    fun updateSkipSilence(enabled: Boolean) = current?.updateSkipSilence(enabled)

    fun openSystemEqualizer(activity: android.app.Activity): Boolean =
        current?.openSystemEqualizer(activity) ?: false

    fun evaluateDeviceMute() = current?.evaluateDeviceMute()
}

internal object AudioPlaybackConfiguration {
    @Volatile var skipSilence = false
    @Volatile var audioNormalization = true
    @Volatile var pauseOnMute = true
}

internal object AudioPlaybackVolume {
    @Volatile var volume = 1f
    @Volatile var muted = false

    val outputVolume: Float get() = if (muted) 0f else volume
}

internal data class AudioPlaybackMetadata(
    val loudnessDb: Double?,
)

/** Supplies per-stream YouTube headers without rebuilding the app-wide player. */
internal object AudioRequestHeaders {
    data class Request(val headers: Map<String, String>)

    @Volatile
    private var current = Request(emptyMap())

    fun update(headers: Map<String, String>) {
        current = Request(headers.toMap())
    }

    fun snapshot(): Request = current
}

@UnstableApi
private class DynamicHttpDataSourceFactory(
    private val context: Context,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val request = AudioRequestHeaders.snapshot()
        val userAgent = request.headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: "Nexa AudioPlayer"
        return CronetDataSource.Factory(
            NexaNetwork.cronetEngine(context),
            NexaNetwork.cronetExecutor(),
        )
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(request.headers)
            .createDataSource()
    }
}
