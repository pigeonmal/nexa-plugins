package dev.nexa.youtube

enum class VideoQuality(val heightPixels: Int) {
    QUALITY_4K(2160),
    QUALITY_1440P(1440),
    QUALITY_1080P(1080),
    QUALITY_720P(720),
    QUALITY_480P(480),
    QUALITY_360P(360),
    QUALITY_240P(240),
    QUALITY_144P(144),
    AUTO(-1);

    companion object {
        fun fromHeight(height: Int?): VideoQuality? = height?.let { value ->
            if (value == AUTO.heightPixels) AUTO else values().find { it.heightPixels == value }
        }
    }
}
