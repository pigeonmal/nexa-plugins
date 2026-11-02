package dev.nexa.youtube

object TvSimplyClient {
    // Real Google Chromecast with Google TV Cobalt User Agent
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (Linux armv7l; Android 12) Cobalt/25.lts.30.1034943-gold " +
            "(unlike Gecko) v8/8.8.278.8-bstar gles Starboard/15, " +
            "sabrina_TV_cord_release/STTE.231215.005 (Google, Chromecast HD, Wireless)"

    val TVHTML5_SIMPLY = YouTubeClient(
        clientName = "TVHTML5_SIMPLY",
        clientVersion = "1.0",
        clientId = "75",
        userAgent = TV_USER_AGENT,
        platform = "TV",
        deviceMake = "Google",
        deviceModel = "Chromecast HD",
        osName = "Android",
        osVersion = "12",
        friendlyName = "TV HTML5 Simply",
        loginSupported = false,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        requirePoToken = false,
        recommendPoToken = true,
        poTokenBinding = dev.nexa.youtube.PoTokenBinding.VISITOR_DATA,
        useMusicPlayerEndpoint = false,
        includeUserAgentInContext = true,
    )

    val TVHTML5_EMBEDDED = YouTubeClient(
        clientName = "TVHTML5_SIMPLY",
        clientVersion = "1.0",
        clientId = "75",
        userAgent = TV_USER_AGENT,
        platform = "TV",
        deviceMake = "Google",
        deviceModel = "Chromecast HD",
        osName = "Android",
        osVersion = "12",
        friendlyName = "TV HTML5 Embedded",
        loginSupported = false,
        useSignatureTimestamp = true,
        isEmbedded = true,
        useWebPoTokens = true,
        requirePoToken = false,
        recommendPoToken = true,
        poTokenBinding = dev.nexa.youtube.PoTokenBinding.VISITOR_DATA,
        useMusicPlayerEndpoint = false,
        includeUserAgentInContext = true,
    )
}
