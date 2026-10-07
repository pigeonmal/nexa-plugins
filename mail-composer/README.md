# `dev.nexa.mail-composer`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-MessageUI%20%2F%20Intent%20Chooser-blue.svg)](https://developer.apple.com/documentation/messageui)

Native email composition sheet for iOS and Android.

Backed by Apple `MessageUI` (`MFMailComposeViewController`) on iOS and standard Android `ACTION_SENDTO` mail client intent chooser on Android.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/mail-composer" as Mail

app FeedbackComposer {
    let composer = Mail.MailComposer()
    state status: String = "Ready to send feedback"
    state sendTask: TaskHandle? = null

    body {
        OnAppear {
            composer.completed { result ->
                status = "Mail flow completed: \(result)"
            }
        }
        Column(spacing: 12) {
            Text(status)
            Button("Write feedback email") {
                Task.launch(handle: sendTask, executor: TaskExecutor.Main) {
                    try {
                        await composer.present(
                            ["support@example.com"],
                            "Feedback about Nexa Reader",
                            "I would like to share feedback about the reading list."
                        )
                        status = "Mail composer opened"
                    } catch {
                        status = "Mail composer is unavailable"
                    }
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `MailComposer` handle

| Constructor | Signature | Description |
|---|---|---|
| `MailComposer` | `MailComposer()` | Creates a native mail composer handle. |


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `available` | `Bool` | Read-only | Whether a configured email account is ready to send mail |
| `deviceInfo` | `String` | Read-only | Formatted string containing device model and OS version (ideal for diagnostic bug reports) |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `present(to: Array<String>, subject: String, body: String)` | `async -> Void throws MailComposerError` | Presents the platform's native mail drafting interface |
| `presentWithAttachments(to: Array<String>, subject: String, body: String, attachments: Array<MailAttachment>)` | `async -> Void throws MailComposerError` | Presents a draft with local attachments. Android accepts `content://` provider URIs; iOS accepts app-accessible `file://` URLs and maps file reads off the main actor before presenting. |

Android grants the selected mail app read access to provider URIs without copying the files. MessageUI accepts attachment `Data`, so iOS reads each app-accessible file into the draft; keep iOS attachments a reasonable size.

#### Events

| Event | Payload | Description |
|---|---|---|
| `completed` | `result: MailComposerResult` | Reports outcome after iOS composer dismissal. (Note: Android external app chooser does not report delivery outcome) |

---

### Enums & Errors

#### `MailAttachment`

| Field | Type | Description |
|---|---|---|
| `uri` | `String` | Android content URI or iOS app-accessible file URL. |
| `mimeType` | `String` | MIME type supplied to the native mail app. |
| `fileName` | `String` | Name shown for the attached file. |

#### `MailComposerResult`

| Case | Description |
|---|---|
| `sent` | The native composer reported the message as sent. |
| `saved` | The user saved the message in drafts. |
| `cancelled` | The user dismissed the composer without sending. |
| `failed` | The composer reported a failure. |

#### `MailComposerError`
| Variant | Description |
|---|---|
| `unavailable` | Device has no email client or active email accounts configured |
| `presentationUnavailable` | Current UI viewController / Activity cannot present modal sheets |
| `attachmentUnavailable` | Attachment URI, file access, MIME type, or name is invalid |
