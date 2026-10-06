# `@nexa/sqlite`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-SQLite3-brightgreen.svg)](https://sqlite.org)

App-private, high-performance SQLite database engine backed directly by native platform SQLite libraries (`libsqlite3.dylib` on iOS, Android framework SQLite/NDK on Android).

Supports typed struct mapping (`<T: Row>`), zero-copy decoding on background threads, reactive signals (`observeQuery`), schema migrations, and cross-process invalidation with iOS WidgetKit and Android Glance widgets.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.sqlite" as SQLite

struct TaskItem {
    id: Int64?,
    title: String,
    completed: Bool,
    priority: Int32
}

component TaskScreen() {
    let db = SQLite.Database("tasks_app", sharedWithWidgets: true)
    state tasks: Signal<Array<TaskItem>> = db.observeQuery<TaskItem>(
        "SELECT id, title, completed, priority FROM tasks ORDER BY priority DESC",
        []
    )

    onAppear(() => {
        setupSchema()
    })

    fn setupSchema() {
        try {
            await db.migrate([
                SQLite.Migration(version: 1, statements: [
                    "CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 1);"
                ])
            ])
        } catch SQLite.Failure as err {
            print("Migration failed: \(err)")
        }
    }

    fn addTask(title: String) {
        try {
            await db.execute(
                "INSERT INTO tasks (title, completed, priority) VALUES (?, ?, ?)",
                [title, false, 1]
            )
        } catch SQLite.Failure as err {
            print("Failed to add task: \(err)")
        }
    }

    VStack(spacing: 12) {
        FastList(tasks.value, id: "id") { task in
            HStack {
                Text(task.title, size: 16)
                Spacer()
                Text(task.completed ? "Done" : "Pending", color: task.completed ? "#34C759" : "#FF9500")
            }
        }
    }
}
```

---

## 2. API Reference

### `Database` Native Class

Long-lived SQLite connection manager. Queries and writes run asynchronously away from the main UI thread (dedicated serial `DispatchQueue` on iOS, `Dispatchers.IO` on Android).

```nexa
native class Database {
    init(name: String, sharedWithWidgets: Bool)
}
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `execute(sql: String, parameters: Array<Value>)` | `ExecutionResult` | Executes an `INSERT`, `UPDATE`, or `DELETE` statement. Emits automatic table invalidation. |
| `executeTracked(sql: String, parameters: Array<Value>, changedTables: Array<String>)` | `ExecutionResult` | Executes SQL and explicitly invalidates only the specified observer tables. |
| `executeRaw(sql: String, parameters: Array<Value>)` | `ExecutionResult` | Dynamic escape hatch for arbitrary SQL. Invalidates all subscriptions. |
| `executeBatch(sql: String, rows: Array<Array<Value>>)` | `ExecutionResult` | Executes the statement once per row inside a single atomic transaction. |
| `executeTransaction(statements: Array<Statement>)` | `Array<ExecutionResult>` | Runs multiple statements atomically inside a single `BEGIN ... COMMIT` block. |
| `query<T: Row>(sql: String, parameters: Array<Value>)` | `Array<T>` | Compiles and runs a query, decoding columns directly into struct `T` on a worker thread. |
| `observeQuery<T: Row>(sql: String, parameters: Array<Value>)` | `Signal<Array<T>>` | Returns a live reactive signal that auto-refreshes whenever matching tables are mutated. |
| `queryRaw(sql: String, parameters: Array<Value>)` | `QueryResult` | Escape hatch returning dynamic raw column names and nested value matrices. |
| `migrate(migrations: Array<Migration>)` | `Int32` | Runs pending sequential schema migrations and updates the `PRAGMA user_version`. |
| `userVersion()` | `Int32` | Reads current `PRAGMA user_version`. |
| `observe()` | `InvalidationSubscription` | Creates a subscription notified on any write to the database. |
| `observeTables(tables: Array<String>)` | `InvalidationSubscription` | Creates a subscription notified only when listed tables are modified. |
| `attach(subscription: InvalidationSubscription)` | `Void` | Attaches a reusable subscription to observe all tables. |
| `attachTables(subscription: InvalidationSubscription, tables: Array<String>)` | `Void` | Attaches a subscription with specific table filters. |
| `dispose()` | `Void` | Closes the database connection and frees native resources. |

---

### `InvalidationSubscription` Native Class

Lifecycle-managed observer for fine-grained database mutations.

```nexa
native class InvalidationSubscription {
    init()
    event invalidated()
    fn dispose()
}
```

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
