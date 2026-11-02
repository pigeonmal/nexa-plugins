package dev.nexa.youtube

object ClientCatalog {
    /**
     * Unauthenticated clients prioritised for speed and reliability.
     * 1. visionOS (ultra-fast ~80ms, no PoToken needed)
     * 2. Android VR (fast ~110ms, no PoToken needed)
     * 3. TV Simply (PoToken)
     * 4. Mobile Web (PoToken)
     * 5. Desktop Web (PoToken)
     * 6. iOS (HLS fallback)
     * 7. TV Downgraded
     */
    val anonymousClients: List<YouTubeClient> = listOf(
        VisionOsClient.VISIONOS,
        VisionOsClient.VISIONOS_0_1,
        AndroidVrClient.ANDROID_VR_1_65_10,
        AndroidVrClient.ANDROID_VR_1_61_48,
        AndroidVrClient.ANDROID_VR_1_43_32,
        AndroidClient.ANDROID,
        IosClient.IOS,
        IosClient.IPADOS,
        TvSimplyClient.TVHTML5_SIMPLY,
        TvSimplyClient.TVHTML5_EMBEDDED,
        WebClient.WEB,
        MWebClient.MWEB,
    )

    /**
     * Authenticated clients verified to succeed when a cookie is available or requested.
     * Ordered: Web Remix (Music Auth) -> TV Embedded -> TV Simply -> Android -> Mobile Web -> Desktop Web.
     */
    val authenticatedClients: List<YouTubeClient> = listOf(
        WebRemixClient.WEB_REMIX,
        TvSimplyClient.TVHTML5_EMBEDDED,
        TvSimplyClient.TVHTML5_SIMPLY,
        AndroidClient.ANDROID,
        MWebClient.MWEB,
        WebClient.WEB,
    )

    fun getClients(cookie: String?, authenticatedOnly: Boolean, excludedClientNames: Set<String>): List<YouTubeClient> {
        val list = if (authenticatedOnly) {
            authenticatedClients
        } else {
            // Anonymous first priority; if cookie is present, append authenticated clients as fallback
            anonymousClients + if (!cookie.isNullOrBlank()) authenticatedClients else emptyList()
        }
        return list.filter { it.clientName !in excludedClientNames }
    }
}
