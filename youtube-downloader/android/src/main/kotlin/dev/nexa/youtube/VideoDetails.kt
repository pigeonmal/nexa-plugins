package dev.nexa.youtube

data class VideoDetails(
    val videoId: String,
    val title: String?,
    val author: String?,
    val channelId: String,
    val lengthSeconds: String,
    val musicVideoType: String?,
    val viewCount: String?,
)
