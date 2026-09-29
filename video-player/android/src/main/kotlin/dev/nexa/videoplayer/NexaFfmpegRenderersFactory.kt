package dev.nexa.videoplayer

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory

/** Enables Media3's bundled FFmpeg extension renderers when software fallback is enabled. */
internal class NexaFfmpegRenderersFactory(
    context: Context,
    softwareDecodingEnabled: Boolean,
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

}
