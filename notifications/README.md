# `@nexa/notifications`

Native local and remote notifications for Nexa apps.

```nx
plugin "dev.nexa.notifications" as Notifications

let notifications = Notifications()

// Request the shared notification permission from a user action first.
if await Permissions.request(permission: Notifications) == PermissionStatus.granted {
    try {
        await notifications.scheduleLocal(
            "daily-reminder",
            "A reminder",
            "Open the app when you have a moment.",
            60,
        )
        await notifications.scheduleLocalAt(
            "meeting-reminder",
            "Meeting",
            "Your meeting starts now.",
            1_800_000_000,
        )
    } catch {
        case Notifications.NotificationError.permissionDenied {
            // The user or system has blocked notifications.
        }
        case Notifications.NotificationError.invalidIdentifier {
            // Use a non-empty identifier.
        }
        case Notifications.NotificationError.invalidDelay {
            // Delay must be non-negative; an absolute timestamp must be valid and in the future.
        }
        case Notifications.NotificationError.schedulerUnavailable {
            // The platform notification scheduler could not accept the request.
        }
        case Notifications.NotificationError.firebaseNotConfigured {
            // Remote registration is missing its Android Firebase options.
        }
        case Notifications.NotificationError.remoteRegistrationUnavailable {
            // APNs or Firebase could not issue a registration token.
        }
    }
}
```

The plugin exposes `scheduleLocal` (a relative delay) and `scheduleLocalAt` (an
absolute Unix timestamp in seconds), `isLocalPending`, `cancelLocal`,
`cancelAllLocal`, `setBadgeCount`, and `registerRemote`. `isLocalPending(identifier)` reads the native scheduler queue
and returns whether that identifier is still pending; delivered notifications
are not counted. A matching identifier replaces its pending notification. Android uses WorkManager
so scheduled work survives process death and reboot; Android may deliver after
the requested delay because the OS controls background scheduling. iOS uses a
native `UNTimeIntervalNotificationTrigger`. A delay of zero requests immediate
delivery on iOS and the earliest WorkManager execution on Android.
Absolute timestamps must be in the future. Android converts them to a relative
WorkManager delay, so the operating system may deliver after the requested
time; iOS uses a native calendar trigger.

`setBadgeCount(count)` uses the native app-icon badge setter on iOS 16 and later,
with the legacy UIKit setter on earlier supported iOS versions. Android stores
the requested count and applies it to active and subsequently delivered app
notifications using Android's notification number. The launcher controls
whether it displays a dot or a number, and Android cannot show an app-icon badge
without an active notification.

Subscribe to `localNotificationOpened` to receive the identifier, title, and
body when the user opens a notification created by this plugin. This callback
is queued until the Nexa plugin instance installs its event handler, including
when the operating system cold-starts the app. Other notifications owned by
the host app are not reported through this event.

Call `Permissions.request(permission: Notifications)` from a user action before
scheduling visible notifications on Android 13 and later or on iOS. The plugin
does not display its own permission prompt. Apps may also check the permission
status through the core `PermissionStatus` API.

Call `registerRemote()` from a user action to register for remote delivery. It
does not require the Notifications presentation permission: APNs/FCM tokens
can also be used for silent or data-only messages when visible notifications
are disabled. On iOS it registers with APNs and returns the APNs device token.
On Android, add the Firebase app options to `nexa.config.nx`:

```nx
plugins {
    Notifications {
        fcmApiKey: "<Firebase API key>"
        fcmApplicationId: "<Firebase app ID>"
        fcmProjectId: "<Firebase project ID>"
        fcmSenderId: "<Firebase project number>"
    }
}
```

These are Firebase client app identifiers, not server credentials. The Android
application ID must match the Firebase Android app. `registerRemote()` returns
the FCM registration token; `remoteTokenChanged` reports later token updates.
Use a trusted server to send messages to that token.

`remoteNotificationReceived` reports messages delivered while the app is
running. `remoteNotificationOpened` reports a notification opened by the user,
including cold launches. The iOS host wires the plugin app delegate, and the
Android host declares the Firebase messaging service and forwards notification
tap intents. Android delivery requires a configured Firebase project and a
device with Google Play services. iOS remote delivery requires APNs enabled for
the app's signing team. APNs registration can be tested in supported Simulator
setups, though support depends on the installed macOS and Xcode versions; use a
physical device when Simulator registration or delivery is unavailable.

The demo app under `tests/demo/app` exercises permission handling,
pending-state readback, scheduling, and cancellation on both native hosts.
Remote delivery still needs a configured Firebase project and APNs signing
credentials for runtime acceptance.

For iOS Simulator runtime acceptance, boot a Simulator and run
`tests/acceptance/ios-local-notifications.sh` (or set `NEXA_BIN` to the Nexa
executable). Its accessibility-driven UI test requests permission, schedules a
reminder, cancels it, and checks the Simulator unified log for both operations.
