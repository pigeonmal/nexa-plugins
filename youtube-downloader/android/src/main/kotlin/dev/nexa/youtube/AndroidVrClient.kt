package dev.nexa.youtube

object AndroidVrClient {
    private const val ANDROID_VR_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"

    val ANDROID_VR_1_65_10 = YouTubeClient(
        clientName = "ANDROID_VR",
        clientVersion = "1.65.10",
        clientId = "28",
        userAgent = ANDROID_VR_USER_AGENT,
        osName = "Android",
        osVersion = "12L",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = "32",
        friendlyName = "Android VR 1.65.10",
        loginSupported = false,
        useSignatureTimestamp = false,
        includeUserAgentInContext = true,
        useMusicPlayerEndpoint = true,
    )

    val ANDROID_VR_1_61_48 = YouTubeClient(
        clientName = "ANDROID_VR",
        clientVersion = "1.61.48",
        clientId = "28",
        userAgent =
            "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; " +
                "Cronet/132.0.6808.3)",
        osName = "Android",
        osVersion = "12",
        deviceMake = "Oculus",
        deviceModel = "Quest 3",
        androidSdkVersion = "32",
        friendlyName = "Android VR 1.61.48",
        loginSupported = false,
        useSignatureTimestamp = false,
        includeUserAgentInContext = true,
        useMusicPlayerEndpoint = true,
    )

    val ANDROID_VR_1_43_32 = YouTubeClient(
        clientName = "ANDROID_VR",
        clientVersion = "1.43.32",
        clientId = "28",
        userAgent =
            "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 10; en_US; Oculus Quest 2; Build/QP1A.190711.020; Cronet/107.0.5284.2)",
        osName = "Android",
        osVersion = "10",
        deviceMake = "Oculus",
        deviceModel = "Quest 2",
        androidSdkVersion = "29",
        friendlyName = "Android VR 1.43.32",
        loginSupported = false,
        useSignatureTimestamp = false,
        includeUserAgentInContext = true,
        useMusicPlayerEndpoint = true,
    )
}
