# `dev.nexa.sqlite`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-SQLite3-brightgreen.svg)](https://sqlite.org)

App-private SQLite database engine backed by native platform SQLite libraries on iOS and Android.

Supports typed struct queries, reactive signals (`observeQuery`), schema migrations, and invalidation for app and widget consumers. Database operations run asynchronously away from the UI executor.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/sqlite" as SQLite

struct TaskRow {
    id: Int64,
    title: String,
    isCompleted: Bool
}

app SharedTaskList {
    let database = SQLite.Database("reading_tasks", false)
    state setupTask: TaskHandle? = null
    state writeTask: TaskHandle? = null
    state status: String = "Preparing task list"
    state tasks: Signal<Array<TaskRow>> = database.observeQuery<TaskRow>(
        "SELECT id, title, is_completed AS isCompleted FROM tasks ORDER BY id DESC",
        []
    )

    body {
        OnAppear {
            Task.launch(handle: setupTask, executor: TaskExecutor.Main) {
                try {
                    await database.migrate([
                        SQLite.Migration(1, [
                            "CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, priority TEXT NOT NULL DEFAULT 'Normal', is_completed INTEGER NOT NULL DEFAULT 0)"
                        ])
                    ])
                    status = "Task list ready"
                } catch {
                    status = "Could not prepare the local database"
                }
            }
        }
        OnDisappear { database.dispose() }
        Column(spacing: 12, padding: 16) {
            Text(status)
            if tasks.value.isEmpty {
                Text("No tasks yet. Add a task to get started.")
            } else {
                FastList(tasks.value, key: .id) { task, index in
                    Text(task.title + (task.isCompleted ? " · Done" : " · Open"))
                }
            }
            Button("Add a reading task") {
                Task.launch(handle: writeTask, executor: TaskExecutor.Main) {
                    try {
                        await database.execute(
                            "INSERT INTO tasks (title, priority, is_completed) VALUES (?, ?, ?)",
                            ["Read the next chapter", "High", false]
                        )
                        status = "Task saved"
                    } catch {
                        status = "Could not save the task"
                    }
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `Database` handle

Create one handle for the database name your app uses. Set `sharedWithWidgets` to `true` when app and widget code must open the same database; iOS requires an App Group.

| Constructor | Signature | Description |
|---|---|---|
| `Database` | `Database(name: String, sharedWithWidgets: Bool)` | Opens or creates the named app-private database. |


#### Methods

| Method | Return Type | Description |
|---|---|---|
| `execute(sql: String, parameters: Array<Value>)` | `async -> ExecutionResult throws Failure` | Executes a write statement and conservatively invalidates query observers. |
| `executeTracked(sql: String, parameters: Array<Value>, changedTables: Array<String>)` | `async -> ExecutionResult throws Failure` | Executes a write and notifies observers for the supplied changed table names. |
| `executeRaw(sql: String, parameters: Array<Value>)` | `async -> ExecutionResult throws Failure` | Dynamic SQL escape hatch; conservatively invalidates observers. |
| `executeBatch(sql: String, rows: Array<Array<Value>>)` | `async -> ExecutionResult throws Failure` | Executes the statement for each parameter row in one transaction. |
| `executeTransaction(statements: Array<Statement>)` | `async -> Array<ExecutionResult> throws Failure` | Runs the statements atomically. A failing statement rolls back the transaction. |
| `query<T: Row>(sql: String, parameters: Array<Value>)` | `async -> Array<T> throws Failure` | Maps selected columns into the declared struct type on the database worker. Declares `rowFailure queryFailed`, so the row mapper can surface `queryFailed` in addition to statement failures. |
| `observeQuery<T: Row>(sql: String, parameters: Array<Value>)` | `Signal<Array<T>>` | Creates a signal that refreshes when a committed write may affect tables read by the query. Read its current value with `.value`. Also declares `rowFailure queryFailed`, so refreshing can surface `queryFailed`. |
| `queryRaw(sql: String, parameters: Array<Value>)` | `async -> QueryResult throws Failure` | Returns column names and rows whose cells use the typed `Value` enum. |
| `migrate(migrations: Array<Migration>)` | `async -> Int32 throws Failure` | Runs sequential pending migrations and returns the applied schema version. |
| `userVersion()` | `async -> Int32 throws Failure` | Reads SQLite `PRAGMA user_version`. |
| `observe()` | `InvalidationSubscription` | Creates a subscription notified on any write to the database. |
| `observeTables(tables: Array<String>)` | `InvalidationSubscription` | Creates a subscription notified only when listed tables are modified. |
| `attach(subscription: InvalidationSubscription)` | `Void` | Attaches a reusable subscription to observe all tables. |
| `attachTables(subscription: InvalidationSubscription, tables: Array<String>)` | `Void` | Attaches a subscription with specific table filters. |
| `dispose()` | `Void` | Closes the connection and releases its native handle. |

---

### `InvalidationSubscription`

Lifecycle-owned write observer. Attach it when the consumer becomes active and dispose it when that consumer leaves.

| Member | Signature | Description |
|---|---|---|
| Constructor | `InvalidationSubscription()` | Creates a detached subscription. |
| Event | `invalidated()` | Fires after a matching committed write. |
| Method | `dispose()` | Releases the subscription and removes its handlers. |

### SQL parameter values

App code normally passes typed Nexa values directly in `parameters`. `queryRaw` returns cells as `SQLite.Value` cases.

| Case | Payload | SQLite value |
|---|---|---|
| `nullValue` | — | SQL `NULL` |
| `boolean(value: Bool)` | `Bool` | Integer boolean |
| `int32(value: Int32)` | `Int32` | 32-bit integer |
| `int64(value: Int64)` | `Int64` | 64-bit integer |
| `float64(value: Float64)` | `Float64` | Floating-point number |
| `text(value: String)` | `String` | Text |
| `blob(value: Bytes)` | `Bytes` | Blob |


---

### Data Structures

#### `ExecutionResult`
| Field | Type | Description |
|---|---|---|
| `rowsAffected` | `Int64` | Total rows modified by `INSERT`, `UPDATE`, or `DELETE`. Returns `0` for DDL statements. |
| `lastInsertRowId` | `Int64` | Row ID generated by the most recent successful `INSERT`. |

#### `Statement`
| Field | Type | Description |
|---|---|---|
| `sql` | `String` | SQL statement with `?` parameter placeholders. |
| `parameters` | `Array<Value>` | Parameter values bound to the statement placeholders. |

#### `QueryResult`
| Field | Type | Description |
|---|---|---|
| `columnNames` | `Array<String>` | List of returned column names. |
| `rows` | `Array<Array<Value>>` | Row values matching column positions. |

#### `Migration`
| Field | Type | Description |
|---|---|---|
| `version` | `Int32` | Sequential version number starting at `1`. |
| `statements` | `Array<String>` | DDL or DML statements executed in order for this migration step. |

---

### Error Handling (`Failure`)

All throwing methods throw `SQLite.Failure`:

| Error Variant | Description |
|---|---|
| `invalidDatabaseName(message: String)` | Database name contains invalid characters (must be 1–64 alphanumeric/underscore). |
| `appGroupUnavailable` | `sharedWithWidgets: true` requested but no App Group is configured on iOS. |
| `openFailed(message: String)` | Unable to open or create SQLite database file on disk. |
| `invalidValue(message: String)` | Parameter value cannot be bound to SQLite type. |
| `prepareFailed(message: String)` | SQL syntax error or missing table/column during statement preparation. |
| `bindFailed(message: String)` | Error binding parameter at index. |
| `executeFailed(message: String)` | Constraint violation or runtime failure during execution. |
| `queryFailed(message: String)` | Execution failure during row stepping or column decoding. |
| `transactionFailed(message: String)` | Failure during `BEGIN`, `COMMIT`, or `ROLLBACK`. |
| `migrationFailed(message: String)` | Migration step failure; transaction was rolled back. |
| `closed` | Attempted to execute an operation on a disposed database connection. |

---

## 3. Platform Architecture & Widget Sharing

| Setting | iOS (`sharedWithWidgets: true`) | Android (`sharedWithWidgets: true`) |
|---|---|---|
| **Location** | Shared App Group container directory (`group.<bundleId>`) | Package-private app database directory |
| **Widget Sharing** | Shared between main app and iOS WidgetKit extensions | Shared between main app and Jetpack Glance receivers |
| **Refresh Trigger** | Coalesced `WidgetCenter.shared.reloadAllTimelines()` | Explicit broadcast to registered `AppWidgetProvider` |
| **Concurrency** | SQLite WAL (Write-Ahead Logging) mode enabled | SQLite WAL mode enabled |

For an iOS app and WidgetKit extension to open the same database, set the App Group identifier in the app configuration and construct both handles with the same database name and `sharedWithWidgets: true`:

```nx
config {
    app { displayName: "Reading Tasks", version: "1.0.0", buildNumber: 1 }
    ios {
        minVersion: "16.0",
        bundleIdentifier: "dev.example.reading",
        appGroupIdentifier: "group.dev.example.reading"
    }
    android {
        minSdk: 23,
        targetSdk: 36,
        applicationId: "dev.example.reading"
    }
}
```

Add `appGroupIdentifier` to the existing `ios` block; Nexa applies the matching entitlement to the app and widget extension. Android widgets use the app's private database directory and need no App Group configuration.
