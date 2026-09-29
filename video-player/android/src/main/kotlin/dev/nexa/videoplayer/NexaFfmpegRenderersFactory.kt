package dev.nexa.videoplayer

import android.content.Context
import android.os.Handler
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/** Registers FFmpeg video and audio fallback renderers when software decoding is enabled. */
internal class NexaFfmpegRenderersFactory(
    context: Context,
    private val softwareDecodingEnabled: Boolean,
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(
            if (softwareDecodingEnabled) {
                EXTENSION_RENDERER_MODE_ON
            } else {
                EXTENSION_RENDERER_MODE_OFF
            },
        )
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )

        // Media3 discovers its audio FFmpeg extension automatically, but has no built-in
        // video FFmpeg extension. Append ours after MediaCodec renderers so hardware decoding
        // stays preferred and FFmpeg is selected only when no platform renderer can handle it.
        if (softwareDecodingEnabled) {
            out.add(
                ExperimentalFfmpegVideoRenderer(
                    allowedVideoJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                ),
            )
        }
    }
}
