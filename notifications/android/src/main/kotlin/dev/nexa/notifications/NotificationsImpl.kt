package dev.nexa.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.nexa.core.NexaRuntimeCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Schedules app-owned local notifications using persistent WorkManager jobs. */
public class NotificationsImpl : NotificationsSpec {
    private val context: Context
        get() = NexaRuntimeCore.context().applicationContext

    init {
        NotificationsRemoteHub.add(this)
    }

    override var onRemoteNotificationReceived: ((RemoteNotification) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) NotificationsRemoteHub.deliverPending(this)
        }

    override var onRemoteNotificationOpened: ((RemoteNotification) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) NotificationsRemoteHub.deliverPending(this)
        }

    override var onRemoteTokenChanged: ((String) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) NotificationsRemoteHub.deliverPending(this)
        }

    override suspend fun scheduleLocal(
        identifier: String,
        title: String,
        body: String,
        delaySeconds: Long,
    ) {
        if (identifier.isEmpty()) throw NotificationError.invalidIdentifier
        if (delaySeconds < 0) throw NotificationError.invalidDelay
        enqueueLocal(identifier, title, body, delaySeconds)
    }

    override var onLocalNotificationOpened: ((LocalNotification) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) NotificationsRemoteHub.deliverPending(this)
        }

    override suspend fun scheduleLocalAt(
        identifier: String,
        title: String,
        body: String,
        timestampSeconds: Long,
    ) {
        if (identifier.isEmpty()) throw NotificationError.invalidIdentifier
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (timestampSeconds <= nowSeconds || timestampSeconds > Long.MAX_VALUE / 1_000L) {
            throw NotificationError.invalidDelay
        }
        enqueueLocal(identifier, title, body, timestampSeconds - nowSeconds)
    }

    private fun enqueueLocal(
        identifier: String,
        title: String,
        body: String,
        delaySeconds: Long,
    ) {
        ensureNotificationsEnabled(context)
        ensureNotificationChannel(context)

        val request = OneTimeWorkRequestBuilder<LocalNotificationWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(KEY_IDENTIFIER, identifier)
                    .putString(KEY_TITLE, title)
                    .putString(KEY_BODY, body)
                    .build(),
            )
            .addTag(LOCAL_NOTIFICATION_WORK_TAG)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(identifier),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (_: IllegalStateException) {
            throw NotificationError.schedulerUnavailable
        }
    }

    override suspend fun isLocalPending(identifier: String): Boolean {
        if (identifier.isEmpty()) throw NotificationError.invalidIdentifier
        return try {
            val future = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName(identifier))
            suspendCancellableCoroutine { continuation ->
                future.addListener(
                    {
                        if (!continuation.isActive) return@addListener
                        try {
                            val infos = future.get()
                            val isPending = infos.any { work ->
                                work.state == WorkInfo.State.ENQUEUED ||
                                    work.state == WorkInfo.State.BLOCKED ||
                                    work.state == WorkInfo.State.RUNNING
                            }
                            if (continuation.isActive) continuation.resume(isPending)
                        } catch (cancellation: CancellationException) {
                            if (continuation.isActive) continuation.cancel(cancellation)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    },
                    Executor { command -> command.run() },
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw NotificationError.schedulerUnavailable
        }
    }

    override fun cancelLocal(identifier: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(identifier))
        NotificationManagerCompat.from(context).cancel(notificationId(identifier))
        notificationPreferences(context).edit().remove(identifier).apply()
    }

    override fun cancelAllLocal() {
        WorkManager.getInstance(context).cancelAllWorkByTag(LOCAL_NOTIFICATION_WORK_TAG)
        val preferences = notificationPreferences(context)
        preferences.all.values
            .filterIsInstance<Int>()
            .forEach { NotificationManagerCompat.from(context).cancel(it) }
        preferences.edit().clear().apply()
    }

    override suspend fun setBadgeCount(count: Int) {
        withContext(Dispatchers.IO) {
            val badgeCount = count.coerceAtLeast(0)
            context.getSharedPreferences(BADGE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putInt(BADGE_COUNT_KEY, badgeCount)
                .apply()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    for (active in manager.activeNotifications) {
                        val notification = active.notification
                        val extras = notification.extras
                        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                            ?: context.applicationInfo.loadLabel(context.packageManager)
                        val body = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
                        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            notification.channelId
                        } else {
                            NOTIFICATION_CHANNEL_ID
                        }
                        val updated = NotificationCompat.Builder(context, channelId ?: NOTIFICATION_CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setContentIntent(notification.contentIntent)
                            .setNumber(badgeCount)
                            .setCategory(NotificationCompat.CATEGORY_REMINDER)
                            .setOnlyAlertOnce(true)
                            .setAutoCancel(true)
                            .build()
                        NotificationManagerCompat.from(context).notify(active.id, updated)
                    }
                } catch (_: SecurityException) {
                    // Permission may be revoked after the app requested a badge update.
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun registerRemote(): String {
        val apiKey = NexaPluginConfig.Notifications.fcmApiKey
        val applicationId = NexaPluginConfig.Notifications.fcmApplicationId
        val projectId = NexaPluginConfig.Notifications.fcmProjectId
        val senderId = NexaPluginConfig.Notifications.fcmSenderId
        if (apiKey.isBlank() || applicationId.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            throw NotificationError.firebaseNotConfigured
        }

        try {
            NotificationsFirebaseApp.getOrCreate(context, apiKey, applicationId, projectId, senderId)
        } catch (_: IllegalArgumentException) {
            throw NotificationError.firebaseNotConfigured
        } catch (_: IllegalStateException) {
            throw NotificationError.remoteRegistrationUnavailable
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener(Executor { it.run() }) { task ->
                    if (!continuation.isActive) return@addOnCompleteListener
                    if (task.isSuccessful) {
                        val token = task.result
                        if (!token.isNullOrBlank()) {
                            NotificationsRemoteHub.tokenChanged(token)
                            continuation.resume(token)
                        } else {
                            continuation.resumeWithException(IllegalStateException("FCM token unavailable"))
                        }
                    } else {
                        continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token unavailable"))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw NotificationError.remoteRegistrationUnavailable
        }
    }
}

private object NotificationsFirebaseApp {
    @Synchronized
    fun getOrCreate(
        context: Context,
        apiKey: String,
        applicationId: String,
        projectId: String,
        senderId: String,
    ): FirebaseApp {
        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(applicationId)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build()
        FirebaseApp.getApps(context)
            .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?.let { app ->
                val configured = app.options
                if (configured.applicationId != applicationId ||
                    configured.apiKey != apiKey ||
                    configured.projectId != projectId ||
                    configured.gcmSenderId != senderId
                ) {
                    throw IllegalArgumentException("Firebase default app options do not match Nexa plugin config")
                }
                return app
            }
        return FirebaseApp.initializeApp(context, options)
    }
}

/** Queues events until the Nexa app has installed its event callbacks. */
public object NotificationsRemoteHub {
    private const val MAX_PENDING_EVENTS = 20
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<WeakReference<NotificationsImpl>>()
    private val pendingReceived = ArrayDeque<RemoteNotification>()
    private val pendingOpened = ArrayDeque<RemoteNotification>()
    private val pendingTokens = ArrayDeque<String>()
    private val pendingLocalOpens = ArrayDeque<LocalNotification>()

    fun add(owner: NotificationsImpl) {
        mainHandler.post {
            observers.removeAll { it.get() == null || it.get() === owner }
            observers.add(WeakReference(owner))
            deliverPending(owner)
        }
    }

    fun received(notification: RemoteNotification) = mainHandler.post {
        val interested = observers.mapNotNull { it.get() }
            .filter { it.onRemoteNotificationReceived != null }
        if (interested.isEmpty()) {
            enqueue(pendingReceived, notification)
        } else {
            interested.forEach { it.onRemoteNotificationReceived?.invoke(notification) }
        }
    }

    fun opened(notification: RemoteNotification) = mainHandler.post {
        val interested = observers.mapNotNull { it.get() }
            .filter { it.onRemoteNotificationOpened != null }
        if (interested.isEmpty()) {
            enqueue(pendingOpened, notification)
        } else {
            interested.forEach { it.onRemoteNotificationOpened?.invoke(notification) }
        }
    }

    fun tokenChanged(token: String) = mainHandler.post {
        val interested = observers.mapNotNull { it.get() }
            .filter { it.onRemoteTokenChanged != null }
        if (interested.isEmpty()) {
            enqueue(pendingTokens, token)
        } else {
            interested.forEach { it.onRemoteTokenChanged?.invoke(token) }
        }
    }

    fun dispatchOpened(intent: android.content.Intent?) {
        val extras = intent?.extras ?: return
        val identifier = extras.getString("google.message_id")
            ?: extras.getString("gcm.message_id")
            ?: ""
        val title = extras.getString("gcm.n.title") ?: extras.getString("title").orEmpty()
        val body = extras.getString("gcm.n.body") ?: extras.getString("body").orEmpty()
        val data = LinkedHashMap<String, String>()
        for (key in extras.keySet()) {
            if (key.startsWith("google.") || key.startsWith("gcm.") || key == "from" || key == "collapse_key") continue
            val value = extras.getString(key) ?: continue
            data[key] = value
        }
        if (identifier.isEmpty() && extras.getString("gcm.n.e") != "1" && data.isEmpty()) return
        opened(RemoteNotification(identifier, title, body, data))
    }

    fun dispatchLocalOpened(intent: android.content.Intent?) {
        if (intent?.action != ACTION_LOCAL_NOTIFICATION_OPENED ||
            !intent.getBooleanExtra(KEY_LOCAL_OPENED, false)
        ) return
        val identifier = intent.getStringExtra(KEY_IDENTIFIER) ?: return
        val notification = LocalNotification(
            identifier = identifier,
            title = intent.getStringExtra(KEY_TITLE).orEmpty(),
            body = intent.getStringExtra(KEY_BODY).orEmpty(),
        )
        mainHandler.post {
            val interested = observers.mapNotNull { it.get() }
                .filter { it.onLocalNotificationOpened != null }
            if (interested.isEmpty()) {
                enqueue(pendingLocalOpens, notification)
            } else {
                interested.forEach { it.onLocalNotificationOpened?.invoke(notification) }
            }
        }
    }

    fun deliverPending(owner: NotificationsImpl) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { deliverPending(owner) }
            return
        }
        if (owner.onRemoteNotificationReceived != null) {
            while (pendingReceived.isNotEmpty()) {
                owner.onRemoteNotificationReceived?.invoke(pendingReceived.removeFirst())
            }
        }
        if (owner.onRemoteNotificationOpened != null) {
            while (pendingOpened.isNotEmpty()) {
                owner.onRemoteNotificationOpened?.invoke(pendingOpened.removeFirst())
            }
        }
        if (owner.onRemoteTokenChanged != null) {
            while (pendingTokens.isNotEmpty()) {
                owner.onRemoteTokenChanged?.invoke(pendingTokens.removeFirst())
            }
        }
        if (owner.onLocalNotificationOpened != null) {
            while (pendingLocalOpens.isNotEmpty()) {
                owner.onLocalNotificationOpened?.invoke(pendingLocalOpens.removeFirst())
            }
        }
    }

    private fun <T> enqueue(queue: ArrayDeque<T>, item: T) {
        if (queue.size == MAX_PENDING_EVENTS) queue.removeFirst()
        queue.addLast(item)
    }
}

internal fun ensureNotificationsEnabled(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        throw NotificationError.permissionDenied
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        throw NotificationError.permissionDenied
    }
}

internal fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Notifications",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
}

internal fun notificationPreferences(context: Context) =
    context.getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)

internal fun notificationId(identifier: String): Int = identifier.hashCode()

internal fun uniqueWorkName(identifier: String): String = "nexa.local.notification.$identifier"

internal const val NOTIFICATION_CHANNEL_ID = "dev.nexa.notifications.local"
internal const val NOTIFICATION_PREFERENCES = "dev.nexa.notifications.local"
internal const val BADGE_PREFERENCES = "dev.nexa.notifications.badge"
internal const val BADGE_COUNT_KEY = "count"
internal const val LOCAL_NOTIFICATION_WORK_TAG = "dev.nexa.notifications.local"
internal const val KEY_IDENTIFIER = "identifier"
internal const val KEY_TITLE = "title"
internal const val KEY_BODY = "body"
internal const val KEY_LOCAL_OPENED = "nexa.local.opened"
internal const val ACTION_LOCAL_NOTIFICATION_OPENED = "dev.nexa.notifications.LOCAL_OPENED"
