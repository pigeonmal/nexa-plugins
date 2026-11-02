# `dev.nexa.share-sheet`

| **Targets**: iOS 16+ / Android API 23+ | **Contract**: `native.nxid` | **Version**: 0.1.0 |

Open the native iOS share controller or Android Sharesheet from shared `.nx` code. This package shares plain text and links; it does not send content to a service.

## Quick start

```nexa
plugin "plugins/share-sheet" as ShareSheet

app PlaylistSharing {
    let sharing = ShareSheet.ShareSheet()
    state invite = "mazica://playlist/join/playlist-81"

    body {
        Column(gap: 12, padding: 20) {
            Button("Share summer playlist") {
                Task.launch(executor: TaskExecutor.Main) {
                    try {
                        await sharing.shareLink(invite, "Summer road trip")
                    } catch {
                        case ShareSheet.ShareSheetError.presentationUnavailable {
                            Log.warning(message: "Sharing is temporarily unavailable")
                        }
                    }
                }
            }
        }
    }
}
```

## Public API

### `ShareSheetError`

| Variant | Meaning | iOS | Android |
|---|---|---|---|
| `presentationUnavailable` | The active app window or host Activity is not available for presentation. | No key window or no presenter. | No foreground Activity. |

### `ShareSheet`

| Member | Exact signature | Behavior |
|---|---|---|
| Constructor | `ShareSheet()` | Creates a lightweight handle; it does not retain a view controller or Activity. |
| `shareText` | `async fn shareText(text: String, subject: String?) -> Void throws ShareSheetError` | Presents the supplied text. A non-null subject becomes the iOS email subject or Android chooser title where supported. |
| `shareLink` | `async fn shareLink(url: String, title: String?) -> Void throws ShareSheetError` | Shares the supplied string unchanged, including app deep links. A blank value fails with `presentationUnavailable`. |

The async calls report whether the native share UI could be presented. They do not report which destination the person chose or whether a remote recipient received the content.

## Native mapping and lifecycle

| Platform | Native API | Thread and ownership |
|---|---|---|
| iOS | `UIActivityViewController` | Presentation starts on the main actor. The temporary controller and its popover anchor are owned by UIKit. |
| Android | `Intent.ACTION_SEND` with `Intent.createChooser` | Presentation starts from the current foreground Activity. The plugin does not retain the Activity. |

The plugin adds no network request, background task, persistent state, or media copy. App content and presentation logic stay in `.nx`; the native implementation only opens the platform share surface.
