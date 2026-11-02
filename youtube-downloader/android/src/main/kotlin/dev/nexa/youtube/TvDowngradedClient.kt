package dev.nexa.youtube

object TvDowngradedClient {
    // Real Samsung Smart TV Tizen Cobalt User Agent
    private const val TV_DOWNGRADED_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; LINUX; Tizen 7.0) Cobalt/25.lts.30.1034943-gold " +
            "(unlike Gecko), PONTUSM_TV_PONTUSM_2024/1120.6 (Samsung, QA65LS03DAWXXY, Wired)"

    val TVHTML5_DOWNGRADED = YouTubeClient(
        clientName = "TVHTML5",
        clientVersion = "5.20260707",
        clientId = "7",
        userAgent = TV_DOWNGRADED_USER_AGENT,
        platform = "TV",
        deviceMake = "Samsung",
        deviceModel = "QA65LS03DAWXXY",
        friendlyName = "TV HTML5 Downgraded",
        loginSupported = true,
        useSignatureTimestamp = true,
        useWebPoTokens = true,
        requirePoToken = true,
        includeUserAgentInContext = true,
    )
}
