# MMKV

Synchronous key-value storage on top of [MMKV](https://github.com/margelo/MMKV).
A read walks the memory-mapped page and a write appends to it, so there is no
async boundary anywhere in the surface.

```nexa
plugin "dev.nexa.mmkv" as MMKV

struct PlayerOptions {
    autoplay: Bool,
    volume: Float64,
    state: PlaybackState,
}

app Demo {
    let store = MMKV.MMKVStore("user", null, false)
    state options: PlayerOptions? = null

    body {
        Button("Save") {
            store.setObject("playerOption", PlayerOptions(true, 0.5, PlaybackState.playing))
            options = store.getObject("playerOption")
        }
    }
}
```

## What it stores

Scalars, strings, and byte buffers map onto MMKV's own types and cost one
native call each. Everything else - a struct, an array, a set, a map, an enum,
or a buffer - is stored as one buffer through a value codec the compiler
generates for the bound type:

```nexa
store.setString("name", "nexa")
store.setBuffer("avatar", Bytes.fromFile(path))
store.setObject("playerOption", options)
store.setList<Int32>("scores", [1, 2, 3])
store.setSet<String>("tags", ["a", "b"])
store.setMap<String, Int32>("highScores", scores)
```

A setter binds its type from the value it is given. A getter binds from the type
it has to produce, or from an explicit type argument when there is no other
information:

```nexa
options = store.getObject("playerOption")            // from the binding
volume = store.getObject<Float64>("volume") ?? 0    // explicit
tags = store.getSet<String>("tags") ?? []           // explicit, two type arguments too
scores = store.getMap<String, Int32>("highScores")   // from the binding
```

Sets and maps are written in a canonical order, so the same value produces the
same bytes on both platforms and a store written on one platform reads back on
the other. The layout is fixed and the schema is not versioned: changing a
struct's fields changes what a stored value means, so rename the key or bump a
version field alongside the change.

## Encryption and sharing

`MMKVStore(instanceID, cryptKey, multiProcess)` maps one file. Pass a
`cryptKey` for AES encryption, and `multiProcess = true` when another process
writes the same file. Both must match the values the store was created with.

For workloads that often write an unchanged value, `enableCompareBeforeSet()`
asks MMKV to compare the new value with the stored value and skip redundant
appends. It returns `false` if MMKV cannot enable the option. This optimization
is incompatible with encryption and key expiration; leave it disabled when
values usually change, since comparing adds work to those writes. Call
`disableCompareBeforeSet()` to turn it off again.

`rekey` re-encrypts an existing store with a new key, or removes encryption
when the key is `null`. MMKV uses up to 16 bytes of the key for AES-128 and up
to 32 for AES-256, and does not validate the length.

## Change listeners

`observe(key)` registers a key; `valueChanged` fires for writes to observed
keys, including writes another process made. `contentChanged` fires when another
process wrote to the store at all. MMKV reports the store, never the key, so
every observed key of that store is reported.

## Platform notes

- The iOS package is pinned to an exact commit, because the upstream fork's
  release tags predate its SwiftPM manifest.
- The Android artifact is `io.github.zhongwuzw:mmkv`, which still ships
  32-bit ABIs; upstream `com.tencent:mmkv` dropped them in 2.0.0.
- A file that fails its CRC or length check is recovered, not discarded, so a
  corrupt file keeps whatever is still readable.
- MMKV's cross-process file lock is POSIX-only and is therefore not part of the
  surface.
