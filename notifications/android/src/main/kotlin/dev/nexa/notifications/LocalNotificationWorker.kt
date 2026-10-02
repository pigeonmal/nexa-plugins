package dev.nexa.notifications

import android.content.Context
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
            val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            val id = notificationId(identifier)
            notificationPreferences(applicationContext).edit().putInt(identifier, id).apply()
            NotificationManagerCompat.from(applicationContext).notify(id, notification)
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        }
    }
}
