package dev.nexa.youtube

object MWebClient {
    private const val MWEB_USER_AGENT =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"

    val MWEB = YouTubeClient(
        clientName = "MWEB",
        clientVersion = "2.20260708.05.00",
        clientId = "2",
        userAgent = MWEB_USER_AGENT,
        friendlyName = "Mobile Web",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        recommendPoToken = true,
        includeUserAgentInContext = true,
    )
}
