package dev.nexa.youtube

object VisionOsClient {
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    val VISIONOS = YouTubeClient(
        clientName = "VISIONOS",
        clientVersion = "1.02",
        clientId = "101",
        userAgent = USER_AGENT,
        osName = "visionOS",
        osVersion = "26.5.23O471",
        deviceMake = "Apple",
        deviceModel = "RealityDevice17,1",
        friendlyName = "visionOS",
        loginSupported = false,
        useSignatureTimestamp = false,
        useMusicPlayerEndpoint = true,
    )

    val VISIONOS_0_1 = YouTubeClient(
        clientName = "VISIONOS",
        clientVersion = "0.1",
        clientId = "101",
        userAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        osName = "VISION_OS",
        osVersion = "1.3",
        deviceMake = "Apple",
        deviceModel = "RealityDevice14,1",
        platform = "MOBILE",
        friendlyName = "visionOS 0.1",
        loginSupported = false,
        useSignatureTimestamp = false,
        useMusicPlayerEndpoint = true,
        skipPlayerResponseValidation = true,
    )
}
