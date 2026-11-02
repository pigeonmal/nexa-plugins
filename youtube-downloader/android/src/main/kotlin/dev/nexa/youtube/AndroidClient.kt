package dev.nexa.youtube

object AndroidClient {
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"

    val ANDROID = YouTubeClient(
        clientName = "ANDROID",
        clientVersion = "21.26.364",
        clientId = "3",
        userAgent = ANDROID_USER_AGENT,
        osName = "Android",
        osVersion = "11",
        androidSdkVersion = "30",
        friendlyName = "Android 21.26.364",
        loginSupported = false,
        useSignatureTimestamp = false,
        recommendPoToken = true,
        includeUserAgentInContext = true,
    )
}
