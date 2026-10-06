package dev.nexa.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dev.nexa.core.NexaRuntimeCore

/** Presents URLs with the platform browser without retaining the host Activity. */
public class SystemBrowserImpl : SystemBrowserSpec {
    override suspend fun open(url: String, inApp: Boolean) {
        val uri = Uri.parse(url)
        if (!uri.isAbsolute || uri.scheme.isNullOrBlank()) {
            throw BrowserError.invalidURL
        }

        val activity = NexaRuntimeCore.currentActivity()
            ?: throw BrowserError.presentationUnavailable

        try {
            if (inApp) {
                if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
                    throw BrowserError.invalidURL
                }
                CustomTabsIntent.Builder().build().launchUrl(activity, uri)
            } else {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(activity.packageManager) == null) {
                    throw BrowserError.unavailable
                }
                activity.startActivity(intent)
            }
        } catch (_: ActivityNotFoundException) {
            throw BrowserError.unavailable
        }
    }
}
