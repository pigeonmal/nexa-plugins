package dev.nexa.youtube

object WebClient {
    private const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val WEB = YouTubeClient(
        clientName = "WEB",
        clientVersion = "2.20260708.00.00",
        clientId = "1",
        userAgent = USER_AGENT_WEB,
        friendlyName = "Web",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        recommendPoToken = true,
    )

    val WEB_EMBEDDED_PLAYER = YouTubeClient(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientVersion = "2.20260708.00.00",
        clientId = "56",
        userAgent = USER_AGENT_WEB,
        friendlyName = "Web Embedded",
        loginSupported = true,
        useSignatureTimestamp = true,
        isEmbedded = true,
        useWebPoTokens = true,
        recommendPoToken = true,
    )
}
