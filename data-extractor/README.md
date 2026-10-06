# `dev.nexa.data-extractor`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-NSDataDetector%20%2F%20TextClassifier-purple.svg)](https://developer.apple.com/documentation/foundation/nsdatadetector)

On-device detection of dates, phone numbers, web URLs, email addresses, and street addresses in message text.

Backed by Apple `NSDataDetector` on iOS and Android `TextClassifier` on Android. Detection uses platform text APIs; no Nexa cloud service is involved.

---

> **Android minimum API:** 28. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/data-extractor" as DataExtractor

app MessageReview {
    let extractor = DataExtractor.DataExtractor()
    state matches: Array<DataExtractor.ExtractedData> = []
    state status: String = "Scanning message"

    body {
        OnAppear async {
            matches = await extractor.extract("Can we meet tomorrow at 3pm? Email mina@example.com or visit https://nexa.dev.")
            status = "Detected items: \(matches.count)"
        }
        Column(spacing: 8) {
            Text(status)
            FastList(matches) { match, index in
                Text("\(match.kind): \(match.text)")
            }
        }
    }
}
```

---

## 2. API Reference

### `DataExtractor` handle

| Constructor | Signature | Description |
|---|---|---|
| `DataExtractor` | `DataExtractor()` | Creates an on-device text extractor. |


#### Methods

| Method | Return Type | Description |
|---|---|---|
| `extract(text: String)` | `async -> Array<ExtractedData>` | Parses input string and returns matched entities ordered by UTF-16 offset |

---

### Data Structures & Enums

#### `ExtractedDataKind`

| Case | Description |
|---|---|
| `date` | Calendar dates, relative days ("tomorrow", "next Monday"), and timestamp expressions. |
| `phoneNumber` | Local and international phone numbers. |
| `url` | Web URLs, IP addresses, and custom URI schemes. |
| `email` | Email addresses recognized by the platform text APIs. |
| `address` | Physical postal addresses and locations. |

#### `ExtractedData`
| Field | Type | Description |
|---|---|---|
| `kind` | `ExtractedDataKind` | Type of detected entity |
| `text` | `String` | Raw substring matching the entity in the source text |
| `start` | `Int32` | Starting character index in UTF-16 code units |
| `length` | `Int32` | Length of entity substring in UTF-16 code units |
| `timestampMillis` | `Int64?` | Resolved Unix epoch millisecond timestamp for `date` entities |
| `hasTime` | `Bool` | `true` if date entity includes specific hour/minute precision |
| `timePrecisionAvailable` | `Bool` | `false` if platform is unable to distinguish date-only from date-and-time |
