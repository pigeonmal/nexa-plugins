# Nexa Text Codec Plugin

| **Package** | `dev.nexa.text-codec` | **Version** | `0.1.0` |
|---|---|---|---|
| **Platforms** | iOS 13+ and Android API 21+ | **Methods** | Base64 UTF-8 decoding |

Provides a typed platform decoder for text payloads encoded as Base64. Call it from shared `.nx` product code, then keep parsing and decisions in Nexa.

## Quick start

```nexa
plugin "plugins/text-codec" as TextCodec

app LyricsPreview {
    let codec = TextCodec.TextCodec()

    fn decodeLyrics(encoded: String) -> String {
        return codec.decodeBase64Utf8(encoded) ?? ""
    }
}
```

## Public API

### `TextCodec`

| Member | Signature | Meaning |
|---|---|---|
| Constructor | `TextCodec()` | Creates a stateless decoder. |
| Decode | `decodeBase64Utf8(encoded: String) -> String?` | Decodes Base64 bytes as UTF-8; returns `null` for malformed Base64 or invalid UTF-8. |

## Platform mapping

| Platform | Implementation | Ownership |
|---|---|---|
| iOS | Foundation `Data` Base64 decoder and UTF-8 initializer | The returned Swift `String` owns its storage. |
| Android | `android.util.Base64` and UTF-8 `String` decoder | The returned Kotlin `String` owns its storage. |

The plugin adds no third-party dependency, permission, runtime, or product-specific parsing. It is safe to reuse for API payloads and imported text assets.
