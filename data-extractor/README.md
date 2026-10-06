# `@nexa/data-extractor`

Detects dates, phone numbers, URLs, email addresses, and postal addresses with
the platform's built-in text recognition. Keep one `DataExtractor` instance in
your app or service and call it from an asynchronous task when text changes.

```nx
plugin "dev.nexa.data-extractor" as DataExtractor

app ContactEditor {
    let extractor = DataExtractor.DataExtractor()
    state extractionTask: TaskHandle? = null
    state matches: Array<DataExtractor.ExtractedData> = []
    state message: String = ""

    body {
        TextInput(value: message, placeholder: "Message")
            .onChange { value ->
                Task.launch(handle: extractionTask, executor: TaskExecutor.Main) {
                    matches = await extractor.extract(value)
                }
            }
    }
}
```

`ExtractedData.kind` identifies `date`, `phoneNumber`, `url`, `email`, or
`address`. `text` is the exact matched substring. `start` and `length` are
UTF-16 offsets into the original input on both platforms. A resolved date's
`timestampMillis` is populated on iOS from `NSDataDetector`; Android's public
`TextLinks` result identifies the date range and date/date-time kind but
does not provide a normalized instant, so `timestampMillis` is `null` there.
Apple's detector doesn't expose date-versus-date-time precision, so iOS returns
`timePrecisionAvailable: false`; Android returns `true` for recognized date
matches, with `hasTime` set from its date/date-time entity. Apps can use the
detected text and optional timestamp to present their own date selection flow.

On iOS the plugin uses one cached `NSDataDetector` instance for dates,
addresses, links, and phone numbers on a private serial actor. Email matches
reported as `mailto:` links are exposed as `email`. On Android it uses
`TextClassifier.generateLinks` with the current system locales and an explicit
entity list on API 28+. It supplies a reference time on API 30+, serializes
extraction on a background dispatcher, and keeps blocking classifier work off
the UI thread. Android applications that use this plugin must support API 28
or newer.
