# `@nexa/mail-composer`

Presents a message in the native email composition flow.

```nx
plugin "dev.nexa.mail-composer" as Mail

app MailComposerExample {
    let mail = Mail.MailComposer()
    state mailTask: TaskHandle? = null
    state outcome = ""

    body {
        OnAppear {
            mail.completed { result ->
                outcome = "\(result)"
            }
        }

        Column(spacing: 12) {
            Button("Send feedback") {
                Task.launch(handle: mailTask, executor: TaskExecutor.Main) {
                    try {
                        await mail.present(
                            ["support@example.com"],
                            "App feedback",
                            "Tell us what you think.",
                        )
                    } catch {
                        case Mail.MailComposerError.unavailable {}
                        case Mail.MailComposerError.presentationUnavailable {}
                    }
                }
            }
            Text(outcome)
        }
    }
}
```

On iOS, the plugin presents `MFMailComposeViewController` with the supplied
recipients, subject, and plain-text body. On Android, it launches a chooser for
apps that handle `mailto:`. The method reports `unavailable` if the platform
has no configured mail composer and `presentationUnavailable` if no foreground
host is available. Read `mail.available` before displaying the action; on
Android it reports whether a foreground host exists because Android 11+ package
visibility can hide email clients from a preflight query. The `present` call
still reports `unavailable` if no handler exists. On iOS, handle
`mail.completed` to receive the native
composer result (`sent`, `saved`, `cancelled`, or `failed`) after dismissal.
Android's chooser delegates composition and delivery to the selected email
app, so it cannot report whether the user sent or canceled the message.
`mail.deviceInfo` returns the current device model and operating-system version
for including useful diagnostics in a user-submitted feedback message.
