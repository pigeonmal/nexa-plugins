# `@nexa/notifications`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-UserNotifications%20%2F%20FCM-orange.svg)](https://developer.apple.com/documentation/usernotifications)

Native local notifications, remote push notifications (Apple APNs / Google Firebase Cloud Messaging), badge counts, and deep-link payload routing.

Backed by Apple `UserNotifications` on iOS and Android `NotificationManager` + Firebase Messaging on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.notifications" as Notifications

component NotificationManagerScreen() {
    let notify = Notifications.Notifications()
    state pushToken: String? = null

    onAppear(() => {
        setupNotifications()
    })

    fn setupNotifications() {
        notify.onLocalNotificationOpened((notif) => {
            print("User tapped local notification: \(notif.identifier)")
        })

        notify.onRemoteTokenChanged((token) => {
            pushToken = token
            print("Device push token: \(token)")
        })

        try {
            pushToken = await notify.registerRemote()
        } catch Notifications.NotificationError as err {
            print("Remote push registration failed: \(err)")
        }
    }

    fn scheduleReminder() {
        try {
            await notify.scheduleLocal(
                identifier: "reminder_1",
                title: "Time for a stretch!",
                body: "You have been sitting for 45 minutes.",
                delaySeconds: 10
            )
        } catch Notifications.NotificationError as err {
            print("Schedule failed: \(err)")
        }
    }

    VStack(spacing: 16) {
        Text(pushToken != null ? "Push Enabled" : "Push Inactive", size: 16)
        Button("Remind Me in 10s", action: () => { scheduleReminder() })
    }
}
```

---

## 2. API Reference

### `Notifications` Native Class

Central notification dispatcher and scheduler.

```nexa
native class Notifications {
    init()
}
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `scheduleLocal(identifier, title, body, delaySeconds)` | `Void` | Schedules notification to fire after a relative delay in seconds |
| `scheduleLocalAt(identifier, title, body, timestampSeconds)` | `Void` | Schedules notification to fire at a specific Unix timestamp in seconds |
| `isLocalPending(identifier: String)` | `Bool` | Checks whether scheduled notification is waiting in OS queue |
| `cancelLocal(identifier: String)` | `Void` | Removes pending scheduled notification matching identifier |
| `cancelAllLocal()` | `Void` | Removes all pending local notifications scheduled by this app |
| `setBadgeCount(count: Int32)` | `Void` | Updates app launcher icon badge count (iOS and supported Android launchers) |
| `registerRemote()` | `String` | Requests APNs token on iOS or FCM token on Android |

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
app MyApp {
    config: {
        "@nexa/notifications": {
            fcmApiKey: "AIzaSy...",
            fcmApplicationId: "1:123456789:android:abcdef",
            fcmProjectId: "my-app-project",
            fcmSenderId: "123456789"
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
| `invalidDelay` | Delay seconds must be greater than zero |
| `schedulerUnavailable` | Platform notification service unavailable |
| `firebaseNotConfigured` | Android push requested but FCM credentials missing in config |
| `remoteRegistrationUnavailable` | Device unable to contact Apple APNs or Google FCM gateways |
