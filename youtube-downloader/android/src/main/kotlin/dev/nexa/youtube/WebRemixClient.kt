package dev.nexa.youtube

object WebRemixClient {
    private const val USER_AGENT_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val WEB_REMIX = YouTubeClient(
        clientName = "WEB_REMIX",
        clientVersion = "1.20260707.12.00",
        clientId = "67",
        userAgent = USER_AGENT_WEB,
        friendlyName = "Web Remix",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        recommendPoToken = true,
        poTokenBinding = dev.nexa.youtube.PoTokenBinding.VISITOR_DATA,
        useMusicPlayerEndpoint = false,
    )
}
