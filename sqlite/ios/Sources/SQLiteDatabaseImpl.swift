import Foundation
import SQLite3

@MainActor
public final class SQLiteDatabaseImpl: SQLiteDatabaseSpec {
    private let connection: SQLiteConnection

    public required init(_ name: String) {
        connection = SQLiteConnection(name: name)
    }

    public func execute(_ sql: String, _ parameters: [SQLiteValue]) async throws(SQLiteError) -> SQLiteExecutionResult {
        let bindings = try sqliteBindings(parameters)
        let result = await connection.perform { database in
            try executeStatement(database, sql: sql, bindings: bindings)
        }
        let native = try unwrap(result)
        return SQLiteExecutionResult(rowsAffected: native.rowsAffected, lastInsertRowId: native.lastInsertRowId)
    }

    public func query(_ sql: String, _ parameters: [SQLiteValue]) async throws(SQLiteError) -> SQLiteQueryResult {
        let bindings = try sqliteBindings(parameters)
        let result = await connection.perform { database in
            try queryStatement(database, sql: sql, bindings: bindings)
        }
        let native = try unwrap(result)
        return SQLiteQueryResult(
            columnNames: native.columnNames,
            rows: native.rows.map { $0.map(sqliteValue) }
        )
    }

    public func executeTransaction(_ statements: [SQLiteStatement]) async throws(SQLiteError) -> [SQLiteExecutionResult] {
        var nativeStatements: [NativeSQLiteStatement] = []
        for statement in statements {
            nativeStatements.append(
                NativeSQLiteStatement(sql: statement.sql, bindings: try sqliteBindings(statement.parameters))
            )
        }
        let frozenStatements = nativeStatements
        let result = await connection.perform { database in
            try transaction(database, failure: SQLiteFailure.transactionFailed) {
                try frozenStatements.map { statement in
                    try executeStatement(database, sql: statement.sql, bindings: statement.bindings)
                }
            }
        }
        return try unwrap(result).map { native in
            SQLiteExecutionResult(rowsAffected: native.rowsAffected, lastInsertRowId: native.lastInsertRowId)
        }
    }

    public func migrate(_ migrations: [SQLiteMigration]) async throws(SQLiteError) -> Int32 {
        var nativeMigrations: [NativeSQLiteMigration] = []
        for migration in migrations {
            var statements: [NativeSQLiteStatement] = []
            for statement in migration.statements {
                statements.append(
                    NativeSQLiteStatement(sql: statement.sql, bindings: try sqliteBindings(statement.parameters))
                )
            }
            nativeMigrations.append(
                NativeSQLiteMigration(version: migration.version, statements: statements)
            )
        }
        let frozenMigrations = nativeMigrations
        let result = await connection.perform { database in
            try migrateDatabase(database, migrations: frozenMigrations)
        }
        return try unwrap(result)
    }

    public func userVersion() async throws(SQLiteError) -> Int32 {
        let result = await connection.perform { database in try readUserVersion(database) }
        return try unwrap(result)
    }

    public func dispose() {
        connection.close()
    }
}

private enum SQLiteFailure: Error, Sendable {
    case invalidDatabaseName(String)
    case openFailed(String)
    case invalidValue(String)
    case prepareFailed(String)
    case bindFailed(String)
    case executeFailed(String)
    case queryFailed(String)
    case transactionFailed(String)
    case migrationFailed(String)
    case closed

    var message: String {
        switch self {
        case .invalidDatabaseName(let message), .openFailed(let message), .invalidValue(let message),
             .prepareFailed(let message), .bindFailed(let message), .executeFailed(let message),
             .queryFailed(let message), .transactionFailed(let message), .migrationFailed(let message):
            message
        case .closed:
            "Database is closed"
        }
    }

    var pluginError: SQLiteError {
        switch self {
        case .invalidDatabaseName(let message): .invalidDatabaseName(message: message)
        case .openFailed(let message): .openFailed(message: message)
        case .invalidValue(let message): .invalidValue(message: message)
        case .prepareFailed(let message): .prepareFailed(message: message)
        case .bindFailed(let message): .bindFailed(message: message)
        case .executeFailed(let message): .executeFailed(message: message)
        case .queryFailed(let message): .queryFailed(message: message)
        case .transactionFailed(let message): .transactionFailed(message: message)
        case .migrationFailed(let message): .migrationFailed(message: message)
        case .closed: .closed
        }
    }
}

private enum SQLiteBinding: Sendable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)
}

private struct NativeSQLiteStatement: Sendable {
    let sql: String
    let bindings: [SQLiteBinding]
}

private struct NativeSQLiteMigration: Sendable {
    let version: Int32
    let statements: [NativeSQLiteStatement]
}

private struct NativeExecutionResult: Sendable {
    let rowsAffected: Int64
    let lastInsertRowId: Int64
}

private struct NativeQueryResult: Sendable {
    let columnNames: [String]
    let rows: [[NativeSQLiteValue]]
}

private enum NativeSQLiteValue: Sendable {
    case null
    case integer(Int64)
    case real(Double)
    case text(String)
    case blob(Data)
}

/// Owns SQLite's non-Sendable handle on a private serial queue.
private final class SQLiteConnection: @unchecked Sendable {
    private let name: String
    private let queue: DispatchQueue
    private var handle: OpaquePointer?
    private var openFailure: SQLiteFailure?
    private var disposed = false

    init(name: String) {
        self.name = name
        queue = DispatchQueue(label: "dev.nexa.sqlite.\(UUID().uuidString)")
    }

    private func openIfNeeded() {
        guard !disposed, handle == nil, openFailure == nil else { return }
        guard Self.isValidName(name) else {
            openFailure = .invalidDatabaseName("Database names must contain 1–64 ASCII letters, digits, underscores, or hyphens.")
            return
        }
        guard let supportDirectory = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            openFailure = .openFailed("Application Support directory is unavailable")
            return
        }

        let directory = supportDirectory.appendingPathComponent("NexaSQLite", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            openFailure = .openFailed(error.localizedDescription)
            return
        }

        let url = directory.appendingPathComponent("\(name).sqlite3", isDirectory: false)
        var database: OpaquePointer?
        let result = url.path.withCString { path in
            sqlite3_open_v2(
                path,
                &database,
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
                nil
            )
        }
        guard result == SQLITE_OK, let database else {
            let message = Self.message(database)
            if let database { sqlite3_close_v2(database) }
            openFailure = .openFailed(message)
            return
        }

        handle = database
        sqlite3_busy_timeout(database, 5_000)
        guard sqlite3_exec(database, "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;", nil, nil, nil) == SQLITE_OK else {
            openFailure = .openFailed(Self.message(database))
            sqlite3_close_v2(database)
            handle = nil
            return
        }
    }

    func perform<T: Sendable>(
        _ operation: @escaping @Sendable (OpaquePointer) throws -> T
    ) async -> Result<T, SQLiteFailure> {
        await withCheckedContinuation { continuation in
            queue.async {
                guard !self.disposed else {
                    continuation.resume(returning: .failure(.closed))
                    return
                }
                self.openIfNeeded()
                guard let handle = self.handle else {
                    continuation.resume(returning: .failure(
                        self.openFailure ?? .openFailed("Database is unavailable")
                    ))
                    return
                }
                do {
                    continuation.resume(returning: .success(try operation(handle)))
                } catch let failure as SQLiteFailure {
                    continuation.resume(returning: .failure(failure))
                } catch {
                    continuation.resume(returning: .failure(.executeFailed(error.localizedDescription)))
                }
            }
        }
    }

    func close() {
        queue.sync {
            if let handle {
                sqlite3_close_v2(handle)
                self.handle = nil
            }
            disposed = true
            openFailure = .closed
        }
    }

    fileprivate static func message(_ database: OpaquePointer?) -> String {
        guard let database, let message = sqlite3_errmsg(database) else {
            return "SQLite could not open the database"
        }
        return String(cString: message)
    }

    private static func isValidName(_ name: String) -> Bool {
        let bytes = Array(name.utf8)
        return !bytes.isEmpty && bytes.count <= 64 && bytes.allSatisfy { byte in
            (byte >= 48 && byte <= 57)
                || (byte >= 65 && byte <= 90)
                || (byte >= 97 && byte <= 122)
                || byte == 45
                || byte == 95
        }
    }
}

private func unwrap<T: Sendable>(_ result: Result<T, SQLiteFailure>) throws(SQLiteError) -> T {
    switch result {
    case .success(let value): value
    case .failure(let failure): throw failure.pluginError
    }
}

private func sqliteBindings(_ values: [SQLiteValue]) throws(SQLiteError) -> [SQLiteBinding] {
    var bindings: [SQLiteBinding] = []
    bindings.reserveCapacity(values.count)
    for value in values {
        let hasInteger = value.integerValue != nil
        let hasReal = value.realValue != nil
        let hasText = value.textValue != nil
        let hasBlob = value.blobValue != nil
        switch value.kind {
        case .nullValue where !hasInteger && !hasReal && !hasText && !hasBlob:
            bindings.append(.null)
        case .integer:
            guard hasInteger && !hasReal && !hasText && !hasBlob, let integer = value.integerValue else {
                throw .invalidValue(message: "SQLiteValue payload does not match its kind.")
            }
            bindings.append(.integer(integer))
        case .real:
            guard !hasInteger && hasReal && !hasText && !hasBlob, let real = value.realValue else {
                throw .invalidValue(message: "SQLiteValue payload does not match its kind.")
            }
            bindings.append(.real(real))
        case .text:
            guard !hasInteger && !hasReal && hasText && !hasBlob, let text = value.textValue else {
                throw .invalidValue(message: "SQLiteValue payload does not match its kind.")
            }
            bindings.append(.text(text))
        case .blob:
            guard !hasInteger && !hasReal && !hasText && hasBlob, let blob = value.blobValue else {
                throw .invalidValue(message: "SQLiteValue payload does not match its kind.")
            }
            bindings.append(.blob(blob))
        default:
            throw .invalidValue(message: "SQLiteValue payload does not match its kind.")
        }
    }
    return bindings
}

private func prepare(_ database: OpaquePointer, sql: String) throws(SQLiteFailure) -> OpaquePointer {
    var statement: OpaquePointer?
    var tail: UnsafePointer<CChar>?
    let result = sql.withCString { source in
        sqlite3_prepare_v2(database, source, -1, &statement, &tail)
    }
    guard result == SQLITE_OK, let statement else {
        throw .prepareFailed(SQLiteConnection.message(database))
    }
    if let tail, !String(cString: tail).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        sqlite3_finalize(statement)
        throw .prepareFailed("Pass one SQL statement at a time.")
    }
    return statement
}

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

private func bind(
    _ statement: OpaquePointer,
    database: OpaquePointer,
    bindings: [SQLiteBinding]
) throws(SQLiteFailure) {
    let expectedCount = Int(sqlite3_bind_parameter_count(statement))
    guard expectedCount == bindings.count else {
        throw .bindFailed("Statement expects \(expectedCount) value(s), received \(bindings.count).")
    }
    for (offset, binding) in bindings.enumerated() {
        let index = Int32(offset + 1)
        let result: Int32
        switch binding {
        case .null:
            result = sqlite3_bind_null(statement, index)
        case .integer(let value):
            result = sqlite3_bind_int64(statement, index, value)
        case .real(let value):
            result = sqlite3_bind_double(statement, index, value)
        case .text(let value):
            let byteCount = value.utf8.count
            guard byteCount <= Int(Int32.max) else {
                throw .invalidValue("Text parameters are limited to 2 GiB.")
            }
            result = value.withCString { text in
                sqlite3_bind_text(statement, index, text, Int32(byteCount), sqliteTransient)
            }
        case .blob(let value) where value.isEmpty:
            result = sqlite3_bind_zeroblob(statement, index, 0)
        case .blob(let value):
            guard value.count <= Int32.max else {
                throw .invalidValue("Blob parameters are limited to 2 GiB.")
            }
            result = value.withUnsafeBytes { bytes in
                sqlite3_bind_blob(statement, index, bytes.baseAddress, Int32(bytes.count), sqliteTransient)
            }
        }
        guard result == SQLITE_OK else {
            throw .bindFailed(SQLiteConnection.message(database))
        }
    }
}

private func executeStatement(
    _ database: OpaquePointer,
    sql: String,
    bindings: [SQLiteBinding]
) throws(SQLiteFailure) -> NativeExecutionResult {
    let statement = try prepare(database, sql: sql)
    defer { sqlite3_finalize(statement) }
    try bind(statement, database: database, bindings: bindings)
    let result = sqlite3_step(statement)
    guard result == SQLITE_DONE else {
        if result == SQLITE_ROW {
            throw .executeFailed("This statement returns rows; use query instead.")
        }
        throw .executeFailed(SQLiteConnection.message(database))
    }
    return NativeExecutionResult(
        rowsAffected: Int64(sqlite3_changes(database)),
        lastInsertRowId: sqlite3_last_insert_rowid(database)
    )
}

private func queryStatement(
    _ database: OpaquePointer,
    sql: String,
    bindings: [SQLiteBinding]
) throws(SQLiteFailure) -> NativeQueryResult {
    let statement = try prepare(database, sql: sql)
    defer { sqlite3_finalize(statement) }
    try bind(statement, database: database, bindings: bindings)
    let columnCount = Int(sqlite3_column_count(statement))
    let columnNames = (0..<columnCount).map { index -> String in
        guard let name = sqlite3_column_name(statement, Int32(index)) else { return "" }
        return String(cString: name)
    }
    var rows: [[NativeSQLiteValue]] = []
    while true {
        let result = sqlite3_step(statement)
        if result == SQLITE_DONE { break }
        guard result == SQLITE_ROW else {
            throw .queryFailed(SQLiteConnection.message(database))
        }
        rows.append((0..<columnCount).map { readColumn(statement, index: Int32($0)) })
    }
    return NativeQueryResult(columnNames: columnNames, rows: rows)
}

private func readColumn(_ statement: OpaquePointer, index: Int32) -> NativeSQLiteValue {
    switch sqlite3_column_type(statement, index) {
    case SQLITE_INTEGER:
        return NativeSQLiteValue.integer(sqlite3_column_int64(statement, index))
    case SQLITE_FLOAT:
        return NativeSQLiteValue.real(sqlite3_column_double(statement, index))
    case SQLITE_TEXT:
        guard let value = sqlite3_column_text(statement, index) else { return .text("") }
        let count = Int(sqlite3_column_bytes(statement, index))
        return .text(String(decoding: UnsafeBufferPointer(start: value, count: count), as: UTF8.self))
    case SQLITE_BLOB:
        let count = Int(sqlite3_column_bytes(statement, index))
        guard count > 0, let value = sqlite3_column_blob(statement, index) else { return .blob(Data()) }
        return NativeSQLiteValue.blob(Data(bytes: value, count: count))
    default:
        return NativeSQLiteValue.null
    }
}

private func sqliteValue(_ value: NativeSQLiteValue) -> SQLiteValue {
    switch value {
    case .null:
        SQLiteValue(kind: .nullValue, integerValue: nil, realValue: nil, textValue: nil, blobValue: nil)
    case .integer(let integer):
        SQLiteValue(kind: .integer, integerValue: integer, realValue: nil, textValue: nil, blobValue: nil)
    case .real(let real):
        SQLiteValue(kind: .real, integerValue: nil, realValue: real, textValue: nil, blobValue: nil)
    case .text(let text):
        SQLiteValue(kind: .text, integerValue: nil, realValue: nil, textValue: text, blobValue: nil)
    case .blob(let blob):
        SQLiteValue(kind: .blob, integerValue: nil, realValue: nil, textValue: nil, blobValue: blob)
    }
}

private func transaction<T>(
    _ database: OpaquePointer,
    failure: (String) -> SQLiteFailure,
    body: () throws -> T
) throws(SQLiteFailure) -> T {
    guard sqlite3_exec(database, "BEGIN IMMEDIATE", nil, nil, nil) == SQLITE_OK else {
        throw failure(SQLiteConnection.message(database))
    }
    do {
        let value = try body()
        guard sqlite3_exec(database, "COMMIT", nil, nil, nil) == SQLITE_OK else {
            throw failure(SQLiteConnection.message(database))
        }
        return value
    } catch let error as SQLiteFailure {
        sqlite3_exec(database, "ROLLBACK", nil, nil, nil)
        throw failure(error.message)
    } catch {
        sqlite3_exec(database, "ROLLBACK", nil, nil, nil)
        throw failure(error.localizedDescription)
    }
}

private func migrateDatabase(
    _ database: OpaquePointer,
    migrations: [NativeSQLiteMigration]
) throws(SQLiteFailure) -> Int32 {
    let ordered = migrations.sorted { $0.version < $1.version }
    guard ordered.enumerated().allSatisfy({ Int32($0.offset + 1) == $0.element.version }) else {
        throw .migrationFailed("Migration versions must be unique and consecutive from 1.")
    }
    return try transaction(database, failure: SQLiteFailure.migrationFailed) {
        var version = try readUserVersion(database)
        for migration in ordered where migration.version > version {
            guard migration.version == version + 1 else {
                throw SQLiteFailure.migrationFailed("Missing migration version \(version + 1).")
            }
            for statement in migration.statements {
                _ = try executeStatement(database, sql: statement.sql, bindings: statement.bindings)
            }
            let result = sqlite3_exec(
                database,
                "PRAGMA user_version = \(migration.version)",
                nil,
                nil,
                nil
            )
            guard result == SQLITE_OK else {
                throw SQLiteFailure.migrationFailed(SQLiteConnection.message(database))
            }
            version = migration.version
        }
        return version
    }
}

private func readUserVersion(_ database: OpaquePointer) throws(SQLiteFailure) -> Int32 {
    let statement = try prepare(database, sql: "PRAGMA user_version")
    defer { sqlite3_finalize(statement) }
    guard sqlite3_step(statement) == SQLITE_ROW else {
        throw .queryFailed(SQLiteConnection.message(database))
    }
    return sqlite3_column_int(statement, 0)
}
