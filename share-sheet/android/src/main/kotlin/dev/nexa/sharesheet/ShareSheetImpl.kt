package dev.nexa.sharesheet

import android.content.Intent
import dev.nexa.core.NexaRuntimeCore

/** Opens the native Android Sharesheet without retaining the host Activity. */
public class ShareSheetImpl : ShareSheetSpec {
    override suspend fun shareText(text: String, subject: String?) {
        present(text, subject)
    }

    override suspend fun shareLink(url: String, title: String?) {
        if (url.isBlank()) throw ShareSheetError.presentationUnavailable
        present(url, title)
    }

    private fun present(text: String, subject: String?) {
        val activity = NexaRuntimeCore.currentActivity()
            ?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: throw ShareSheetError.presentationUnavailable
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        activity.startActivity(Intent.createChooser(intent, subject))
    }
}
