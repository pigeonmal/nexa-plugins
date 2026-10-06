# `@nexa/data-extractor`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-NSDataDetector%20%2F%20TextClassifier-purple.svg)](https://developer.apple.com/documentation/foundation/nsdatadetector)

On-device natural language entity detection and parsing. Automatically identifies dates, calendar appointments, phone numbers, web URLs, email addresses, and street addresses inside arbitrary text.

Backed by Apple `NSDataDetector` on iOS and Android `TextClassifier` on Android. Runs 100% offline on-device with zero cloud dependencies or latency.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.data-extractor" as NLP

component SmartMessageScreen() {
    let extractor = NLP.DataExtractor()
    state detectedLinks: Array<NLP.ExtractedData> = []

    fn parseIncomingMessage(body: String) {
        detectedLinks = await extractor.extract(body)
        for entity in detectedLinks {
            print("Found \(entity.kind) at [\(entity.start):\(entity.length)]: \(entity.text)")
        }
    }

    onAppear(() => {
        parseIncomingMessage("Let's meet tomorrow at 3pm at 123 Market St or call me at 415-555-0199")
    })

    VStack(spacing: 8) {
        FastList(detectedLinks) { entity in
            HStack {
                Text("\(entity.kind)", weight: "bold", size: 14)
                Spacer()
                Text(entity.text, size: 14, color: "#007AFF")
            }
        }
    }
}
```

---

## 2. API Reference

### `DataExtractor` Native Class

```nexa
native class DataExtractor {
    init()
}
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `extract(text: String)` | `Array<ExtractedData>` | Parses input string and returns matched entities ordered by UTF-16 offset |

---

### Data Structures & Enums

#### `ExtractedDataKind`
- `date`: Calendar dates, relative days ("tomorrow", "next Monday"), and timestamp expressions.
- `phoneNumber`: Local and international phone numbers.
- `url`: Web URLs, IP addresses, and custom URI schemes.
- `email`: Validated email addresses.
- `address`: Physical postal addresses and locations.

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
