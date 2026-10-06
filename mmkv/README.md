# MMKV

A synchronous key-value storage plugin for [Tencent MMKV](https://github.com/Tencent/MMKV).
A read walks the memory-mapped page and a write appends to it, so there is no
async boundary anywhere in the surface.

## Instance ownership

Tencent's [official iOS guide](https://github.com/Tencent/MMKV/wiki/iOS_tutorial)
initializes MMKV once during application startup on the main thread, then uses
`defaultMMKV()` for the shared default store. It recommends creating a separate
MMKV instance when a module needs isolated storage. Keep the MMKV handle in a
standalone persistence layer, not in a SwiftUI view or reusable UI component.

In a Nexa app, keep persistence calls in a dedicated `.nx` module and use a
clear key namespace for independent preferences (for example,
`settings.theme`). Use SQLite for related or queryable records such as tasks
and comments. A file-scope immutable
`let` is initialized once per process and emitted as a native file-level
binding (`private let` in Swift, `private val` in Kotlin). It is independent of
app and UI identity, so helper functions in that module reuse one `MMKVStore`
handle across calls:

```nexa
plugin "dev.nexa.mmkv" as MMKV

struct PlayerOptions {
    autoplay: Bool,
    volume: Float64,
}

let playerStore = MMKV.MMKVStore("player", null, false)

fn savePlayerOptions(options: PlayerOptions) -> Bool {
    return playerStore.setObject("options", options)
}

fn loadPlayerOptions() -> PlayerOptions? {
    return playerStore.getObject<PlayerOptions>("options")
}
```

The binding must be immutable; app `state` and component-local `let` bindings
retain their existing UI-scoped lifecycle. Keep the file-scope store and its
read/write helpers together in the standalone persistence module. The handle
then lives for the process lifetime, so per-call `dispose()` is unnecessary.
Use a distinct instance ID when another module needs isolated storage.

## What it stores

Scalars, strings, byte buffers, arrays, sets, and maps use their matching
typed MMKV methods. `setObject` and `getObject<T>` are for app-defined value
structs; do not use them for primitives, enums, or collections. For example,
use `getFloat64` for a `Float64` and `getList<T>` for an array:

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
options = store.getObject("playerOption")            // app struct from the binding
volume = store.getFloat64("volume") ?? 0            // primitive through its typed API
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

Unencrypted stores enable MMKV's compare-before-set optimization by default.
Repeated writes of the same value skip redundant appends. This optimization is
incompatible with encryption and key expiration. When most writes change the
value, call `disableCompareBeforeSet()` to avoid comparing it; call
`enableCompareBeforeSet()` to turn the optimization back on. Both methods
return `false` when MMKV cannot apply the requested setting. Rekeying to an
encrypted store always disables comparison; rekeying back to an unencrypted
store restores the last requested comparison setting.

`rekey` re-encrypts an existing store with a new key, or removes encryption
when the key is `null`. MMKV uses up to 16 bytes of the key for AES-128 and up
to 32 for AES-256, and does not validate the length.

## Change listeners

`observe(key)` registers a key; `valueChanged` fires for writes to observed
keys, including writes another process made. `contentChanged` fires when another
process wrote to the store at all. MMKV reports the store, never the key, so
every observed key of that store is reported.

## Platform notes

- iOS uses the official Tencent MMKV Swift package from version 2.4.2.
- The Android artifact is `io.github.zhongwuzw:mmkv`, which still ships
  32-bit ABIs; upstream `com.tencent:mmkv` dropped them in 2.0.0.
- A file that fails its CRC or length check is recovered, not discarded, so a
  corrupt file keeps whatever is still readable.
- MMKV's cross-process file lock is POSIX-only and is therefore not part of the
  surface.
