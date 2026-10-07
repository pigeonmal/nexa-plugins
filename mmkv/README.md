# `dev.nexa.mmkv`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-Tencent%20MMKV-red.svg)](https://github.com/Tencent/MMKV)

Synchronous typed key-value storage backed by Tencent MMKV. The native engine uses memory-mapped files (`mmap`); benchmark on target devices before making latency assumptions.

Supports typed primitives, custom structs (`setObject`/`getObject`), collections (`Array`, `Set`, `Map`), hardware AES encryption, multi-process synchronization, and reactive key observation.

---

> **Android minimum API:** 21. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/mmkv" as MMKV

struct ReaderPreferences {
    theme: String,
    dailyGoal: Int32
}

app ReadingPreferences {
    let store = MMKV.MMKVStore("reader-settings", null, false)
    state theme: String = store.getString("theme") ?? "system"
    state dailyGoal: Int32 = store.getInt32("daily-goal") ?? 20

    body {
        Column(spacing: 12) {
            Text("Theme: \(theme)")
            Text("Daily reading goal: \(dailyGoal) minutes")
            Button("Use dark theme") {
                let saved = store.setString("theme", "dark")
                if saved { theme = "dark" }
            }
            Button("Increase daily goal") {
                dailyGoal = dailyGoal + 5
                store.setInt32("daily-goal", dailyGoal)
            }
            Button("Save preferences object") {
                store.setObject("reader-preferences", ReaderPreferences(theme, dailyGoal))
            }
        }
    }
}
```

---

## 2. API Reference

### `MMKVStore` handle

Construct a store with an instance id. Pass a crypt key to encrypt it and `true` for cross-process mode when the same file is intentionally shared across processes. Keep the returned handle for the store's lifetime and call `dispose()` when finished.

| Constructor | Signature | Description |
|---|---|---|
| `MMKVStore` | `MMKVStore(instanceID: String, cryptKey: String?, multiProcess: Bool)` | Opens or creates the named store. |


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `instanceID` | `String` | Read-only | Unique identifier and file stem for this store instance |
| `isEncrypted` | `Bool` | Read-only | Whether the store is encrypted with an AES crypt key |
| `isMultiProcess` | `Bool` | Read-only | Whether cross-process file locks are active |
| `rootDirectory` | `String` | Read-only | Resolved filesystem path where MMKV files reside |
| `version` | `String` | Read-only | Underlying native MMKV C++ library version |
| `pageSize` | `Int64` | Read-only | Memory mapping page size in bytes |

---

#### Scalar & Value Methods

| Method | Return Type | Description |
|---|---|---|
| `setBool(key: String, value: Bool)` | `Bool` | Writes boolean value |
| `getBool(key: String)` | `Bool?` | Reads boolean or returns `null` |
| `setInt32(key: String, value: Int32)` | `Bool` | Writes 32-bit signed integer |
| `getInt32(key: String)` | `Int32?` | Reads 32-bit signed integer or returns `null` |
| `setInt64(key: String, value: Int64)` | `Bool` | Writes 64-bit signed integer |
| `getInt64(key: String)` | `Int64?` | Reads 64-bit signed integer or returns `null` |
| `setUInt32(key: String, value: UInt32)` | `Bool` | Writes 32-bit unsigned integer |
| `getUInt32(key: String)` | `UInt32?` | Reads 32-bit unsigned integer or returns `null` |
| `setUInt64(key: String, value: UInt64)` | `Bool` | Writes 64-bit unsigned integer |
| `getUInt64(key: String)` | `UInt64?` | Reads 64-bit unsigned integer or returns `null` |
| `setFloat32(key: String, value: Float32)` | `Bool` | Writes 32-bit float |
| `getFloat32(key: String)` | `Float32?` | Reads 32-bit float or returns `null` |
| `setFloat64(key: String, value: Float64)` | `Bool` | Writes 64-bit double |
| `getFloat64(key: String)` | `Float64?` | Reads 64-bit double or returns `null` |
| `setString(key: String, value: String)` | `Bool` | Writes UTF-8 string |
| `getString(key: String)` | `String?` | Reads UTF-8 string or returns `null` |
| `setBuffer(key: String, value: Bytes)` | `Bool` | Writes raw binary byte buffer |
| `getBuffer(key: String)` | `Bytes?` | Reads raw binary byte buffer or returns `null` |
| `setObject<T: Struct>(key: String, value: T)` | `Bool` | Serializes custom `.nx` struct using static binary codec |
| `getObject<T: Struct>(key: String)` | `T?` | Deserializes custom `.nx` struct using static binary codec |
| `setList<T>(key: String, values: Array<T>)` | `Bool` | Writes array of typed elements |
| `getList<T>(key: String)` | `Array<T>?` | Reads array of typed elements or returns `null` |
| `setSet<T>(key: String, values: Set<T>)` | `Bool` | Writes set of typed elements |
| `getSet<T>(key: String)` | `Set<T>?` | Reads set of typed elements or returns `null` |
| `setMap<K, V>(key: String, values: Map<K, V>)` | `Bool` | Writes map with sorted keys for reproducible bytes |
| `getMap<K, V>(key: String)` | `Map<K, V>?` | Reads map or returns `null` |

---

#### Key Management & Maintenance

| Method | Return Type | Description |
|---|---|---|
| `contains(key: String)` | `Bool` | Checks whether key exists in store |
| `getAllKeys()` | `Array<String>` | Returns all keys in store sorted lexicographically |
| `getAllKeysWithPrefix(prefix: String)` | `Array<String>` | Returns all keys starting with prefix |
| `getAllKeysWithSuffix(suffix: String)` | `Array<String>` | Returns all keys ending with suffix |
| `getAllKeysMatching(prefix: String, suffix: String)` | `Array<String>` | Returns keys matching both prefix and suffix |
| `remove(key: String)` | `Bool` | Removes specific key from store |
| `removeMany(keys: Array<String>)` | `Int32` | Removes multiple keys and returns count of removed items |
| `clearAll()` | `Bool` | Removes all keys and shrinks the data file to its expected base capacity. |
| `clearAllKeepingSpace()` | `Bool` | Removes all keys while retaining the current file space for faster later writes. |
| `trim()` | `Int64` | Compacts storage file and returns reclaimed bytes |
| `count()` | `Int64` | Total number of keys in store |
| `totalSize()` | `Int64` | Total file size in bytes |
| `actualSize()` | `Int64` | Byte count of active payload data |
| `valueSize(key: String)` | `Int64` | Size of specific key's value including protobuf header |
| `stats()` | `MMKVStats` | Snapshot of store key count, sizes, and page size |
| `sync()` | `Void` | Synchronously flushes memory-mapped pages to disk. The store also syncs when the app enters the background and before `dispose()` closes the handle. |
| `asyncFlush()` | `Void` | Asynchronously queues disk flush on MMKV background worker |
| `enableCompareBeforeSet()` | `Bool` | Skips a write when its encoded value matches the existing value. Unsupported with encryption or key expiration. |
| `disableCompareBeforeSet()` | `Bool` | Disables compare-before-set write skipping. |
| `rekey(cryptKey: String?)` | `Bool` | Changes encryption key or decrypts store if `null` |
| `checkContentChanged()` | `Void` | Manually synchronizes memory map with external process writes |
| `storageExists()` | `Bool` | Checks whether files exist for this store's instance id. |
| `backup(directory: String)` | `Bool` | Backs up store files to target directory path |
| `restore(directory: String)` | `Bool` | Restores store files from target directory path |
| `removeStorage()` | `Bool` | Permanently deletes underlying files from disk |
| `dispose()` | `Void` | Flushes pending writes, unmaps memory pages, and releases the native store handle |

---

#### Change Observers & Events

| Method / Event | Description |
|---|---|
| `observe(key: String)` | Registers key for mutation notifications across processes |
| `unobserve(key: String)` | Stops observing key mutations |
| `unobserveAll()` | Clears all registered key observers |
| `event valueChanged(key: String)` | Fired when any observed key is modified |
| `event contentChanged()` | Fired when an external process writes to the store |

---

### Data Structures

#### `MMKVStats`
| Field | Type | Description |
|---|---|---|
| `keyCount` | `Int64` | Active key count |
| `totalSize` | `Int64` | Allocated file size in bytes |
| `actualSize` | `Int64` | Actual data payload size in bytes |
| `pageSize` | `Int64` | Memory page size in bytes |
