package dev.nexa.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
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
                activity.startActivity(intent)
            }
        } catch (_: ActivityNotFoundException) {
            throw BrowserError.unavailable
        }
    }

    override suspend fun openInApp(url: String, toolbarColor: String?) {
        val uri = Uri.parse(url)
        if ((uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank() ||
            uri.userInfo != null
        ) {
            throw BrowserError.invalidURL
        }

        val color = toolbarColor?.let(::parseHexColor)
        if (toolbarColor != null && color == null) throw BrowserError.invalidURL
        val activity = NexaRuntimeCore.currentActivity()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: throw BrowserError.presentationUnavailable

        try {
            val builder = CustomTabsIntent.Builder()
            if (color != null) builder.setToolbarColor(color)
            builder.build().launchUrl(activity, uri)
        } catch (_: ActivityNotFoundException) {
            throw BrowserError.unavailable
        }
    }

    private fun parseHexColor(value: String): Int? {
        val digits = value.removePrefix("#")
        if (digits.length != 6) return null
        val rgb = digits.toLongOrNull(16) ?: return null
        return Color.rgb(
            ((rgb shr 16) and 0xff).toInt(),
            ((rgb shr 8) and 0xff).toInt(),
            (rgb and 0xff).toInt(),
        )
    }
}
