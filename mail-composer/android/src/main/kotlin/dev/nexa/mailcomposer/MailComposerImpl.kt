package dev.nexa.mailcomposer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.nexa.core.NexaRuntimeCore

/** Opens an installed email application without retaining the host Activity. */
public class MailComposerImpl : MailComposerSpec {
    override var onCompleted: ((MailComposerResult) -> Unit)? = null
    // Android 11+ package visibility can hide installed email clients from
    // resolveActivity without a package-visibility query. The chooser itself
    // reports `unavailable` if there is no handler.
    override val available: Boolean
        get() = NexaRuntimeCore.currentActivity() != null
    override val deviceInfo: String
        get() = "${Build.MODEL} / Android ${Build.VERSION.RELEASE}"

    override suspend fun present(to: List<String>, subject: String, body: String) {
        val activity = NexaRuntimeCore.currentActivity()
            ?: throw MailComposerError.presentationUnavailable

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)

        try {
            activity.startActivity(Intent.createChooser(intent, null))
        } catch (_: ActivityNotFoundException) {
            throw MailComposerError.unavailable
        }
    }
}
