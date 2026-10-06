package dev.nexa.notifications

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Posts a scheduled local notification after its persistent work delay. */
public class LocalNotificationWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val identifier = inputData.getString(KEY_IDENTIFIER) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()

        return try {
            ensureNotificationsEnabled(applicationContext)
            ensureNotificationChannel(applicationContext)
            val launchIntent = applicationContext.packageManager
                .getLaunchIntentForPackage(applicationContext.packageName)
                ?.apply {
                    action = ACTION_LOCAL_NOTIFICATION_OPENED
                    data = Uri.Builder()
                        .scheme("nexa-notification")
                        .authority("local")
                        .appendPath(identifier)
                        .build()
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(KEY_LOCAL_OPENED, true)
                    putExtra(KEY_IDENTIFIER, identifier)
                    putExtra(KEY_TITLE, title)
                    putExtra(KEY_BODY, body)
                }
            val contentIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    notificationId(identifier),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
            val badgeCount = applicationContext
                .getSharedPreferences(BADGE_PREFERENCES, Context.MODE_PRIVATE)
                .getInt(BADGE_COUNT_KEY, 0)
            if (badgeCount > 0) builder.setNumber(badgeCount)
            if (contentIntent != null) builder.setContentIntent(contentIntent)
            val notification = builder.build()
            val id = notificationId(identifier)
            notificationPreferences(applicationContext).edit().putInt(identifier, id).apply()
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        }
    }
}
