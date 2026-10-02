# `@nexa/sqlite`

App-private SQLite for typed Nexa apps. The package uses the operating system's
SQLite library on both platforms and adds no third-party dependency.

## Database location

`SQLiteDatabase(name)` opens `<name>.sqlite3` in the app's private database
directory. Names must contain 1–64 ASCII letters, digits, underscores, or
hyphens. Apps cannot select arbitrary paths.

## Values and queries

SQL parameters are bound values. A `SQLiteValue` selects exactly one of
`nullValue`, `integer`, `real`, `text`, or `blob`; only the payload for the
selected kind may be set. Query rows preserve SQLite's runtime column types and
are returned with their column names. Plugin value structs are constructed with
the plugin namespace alias and positional fields:

```nexa
plugin "dev.nexa.sqlite" as SQLite
let result = await database.query(
    "SELECT id, title FROM notes WHERE id = ?",
    [SQLite.SQLiteValue(SQLite.SQLiteValueKind.integer, 7, null, null, null)]
)
```

All database operations are asynchronous. `execute` is for statements that do
not return rows; use `query` for `SELECT` and statements with `RETURNING`.

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
