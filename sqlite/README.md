# `@nexa/sqlite`

SQLite for typed Nexa apps. The package uses the operating system's SQLite
library on both platforms and adds no third-party dependency.

## Database location

`Database(name, sharedWithWidgets)` opens `<name>.sqlite3`. With
`sharedWithWidgets: false`, it uses the app-private support/database directory.
With `true`, iOS uses the configured App Group container. Android uses the
application database directory for either setting; the app and its widgets
share that package-private database through the same application identity.
Successful committed writes and migrations through a `sharedWithWidgets` handle
request a coalesced widget refresh. iOS uses WidgetKit's timeline reload request; Android targets
the app's registered widget receivers. Both systems decide when the refreshed
snapshot is rendered.
Names must contain 1–64 ASCII letters, digits, underscores, or hyphens. Apps
cannot select arbitrary paths.

Keep one `Database` in a standalone storage module for the lifetime of
the app. The handle opens lazily on the first operation; do not construct and
dispose one for every read or write, and keep it out of view bodies. If the
app explicitly closes a database during shutdown, call `database.dispose()`.
Closing is queued on the database worker and does not block the UI thread. Keep
the handle alive for normal app lifetime.

## Values and queries

Pass SQL parameters as ordinary Nexa values. The compiler lowers each value to
the matching SQLite type, so a single parameter list can contain integers,
strings, decimals, booleans, bytes, and `null` without wrapper constructors:

```nexa
plugin "dev.nexa.sqlite" as SQLite
struct Note {
    id: Int64?
    title: String
    score: Float64
    archived: Bool
}

await database.execute(
    "INSERT INTO notes (title, score, archived) VALUES (?, ?, ?)",
    ["nexa", 2.5, false]
)

let notes: Array<Note> = await database.query(
    "SELECT id, title, score, archived FROM notes WHERE archived = ?",
    [false]
)
```

Typed `query` maps selected columns directly into an app struct. Its generated
mapper resolves the struct's column names once per prepared query, then reads
only the needed fields by cached integer indices using native typed column
accessors. It does not build an intermediate `Value` for each cell.
Optional fields such as `id: Int64?` accept SQL `NULL`; a non-optional field
with a missing or incompatible SQL value returns a mapping error. The mapping
runs on the same background worker as SQLite stepping: a private serial
DispatchQueue on iOS and `Dispatchers.IO` on Android.

Use `queryRaw` when runtime column inspection is needed; it returns column
names and rows of typed `Value` enum cases. SQLite stores booleans as
integer 0/1 values.

Queries return snapshots. `database.observeTables(["notes"])` provides a
per-consumer invalidation event for a query that reads `notes`: subscribe when
the screen appears, run the typed query after invalidation, and dispose the
subscription when it leaves. Table matching is ASCII case-insensitive, as in
SQLite. `observe()` remains available as a wildcard observer for dynamic
queries whose dependencies are not known. Notifications are coalesced on the
UI executor, and subscriptions remain weakly held and explicitly disposable.

Compiler-generated writes use `executeTracked(sql, parameters, changedTables)`
with the complete table write set validated at compile time. Only observers
whose table sets overlap are notified. Keep using `execute`, batches,
transactions, and migrations for dynamic operations; those retain conservative
database-wide invalidation so triggers or opaque SQL cannot silently leave an
observer stale. Invalidation is in-process only; widgets still use the native
OS-requested snapshot refresh path after successful writes to a shared database.

```nexa
struct NoteSummary {
    id: Int64,
    title: String
}

state notes: Array<NoteSummary> = []
state subscription = SQLite.InvalidationSubscription()
state refreshTask: TaskHandle? = null

OnAppear {
    database.attachTables(subscription, ["notes"])
    subscription.invalidated {
        Task.launch(handle: refreshTask, executor: TaskExecutor.Main) {
            try {
                notes = await database.query(
                    "SELECT id, title FROM notes ORDER BY id DESC",
                    []
                )
            } catch {
                else {
                    Log.error(message: "Could not refresh notes")
                }
            }
        }
    }
}

OnDisappear {
    subscription.dispose()
}
```

The subscription event does not fetch or retain query results. Nexa code owns
the query and its screen state, while `Task.launch` keeps the asynchronous
reload on the main executor and cancels it with the owning view lifecycle.
`database.attachTables(subscription, tables)` can attach the same state handle
again if a screen appears more than once, and replaces its prior table filter.
`database.attach(subscription)` reattaches the handle with its current filter;
an unfiltered handle observes every write. `database.observeTables(tables)` is
convenient for long-lived owners that keep the returned handle themselves.

For performance, prefer typed `query` over `queryRaw`, select only fields the
screen needs, page large result sets, and add indexes for measured query plans.
The plugin intentionally uses one serialized connection per `Database` handle
and prepares each regular query per call; a connection pool or statement cache
should only be added after representative benchmarks show they help.
Android serializes suspend callers with a suspending mutex, so waiting operations
do not occupy extra `Dispatchers.IO` workers.

Dynamic SQL strings are still validated by SQLite when they run. Nexa's
compiler-validated database declarations check query columns, parameter types,
and migration compatibility before emitting typed calls; dynamically
constructed SQL remains a runtime escape hatch. Reactive subscriptions are
lifecycle-manageable and table-targeted, but app code still reruns the query
after the event. Typed queries materialize their result arrays, so large result
sets should use projections and pagination.

All database operations are asynchronous. `execute` is for statements that do
not return rows; use `query` for `SELECT` and statements with `RETURNING`.
Row-producing `PRAGMA` statements such as `PRAGMA table_info(...)` also belong
in `query` or `queryRaw`. User-supplied PRAGMA statements are not accepted by
`execute`; the plugin configures its own connection pragmas.
The number of parameter values must match the SQL bind indexes on both
platforms. Repeated named parameters share one bind index.

`execute` returns the number of changed rows for inserts, updates, and deletes.
`lastInsertRowId` is the inserted row ID and is `0` for statements that did not
insert a row. Schema statements run through the platform's DDL execution path
and report zero changed rows. `executeBatch` accepts only inserts, updates, or
deletes; it prepares once and returns the final inserted row ID, or `0` if no
row was inserted. `migrate` executes each migration statement directly inside
the version transaction.

For many rows, `executeBatch` prepares the SQL once and binds every row inside
one transaction:

```nexa
await database.executeBatch(
    "INSERT INTO notes (title, score, archived) VALUES (?, ?, ?)",
    [["one", 1.0, false], ["two", 2.0, true]]
)
```

Migrations accept direct SQL strings. Keep each string to one statement; a
version is applied atomically with all later migrations:

```nexa
let migrations = [
    SQLite.Migration(1, [
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL)"
    ]),
    SQLite.Migration(2, [
        "ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0"
    ])
]
```

## Compiler-checked declarations

SQL parsing, schema validation, and SQLite-version compatibility analysis are
owned by the SQLite compiler analyzer. The framework supplies only the generic
plugin analyzer protocol and source graph; the Nexa parser and generic compiler
do not parse SQL or recognize SQLite method names.

The package declares that analyzer in `plugin.config.nx`. The current
development command runs `cargo run --quiet --manifest-path compiler/Cargo.toml`
from the package root and keeps the process alive for JSONL requests. This
prototype requires Cargo and access to the crate dependencies on the developer
machine; a packaged analyzer binary is a later distribution improvement.
Analyzer source lives under `compiler/` and uses SQLite's own parser against an
in-memory database. It validates statically resolvable `Database`, `Migration`,
`migrate`, `query`, `execute`, and `executeBatch` calls, then reports SQL
compatibility warnings for the configured iOS and Android minimums. Its
compile-time handle resolver currently supports immutable file or class-static
handles and aliases, plus compile-time migration arrays, as documented below.

The SQLite plugin provides reactive queries via `database.observeQuery<T>(sql, parameters) -> Signal<Array<T>>`.
The returned signal is lifecycle-aware and invalidates automatically whenever writes
touch the underlying tables. The SQLite analyzer validates database access, schema migrations,
and compatibility with target OS platform versions.

Use ordinary Nexa values and methods for database setup and access:

```nexa
plugin "dev.nexa.sqlite" as SQLite

struct Note {
    id: Int64,
    title: String,
    archived: Bool,
}

let database = SQLite.Database("notes", false)
let migrations = [
    SQLite.Migration(1, [
        "CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL, archived INTEGER NOT NULL DEFAULT 0)"
    ])
]

async fn loadActiveNotes() -> Void {
    try {
        let version = await database.migrate(migrations)
        let notes: Array<Note> = await database.query<Note>(
            "SELECT id, title, archived FROM notes WHERE archived = ?",
            [false]
        )
    } catch { }
}

async fn archiveNote(noteID: Int64) -> Void {
    try {
        let result = await database.execute(
            "UPDATE notes SET archived = 1 WHERE id = ?",
            [noteID]
        )
    } catch { }
}
```

When the database handle, migration list, SQL strings, and parameter arrays
are statically resolvable, Nexa validates the migration history, typed query
columns, and bind-slot counts during compilation. Use `queryRaw` and
`executeRaw` for genuinely dynamic SQL. For reactive UI, `observeQuery` provides
automatic table-level invalidation, and explicit `observeTables` / `attachTables`
subscriptions remain available for custom invalidation pipelines.

Dynamic SQL remains available for queries that genuinely need runtime SQL or
computed projections that do not yet have a statically inferable schema type.
Compile-time validation uses a bundled SQLite engine, while generated apps use
the platform SQLite library. Keep schema SQL within the feature set available
on the app's minimum iOS and Android versions; SQLite syntax introduced by a
newer system release can compile here and still be unavailable on an older
device. Use `Database.execute(...)` for statically known SQL so Nexa can
validate its statement and dependencies. Use `Database.executeRaw(...)` only
when SQL must be assembled at runtime; it keeps conservative database-wide
invalidation.

## Transactions and migrations

`executeTransaction` runs all supplied statements inside one write transaction
and rolls the whole batch back if any statement fails. `migrate` uses SQLite's
`user_version`, applies only pending versions in order, requires versions to
start at 1 and be consecutive, and rolls back the entire migration batch on
failure. Pass the complete ordered migration history on each call; already
applied versions are skipped. `userVersion()` reads the current schema version.

## Build and conformance

```sh
nexa plugin check plugins/sqlite
(cd plugins/sqlite/tests/conformance/app && nexa check && nexa test --ios)
(cd plugins/sqlite/tests/conformance/app && nexa check && nexa test --android)
```
