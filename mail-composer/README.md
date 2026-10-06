# `@nexa/mail-composer`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-MessageUI%20%2F%20Intent%20Chooser-blue.svg)](https://developer.apple.com/documentation/messageui)

Native email composition sheet for iOS and Android.

Backed by Apple `MessageUI` (`MFMailComposeViewController`) on iOS and standard Android `ACTION_SENDTO` mail client intent chooser on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.mail-composer" as Mail

component FeedbackScreen() {
    let composer = Mail.MailComposer()
    state statusMessage: String? = null

    onAppear(() => {
        composer.onCompleted((result) => {
            switch result {
                case Mail.MailComposerResult.sent:
                    statusMessage = "Thank you! Your feedback has been sent."
                case Mail.MailComposerResult.saved:
                    statusMessage = "Draft saved in your mail app."
                case Mail.MailComposerResult.cancelled:
                    statusMessage = "Feedback cancelled."
                case Mail.MailComposerResult.failed:
                    statusMessage = "Failed to send email."
            }
        })
    })

    fn sendFeedback() {
        if !composer.available {
            statusMessage = "No email account configured on this device."
            return
        }

        try {
            await composer.present(
                to: ["support@example.com"],
                subject: "App Feedback",
                body: "\n\n---\nDevice Info: \(composer.deviceInfo)"
            )
        } catch Mail.MailComposerError as err {
            statusMessage = "Failed to present mail composer: \(err)"
        }
    }

    VStack(spacing: 16) {
        Button("Send Feedback", action: () => { sendFeedback() })
        if let msg = statusMessage {
            Text(msg, size: 14)
        }
    }
}
```

---

## 2. API Reference

### `MailComposer` Native Class

```nexa
native class MailComposer {
    init()
}
```

#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `available` | `Bool` | Read-only | Whether a configured email account is ready to send mail |
| `deviceInfo` | `String` | Read-only | Formatted string containing device model and OS version (ideal for diagnostic bug reports) |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `present(to, subject, body)` | `Void` | Presents the platform's native mail drafting interface |

#### Events

| Event | Payload | Description |
|---|---|---|
| `completed` | `result: MailComposerResult` | Reports outcome after iOS composer dismissal. (Note: Android external app chooser does not report delivery outcome) |

---

### Enums & Errors

#### `MailComposerResult`
- `sent`: Mail was successfully queued or sent by the system client.
- `saved`: User saved the email in their local drafts folder.
- `cancelled`: User discarded the message.
- `failed`: Mail delivery or composer system error.

#### `MailComposerError`
| Variant | Description |
|---|---|
| `unavailable` | Device has no email client or active email accounts configured |
| `presentationUnavailable` | Current UI viewController / Activity cannot present modal sheets |
