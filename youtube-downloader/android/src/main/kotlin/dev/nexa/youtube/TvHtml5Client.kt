package dev.nexa.youtube

object TvHtml5Client {
    // Real Sony Bravia 4K Android TV Cobalt User Agent
    private const val TV_USER_AGENT =
        "Mozilla/5.0 (Linux arm64-v8a; Android 12) Cobalt/25.lts.30.1034943-gold " +
            "(unlike Gecko) v8/8.8.278.8-bstar gles Starboard/15, " +
            "Sony_ATV3_EU/UR2_4K (Sony, BRAVIA 4K UR2, Wired)"

    val TVHTML5 = YouTubeClient(
        clientName = "TVHTML5",
        clientVersion = "7.20260707.07.00",
        clientId = "7",
        userAgent = TV_USER_AGENT,
        platform = "TV",
        deviceMake = "Sony",
        deviceModel = "BRAVIA 4K UR2",
        osName = "Android",
        osVersion = "12",
        friendlyName = "TV HTML5",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        requirePoToken = true,
        includeUserAgentInContext = true,
    )
}
