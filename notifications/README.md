# `dev.nexa.notifications`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-UserNotifications%20%2F%20FCM-orange.svg)](https://developer.apple.com/documentation/usernotifications)

Native local notifications, remote notification registration and events (Apple APNs / Firebase Cloud Messaging), and badge updates.

Backed by Apple `UserNotifications` on iOS and Android `NotificationManager` + Firebase Messaging on Android.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/notifications" as Notifications

app NotificationsDemo {
    let notifications = Notifications()

    state permissionTask: TaskHandle? = null
    state scheduleTask: TaskHandle? = null
    state remoteTask: TaskHandle? = null
    state status = "Allow notifications to schedule a reminder."

    body {
        OnAppear {
            notifications.remoteNotificationReceived { notification ->
                status = "Received: \(notification.title)"
            }
            notifications.remoteNotificationOpened { notification ->
                status = "Opened: \(notification.title)"
            }
            notifications.localNotificationOpened { notification ->
                status = "Local reminder opened: \(notification.title)"
                Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_OPENED: \(notification.identifier)")
            }
            notifications.remoteTokenChanged { token ->
                if token != "" {
                    status = "Notification token updated."
                    Log.info(message: "NEXA_NOTIFICATIONS_APNS_TOKEN_CHANGED")
                }
            }
        }
        Column(spacing: 12, padding: 20) {
            Text(status)
            Button("Request permission") {
                Task.launch(handle: permissionTask, executor: TaskExecutor.Main) {
                    if await Permissions.request(permission: Notifications) == PermissionStatus.granted {
                        status = "Permission granted."
                    } else {
                        status = "Permission not granted."
                    }
                }
            }
            Button("Schedule 60 second reminder") {
                Task.launch(handle: scheduleTask, executor: TaskExecutor.Main) {
                    try {
                        await notifications.scheduleLocal(
                            "nexa-demo-reminder",
                            "Nexa notification",
                            "This local reminder was scheduled by Nexa.",
                            60,
                        )
                        if (await notifications.isLocalPending("nexa-demo-reminder")) {
                            status = "Reminder scheduled."
                            Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_SCHEDULED_AND_PENDING")
                        } else {
                            status = "Reminder schedule was not confirmed."
                            Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_SCHEDULE_NOT_PENDING")
                        }
                    } catch {
                        case Notifications.NotificationError.permissionDenied {
                            status = "Grant notification permission first."
                        }
                        case Notifications.NotificationError.invalidIdentifier {
                            status = "Notification identifier is empty."
                        }
                        case Notifications.NotificationError.invalidDelay {
                            status = "Notification delay must be non-negative."
                        }
                        case Notifications.NotificationError.schedulerUnavailable {
                            status = "Notification could not be scheduled."
                        }
                        case Notifications.NotificationError.firebaseNotConfigured {
                            status = "Android Firebase options are missing."
                        }
                        case Notifications.NotificationError.remoteRegistrationUnavailable {
                            status = "Remote notification registration failed."
                        }
                    }
                }
            }
            Button("Cancel reminder") {
                notifications.cancelLocal("nexa-demo-reminder")
                status = "Checking reminder cancellation."
                Task.launch(handle: scheduleTask, executor: TaskExecutor.Main) {
                    try {
                        if (await notifications.isLocalPending("nexa-demo-reminder")) {
                            status = "Reminder is still pending."
                            Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_CANCEL_NOT_CONFIRMED")
                        } else {
                            status = "Pending reminder canceled."
                            Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_CANCELED_AND_VERIFIED")
                        }
                    } catch {
                        case Notifications.NotificationError.invalidIdentifier {
                            status = "Notification identifier is empty."
                        }
                        case Notifications.NotificationError.permissionDenied {
                            status = "Notification permission is not available."
                        }
                        case Notifications.NotificationError.invalidDelay {
                            status = "Notification delay must be non-negative."
                        }
                        case Notifications.NotificationError.schedulerUnavailable {
                            status = "Cancellation could not be verified."
                        }
                        case Notifications.NotificationError.firebaseNotConfigured {
                            status = "Android Firebase options are missing."
                        }
                        case Notifications.NotificationError.remoteRegistrationUnavailable {
                            status = "Remote notification registration failed."
                        }
                    }
                }
            }
            Button("Register for remote notifications") {
                Task.launch(handle: remoteTask, executor: TaskExecutor.Main) {
                    try {
                        await notifications.registerRemote()
                        status = "Remote notification token registered."
                        Log.info(message: "NEXA_NOTIFICATIONS_APNS_TOKEN_REGISTERED")
                    } catch {
                        case Notifications.NotificationError.permissionDenied {
                            status = "Remote registration was denied."
                        }
                        case Notifications.NotificationError.invalidIdentifier {
                            status = "Notification identifier is empty."
                        }
                        case Notifications.NotificationError.invalidDelay {
                            status = "Notification delay must be non-negative."
                        }
                        case Notifications.NotificationError.schedulerUnavailable {
                            status = "Notification scheduler is unavailable."
                        }
                        case Notifications.NotificationError.firebaseNotConfigured {
                            status = "Android Firebase options are missing."
                        }
                        case Notifications.NotificationError.remoteRegistrationUnavailable {
                            status = "Remote notification registration failed."
                            Log.info(message: "NEXA_NOTIFICATIONS_APNS_TOKEN_REGISTRATION_FAILED")
                        }
                    }
                }
            }
            Button("Cancel all reminders") {
                notifications.cancelAllLocal()
                status = "All plugin reminders canceled."
                Log.info(message: "NEXA_NOTIFICATIONS_LOCAL_CANCEL_ALL")
            }
        }
    }
}
```

---

## 2. API Reference

### `Notifications` handle

| Constructor | Signature | Description |
|---|---|---|
| `Notifications` | `Notifications()` | Creates the notification scheduling and registration handle. |

Central notification dispatcher and scheduler.


#### Methods

| Method | Return Type | Description |
|---|---|---|
| `scheduleLocal(identifier: String, title: String, body: String, delaySeconds: Int64)` | `async -> Void throws NotificationError` | Schedules notification after a relative delay in seconds. |
| `scheduleLocalAt(identifier: String, title: String, body: String, timestampSeconds: Int64)` | `async -> Void throws NotificationError` | Schedules notification at a Unix timestamp in seconds. |
| `isLocalPending(identifier: String)` | `async -> Bool throws NotificationError` | Checks whether scheduled notification is waiting in OS queue |
| `cancelLocal(identifier: String)` | `Void` | Removes pending scheduled notification matching identifier |
| `cancelAllLocal()` | `Void` | Removes all pending local notifications scheduled by this app |
| `setBadgeCount(count: Int32)` | `async -> Void` | Updates app launcher icon badge count (iOS and supported Android launchers) |
| `registerRemote()` | `async -> String throws NotificationError` | Requests APNs token on iOS or FCM token on Android |

#### Events

| Event | Payload | Description |
|---|---|---|
| `remoteNotificationReceived` | `notification: RemoteNotification` | Fired when push arrives while app is in foreground |
| `remoteNotificationOpened` | `notification: RemoteNotification` | Fired when user taps push notification banner |
| `remoteTokenChanged` | `token: String` | Fired when OS or FCM server rotates device push token |
| `localNotificationOpened` | `notification: LocalNotification` | Fired when user opens local notification banner |

---

### Data Structures

#### `LocalNotification`
| Field | Type | Description |
|---|---|---|
| `identifier` | `String` | Unique notification request ID |
| `title` | `String` | Bold headline text |
| `body` | `String` | Main message text |

#### `RemoteNotification`
| Field | Type | Description |
|---|---|---|
| `identifier` | `String` | Remote push message ID |
| `title` | `String` | Headline text |
| `body` | `String` | Push message body |
| `data` | `Map<String, String>` | Custom key-value payload dictionary from APNs `userInfo` or FCM `data` |

---

### Configuration (`nexa.config.nx`)

For Android Firebase Cloud Messaging, configure project credentials:

```nexa
config {
    plugins {
        Notifications {
            fcmApiKey: "AIzaSy...",
            fcmApplicationId: "1:123456789:android:abcdef",
            fcmProjectId: "my-app-project",
            fcmSenderId: "123456789",
        }
    }
}
```

---

### Error Handling (`NotificationError`)

| Variant | Description |
|---|---|
| `permissionDenied` | User rejected notification alert/badge permissions |
| `invalidIdentifier` | Empty or illegal notification identifier |
| `invalidDelay` | Delay seconds must be non-negative |
| `schedulerUnavailable` | Platform notification service unavailable |
| `firebaseNotConfigured` | Android push requested but FCM credentials missing in config |
| `remoteRegistrationUnavailable` | Device unable to contact Apple APNs or Google FCM gateways |
