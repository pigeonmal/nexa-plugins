package dev.nexa.youtube

import java.util.Locale

internal object StreamSelector {
    fun selectAudio(candidates: List<StreamCandidate>, quality: AudioQuality): StreamCandidate? {
        val audioOnly = candidates.filter { it.isAudio && !it.isVideo }
        val pool = if (audioOnly.isNotEmpty()) audioOnly else candidates.filter { it.isAudio }
        if (pool.isEmpty()) return null

        return when (quality) {
            AudioQuality.LOW -> pool.minByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
            AudioQuality.HIGH -> pool.maxByOrNull { audioScore(it) }
            AudioQuality.AUTO -> pool.maxByOrNull { audioScore(it) }
        }
    }

    fun selectVideo(
        candidates: List<StreamCandidate>,
        quality: VideoQuality,
        isMetered: Boolean,
    ): StreamCandidate? {
        val videoPool = candidates.filter { it.isVideo && (it.height ?: 0) > 0 }
        if (videoPool.isEmpty()) return null

        val targetHeight = quality.heightPixels.takeIf { it > 0 } ?: if (isMetered) 720 else 1080
        val bounded = videoPool.filter { (it.height ?: 0) <= targetHeight }
        val candidatesPool = if (bounded.isNotEmpty()) bounded else videoPool

        return candidatesPool.maxWithOrNull(
            compareBy<StreamCandidate> { it.height ?: 0 }
                .thenBy { if (it.mimeType.contains("mp4", ignoreCase = true)) 1 else 0 }
                .thenBy { it.bitrate }
        )
    }

    private fun audioScore(candidate: StreamCandidate): Long {
        val bitrate = candidate.averageBitrate.takeIf { it > 0 }?.toLong() ?: candidate.bitrate.toLong()
        val formatBonus = when {
            candidate.mimeType.contains("webm", ignoreCase = true) ||
                candidate.codecs?.lowercase(Locale.ROOT)?.contains("opus") == true -> 25_000L
            candidate.mimeType.contains("mp4", ignoreCase = true) ||
                candidate.codecs?.lowercase(Locale.ROOT)?.contains("mp4a") == true -> 10_000L
            else -> 0L
        }
        val sampleRateBonus = ((candidate.audioSampleRate ?: 44100) / 1000).toLong() * 100L
        return bitrate + formatBonus + sampleRateBonus
    }
}
