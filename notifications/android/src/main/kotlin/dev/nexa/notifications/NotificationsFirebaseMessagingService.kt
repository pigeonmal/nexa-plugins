package dev.nexa.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Bridges Firebase callbacks into the Nexa plugin event queue. */
public class NotificationsFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        NotificationsRemoteHub.received(
            RemoteNotification(
                identifier = message.messageId.orEmpty(),
                title = notification?.title ?: message.data["title"].orEmpty(),
                body = notification?.body ?: message.data["body"].orEmpty(),
                data = message.data,
            ),
        )
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        NotificationsRemoteHub.tokenChanged(token)
    }
}
