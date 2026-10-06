import Foundation
import SQLite3
#if canImport(WidgetKit)
import WidgetKit
#endif

@MainActor
public final class DatabaseImpl: DatabaseSpec {
    private let connection: SQLiteConnection
    private let sharedWithWidgets: Bool
    private let invalidationKey: String
    private let darwinSync: SQLiteDarwinSync

    public required convenience init(_ name: String, _ sharedWithWidgets: Bool) {
        self.init(name, sharedWithWidgets, false)
    }

    public required init(_ name: String, _ sharedWithWidgets: Bool, _ deviceProtectedStorage: Bool) {
        self.sharedWithWidgets = sharedWithWidgets
        let key = "\(sharedWithWidgets ? "shared" : "private"):\(name)"
        self.invalidationKey = key
        let conn = SQLiteConnection(name: name, sharedWithWidgets: sharedWithWidgets, deviceProtectedStorage: deviceProtectedStorage)
        self.connection = conn
        self.darwinSync = SQLiteDarwinSync(name: name) { [weak conn] in
            guard let conn else { return }
            if let changes = conn.checkForExternalChanges() {
                SQLiteInvalidationCenter.invalidate(key, changedTables: changes)
            }
        }
    }

    public func execute(_ sql: String, _ parameters: [Value]) async throws(Failure) -> ExecutionResult {
        try await performExecute(sql, parameters, changedTables: nil)
    }

    public func executeRaw(_ sql: String, _ parameters: [Value]) async throws(Failure) -> ExecutionResult {
        try await execute(sql, parameters)
    }

    public func executeTracked(
        _ sql: String,
        _ parameters: [Value],
        _ changedTables: [String]
    ) async throws(Failure) -> ExecutionResult {
        try await performExecute(sql, parameters, changedTables: Set(changedTables.map(sqliteTableKey)))
    }

    private func performExecute(
        _ sql: String,
        _ parameters: [Value],
        changedTables: Set<String>?
    ) async throws(Failure) -> ExecutionResult {
        let result = await connection.perform { database in
            try transaction(database, failure: SQLiteFailure.transactionFailed) {
                let native = try executeStatement(database, sql: sql, bindings: try sqliteBindings(parameters))
                if native.rowsAffected > 0 {
                    try bumpTableVersions(database, changedTables: changedTables)
                }
                return native
            }
        }
        let native = try unwrap(result)
        if native.rowsAffected > 0 {
            connection.syncLastSeenDataVersion()
            SQLiteInvalidationCenter.invalidate(invalidationKey, changedTables: changedTables)
            darwinSync.post()
            if sharedWithWidgets { SQLiteWidgetRefreshScheduler.request() }
        }
        return ExecutionResult(rowsAffected: native.rowsAffected, lastInsertRowId: native.lastInsertRowId)
    }

    public func query<T: Copyable & Sendable>(
        _ sql: String,
        _ parameters: [Value],
        _ mapRow: @escaping NexaRowMapper<Value, T, Failure>
    ) async throws(Failure) -> [T] {
        let result = await connection.perform { database in
            try queryStatement(database, sql: sql, bindings: try sqliteBindings(parameters), mapRow: mapRow)
        }
        return try unwrap(result)
    }

    public func observeQuery<T: Copyable & Sendable>(
        _ sql: String,
        _ parameters: [Value],
        _ mapRow: @escaping NexaRowMapper<Value, T, Failure>
    ) -> NexaSignal<[T]> {
        let initialResult = connection.performSync { database in
            try queryStatement(database, sql: sql, bindings: try sqliteBindings(parameters), mapRow: mapRow)
        }
        let initial = (try? unwrap(initialResult)) ?? []
        let tablesResult = connection.performSync { database in
            sqliteExtractReadTables(database, sql: sql)
        }
        let tables = (try? unwrap(tablesResult)).flatMap { $0.isEmpty ? nil : $0 }
        let key = invalidationKey
        let conn = connection

        return NexaSignal(initial: initial) { emit in
            let subscription = SQLiteInvalidationCenter.observe(key, tables: tables)
            subscription.onInvalidated = {
                Task {
                    let nextResult = await conn.perform { database in
                        try queryStatement(database, sql: sql, bindings: try sqliteBindings(parameters), mapRow: mapRow)
                    }
                    if case .success(let rows) = nextResult {
                        emit(rows)
                    }
                }
            }
            return {
                Task { @MainActor in
                    subscription.dispose()
                }
            }
        }
    }

    public func queryRaw(_ sql: String, _ parameters: [Value]) async throws(Failure) -> QueryResult {
        let result = await connection.perform { database in
            let native = try queryStatement(database, sql: sql, bindings: try sqliteBindings(parameters))
            return QueryResult(
                columnNames: native.columnNames,
                rows: native.rows.map { $0.map(sqliteValue) }
            )
        }
        return try unwrap(result)
    }

    public func executeBatch(_ sql: String, _ rows: [[Value]]) async throws(Failure) -> ExecutionResult {
        let result = await connection.perform { database in
            if rows.isEmpty {
                return NativeExecutionResult(rowsAffected: 0, lastInsertRowId: 0)
            }
            let kind = sqliteStatementKind(sql)
            guard kind == .insert || kind == .mutation else {
                throw SQLiteFailure.executeFailed("Batch SQL must be an INSERT, UPDATE, or DELETE statement.")
            }
            let statement = try prepare(database, sql: sql)
            defer { sqlite3_finalize(statement) }
            guard sqlite3_column_count(statement) == 0 else {
                throw SQLiteFailure.executeFailed("Batch statements must not return rows.")
            }
            return try transaction(database, failure: SQLiteFailure.transactionFailed) {
                var rowsAffected: Int64 = 0
                var lastInsertRowId: Int64 = 0
                for row in rows {
                    try bind(statement, database: database, bindings: try sqliteBindings(row))
                    let stepResult = sqlite3_step(statement)
                    guard stepResult == SQLITE_DONE else {
                        if stepResult == SQLITE_ROW {
                            throw SQLiteFailure.executeFailed("Batch statements must not return rows.")
                        }
                        throw SQLiteFailure.executeFailed(SQLiteConnection.message(database))
                    }
                    let changed = Int64(sqlite3_changes(database))
                    rowsAffected += changed
                    if kind == .insert && changed > 0 {
                        lastInsertRowId = sqlite3_last_insert_rowid(database)
                    }
                    guard sqlite3_reset(statement) == SQLITE_OK,
                          sqlite3_clear_bindings(statement) == SQLITE_OK else {
                        throw SQLiteFailure.executeFailed(SQLiteConnection.message(database))
                    }
                }
                if rowsAffected > 0 {
                    try bumpTableVersions(database, changedTables: nil)
                }
                return NativeExecutionResult(rowsAffected: rowsAffected, lastInsertRowId: lastInsertRowId)
            }
        }
        let native = try unwrap(result)
        if native.rowsAffected > 0 {
            connection.syncLastSeenDataVersion()
            SQLiteInvalidationCenter.invalidate(invalidationKey)
            darwinSync.post()
            if sharedWithWidgets { SQLiteWidgetRefreshScheduler.request() }
        }
        return ExecutionResult(rowsAffected: native.rowsAffected, lastInsertRowId: native.lastInsertRowId)
    }

    public func executeTransaction(_ statements: [Statement]) async throws(Failure) -> [ExecutionResult] {
        let result = await connection.perform { database in
            let nativeStatements = try statements.map { statement in
                NativeSQLiteStatement(sql: statement.sql, bindings: try sqliteBindings(statement.parameters))
            }
            return try transaction(database, failure: SQLiteFailure.transactionFailed) {
                let results = try nativeStatements.map { statement in
                    try executeStatement(database, sql: statement.sql, bindings: statement.bindings)
                }
                if results.contains(where: { $0.rowsAffected > 0 }) {
                    try bumpTableVersions(database, changedTables: nil)
                }
                return results
            }
        }
        let nativeResults = try unwrap(result)
        if nativeResults.contains(where: { $0.rowsAffected > 0 }) {
            connection.syncLastSeenDataVersion()
            SQLiteInvalidationCenter.invalidate(invalidationKey)
            darwinSync.post()
            if sharedWithWidgets { SQLiteWidgetRefreshScheduler.request() }
        }
        return nativeResults.map { native in
            ExecutionResult(rowsAffected: native.rowsAffected, lastInsertRowId: native.lastInsertRowId)
        }
    }

    public func migrate(_ migrations: [Migration]) async throws(Failure) -> Int32 {
        let result = await connection.perform { database in
            let nativeMigrations = migrations.map { migration in
                NativeSQLiteMigration(
                    version: migration.version,
                    statements: migration.statements
                )
            }
            return try migrateDatabase(database, migrations: nativeMigrations)
        }
        let native = try unwrap(result)
        if native.didApplyMigrations {
            connection.syncLastSeenDataVersion()
            SQLiteInvalidationCenter.invalidate(invalidationKey)
            darwinSync.post()
            if sharedWithWidgets { SQLiteWidgetRefreshScheduler.request() }
        }
        return native.version
    }

    public func userVersion() async throws(Failure) -> Int32 {
        let result = await connection.perform { database in try readUserVersion(database) }
        return try unwrap(result)
    }

    public func observe() -> InvalidationSubscriptionImpl {
        SQLiteInvalidationCenter.observe(invalidationKey)
    }

    public func observeTables(_ tables: [String]) -> InvalidationSubscriptionImpl {
        SQLiteInvalidationCenter.observe(
            invalidationKey,
            tables: Set(tables.map(sqliteTableKey))
        )
    }

    public func attach(_ subscription: InvalidationSubscriptionImpl) {
        subscription.attach(invalidationKey, tables: nil, replaceTables: false)
    }

    public func attachTables(_ subscription: InvalidationSubscriptionImpl, _ tables: [String]) {
        subscription.attach(
            invalidationKey,
            tables: Set(tables.map(sqliteTableKey)),
            replaceTables: true
        )
    }

    public func dispose() {
        darwinSync.dispose()
        connection.close()
    }

    /// Backstop for a handle whose owner was released without an explicit
    /// `dispose()`.
    ///
    /// `close()` is what calls `sqlite3_close_v2`, and a raw SQLite pointer is
    /// invisible to ARC: releasing this object without `dispose()` leaks the
    /// database file handle and its WAL for the life of the process. Callers
    /// should still dispose explicitly -- `OnDisappear` is the documented place,
    /// and an explicit call wins because `dispose()` is idempotent -- but a
    /// forgotten call should not cost a file descriptor.
    ///
    /// Only the connection is closed here. `deinit` runs outside the main
    /// actor, so it cannot call the `@MainActor` `dispose()`, and it does not
    /// need to: releasing `darwinSync` runs that object's own `deinit`, which
    /// removes the Darwin notification observer.
    deinit {
        connection.close()
    }
}

/// Independent subscription handles keep one screen from replacing another's callback.
@MainActor
public final class InvalidationSubscriptionImpl: InvalidationSubscriptionSpec {
    public var onInvalidated: (() -> Void)?
    private var invalidationKey: String?
    private var observedTables: Set<String>?
    private var deliveryPending = false
    private var disposed = false

    public required init() {}

    fileprivate init(invalidationKey: String, tables: Set<String>? = nil) {
        self.invalidationKey = invalidationKey
        observedTables = tables
    }

    fileprivate func attach(_ key: String, tables: Set<String>?, replaceTables: Bool) {
        if let invalidationKey {
            SQLiteInvalidationCenter.remove(invalidationKey, self)
        }
        invalidationKey = key
        if replaceTables { observedTables = tables }
        deliveryPending = false
        disposed = false
        SQLiteInvalidationCenter.add(key, self)
    }

    public func dispose() {
        guard !disposed else { return }
        disposed = true
        onInvalidated = nil
        if let invalidationKey {
            SQLiteInvalidationCenter.remove(invalidationKey, self)
        }
        invalidationKey = nil
    }

    fileprivate func scheduleInvalidation() {
        guard !disposed, !deliveryPending else { return }
        deliveryPending = true
        Task { @MainActor [weak self] in
            await Task.yield()
            guard let self else { return }
            deliveryPending = false
            guard !disposed else { return }
            onInvalidated?()
        }
    }

    fileprivate var isActive: Bool { !disposed }

    fileprivate func matches(_ changedTables: Set<String>?) -> Bool {
        guard let observedTables, let changedTables else { return true }
        return !observedTables.isDisjoint(with: changedTables)
    }
}

@MainActor
private final class WeakInvalidationSubscription {
    weak var value: InvalidationSubscriptionImpl?

    init(_ value: InvalidationSubscriptionImpl) {
        self.value = value
    }
}

@MainActor
private enum SQLiteInvalidationCenter {
    private static var subscriptions: [String: [WeakInvalidationSubscription]] = [:]

    static func observe(_ key: String, tables: Set<String>? = nil) -> InvalidationSubscriptionImpl {
        let subscription = InvalidationSubscriptionImpl(invalidationKey: key, tables: tables)
        add(key, subscription)
        return subscription
    }

    static func add(_ key: String, _ subscription: InvalidationSubscriptionImpl) {
        subscriptions[key, default: []].append(WeakInvalidationSubscription(subscription))
    }

    static func remove(_ key: String, _ subscription: InvalidationSubscriptionImpl) {
        subscriptions[key]?.removeAll { $0.value == nil || $0.value === subscription }
        if subscriptions[key]?.isEmpty == true { subscriptions.removeValue(forKey: key) }
    }

    static func invalidate(_ key: String, changedTables: Set<String>? = nil) {
        guard var entries = subscriptions[key] else { return }
        entries.removeAll { $0.value?.isActive != true }
        if entries.isEmpty {
            subscriptions.removeValue(forKey: key)
            return
        }
        subscriptions[key] = entries
        entries.compactMap(\.value)
            .filter { $0.matches(changedTables) }
            .forEach { $0.scheduleInvalidation() }
    }
}

/// SQLite identifier matching is ASCII case-insensitive. Folding only ASCII
/// avoids locale-dependent differences between the Apple and Android paths.
private func sqliteTableKey(_ table: String) -> String {
    String(decoding: table.utf8.map { byte in
        byte >= 65 && byte <= 90 ? byte + 32 : byte
    }, as: UTF8.self)
}

#if canImport(WidgetKit)
@MainActor
private enum SQLiteWidgetRefreshScheduler {
    private static var pending: Task<Void, Never>?

    static func request() {
        pending?.cancel()
        pending = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            if #available(iOS 14.0, *) {
                WidgetCenter.shared.reloadAllTimelines()
            }
        }
    }
}
#else
@MainActor
private enum SQLiteWidgetRefreshScheduler {
    static func request() {}
}
#endif

private enum SQLiteFailure: Error, Sendable {
    case invalidDatabaseName(String)
    case appGroupUnavailable
    case openFailed(String)
    case invalidValue(String)
    case prepareFailed(String)
    case bindFailed(String)
    case executeFailed(String)
    case queryFailed(String)
    case transactionFailed(String)
    case migrationFailed(String)
    case closed

    init(_ error: Failure) {
        switch error {
        case .invalidDatabaseName(let message): self = .invalidDatabaseName(message)
        case .appGroupUnavailable: self = .appGroupUnavailable
        case .openFailed(let message): self = .openFailed(message)
        case .invalidValue(let message): self = .invalidValue(message)
        case .prepareFailed(let message): self = .prepareFailed(message)
        case .bindFailed(let message): self = .bindFailed(message)
        case .executeFailed(let message): self = .executeFailed(message)
        case .queryFailed(let message): self = .queryFailed(message)
        case .transactionFailed(let message): self = .transactionFailed(message)
        case .migrationFailed(let message): self = .migrationFailed(message)
        case .closed: self = .closed
        }
    }

    var message: String {
        switch self {
        case .invalidDatabaseName(let message), .openFailed(let message), .invalidValue(let message),
             .prepareFailed(let message), .bindFailed(let message), .executeFailed(let message),
             .queryFailed(let message), .transactionFailed(let message), .migrationFailed(let message):
            message
        case .closed:
            "Database is closed"
        case .appGroupUnavailable:
            "The configured iOS App Group container is unavailable"
        }
    }

    var pluginError: Failure {
        switch self {
        case .invalidDatabaseName(let message): .invalidDatabaseName(message: message)
        case .appGroupUnavailable: .appGroupUnavailable
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
    let statements: [String]
}

private struct NativeSQLiteMigrationResult: Sendable {
    let version: Int32
    let didApplyMigrations: Bool
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
    private let sharedWithWidgets: Bool
    private let deviceProtectedStorage: Bool
    private let queue: DispatchQueue
    private var handle: OpaquePointer?
    private var openFailure: SQLiteFailure?
    private var disposed = false
    private var knownTableVersions: [String: Int64] = [:]
    private var lastSeenDataVersion: Int64 = 0

    init(name: String, sharedWithWidgets: Bool, deviceProtectedStorage: Bool = false) {
        self.name = name
        self.sharedWithWidgets = sharedWithWidgets
        self.deviceProtectedStorage = deviceProtectedStorage
        queue = DispatchQueue(label: "dev.nexa.sqlite.\(UUID().uuidString)")
    }

    private func openIfNeeded() {
        guard !disposed, handle == nil, openFailure == nil else { return }
        guard Self.isValidName(name) else {
            openFailure = .invalidDatabaseName("Database names must contain 1–64 ASCII letters, digits, underscores, or hyphens.")
            return
        }
        let directory: URL
        if sharedWithWidgets {
            guard let groupIdentifier = Bundle.main.infoDictionary?["NexaAppGroupIdentifier"] as? String,
                  let groupDirectory = FileManager.default.containerURL(
                    forSecurityApplicationGroupIdentifier: groupIdentifier
                  ) else {
                openFailure = .appGroupUnavailable
                return
            }
            directory = groupDirectory.appendingPathComponent("NexaSQLite", isDirectory: true)
            try? FileManager.default.setAttributes([
                .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
            ], ofItemAtPath: directory.path)
        } else {
            guard let supportDirectory = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            ).first else {
                openFailure = .openFailed("Application Support directory is unavailable")
                return
            }
            directory = supportDirectory.appendingPathComponent("NexaSQLite", isDirectory: true)
        }
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

        try? FileManager.default.setAttributes([
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ], ofItemAtPath: url.path)

        handle = database
        sqlite3_busy_timeout(database, 5_000)
        guard sqlite3_exec(database, "PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL;", nil, nil, nil) == SQLITE_OK else {
            openFailure = .openFailed(Self.message(database))
            sqlite3_close_v2(database)
            handle = nil
            return
        }

        sqlite3_exec(
            database,
            "CREATE TABLE IF NOT EXISTS _nexa_table_versions (table_name TEXT PRIMARY KEY, version INTEGER NOT NULL) WITHOUT ROWID;",
            nil, nil, nil
        )

        var stmt: OpaquePointer?
        if sqlite3_prepare_v2(database, "SELECT table_name, version FROM _nexa_table_versions;", -1, &stmt, nil) == SQLITE_OK, let stmt {
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let namePtr = sqlite3_column_text(stmt, 0) {
                    let tName = String(cString: namePtr)
                    let version = sqlite3_column_int64(stmt, 1)
                    knownTableVersions[tName] = version
                }
            }
            sqlite3_finalize(stmt)
        }

        var dataStmt: OpaquePointer?
        if sqlite3_prepare_v2(database, "PRAGMA data_version;", -1, &dataStmt, nil) == SQLITE_OK, let dataStmt {
            if sqlite3_step(dataStmt) == SQLITE_ROW {
                lastSeenDataVersion = sqlite3_column_int64(dataStmt, 0)
            }
            sqlite3_finalize(dataStmt)
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

    func performSync<T>(
        _ operation: (OpaquePointer) throws -> T
    ) -> Result<T, SQLiteFailure> {
        queue.sync {
            guard !self.disposed else { return .failure(.closed) }
            self.openIfNeeded()
            guard let handle = self.handle else {
                return .failure(self.openFailure ?? .openFailed("Database is unavailable"))
            }
            do {
                return .success(try operation(handle))
            } catch let failure as SQLiteFailure {
                return .failure(failure)
            } catch {
                return .failure(.executeFailed(error.localizedDescription))
            }
        }
    }

    func syncLastSeenDataVersion() {
        queue.async {
            guard !self.disposed, let handle = self.handle else { return }
            var dataStmt: OpaquePointer?
            if sqlite3_prepare_v2(handle, "PRAGMA data_version;", -1, &dataStmt, nil) == SQLITE_OK, let dataStmt {
                if sqlite3_step(dataStmt) == SQLITE_ROW {
                    self.lastSeenDataVersion = sqlite3_column_int64(dataStmt, 0)
                }
                sqlite3_finalize(dataStmt)
            }
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(handle, "SELECT table_name, version FROM _nexa_table_versions;", -1, &stmt, nil) == SQLITE_OK, let stmt {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    if let namePtr = sqlite3_column_text(stmt, 0) {
                        let tName = String(cString: namePtr)
                        let version = sqlite3_column_int64(stmt, 1)
                        self.knownTableVersions[tName] = version
                    }
                }
                sqlite3_finalize(stmt)
            }
        }
    }

    func checkForExternalChanges() -> Set<String>? {
        queue.sync {
            guard !self.disposed, let handle = self.handle else { return nil }
            var dataStmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, "PRAGMA data_version;", -1, &dataStmt, nil) == SQLITE_OK, let dataStmt else {
                return nil
            }
            defer { sqlite3_finalize(dataStmt) }
            guard sqlite3_step(dataStmt) == SQLITE_ROW else { return nil }
            let currentDataVersion = sqlite3_column_int64(dataStmt, 0)
            if currentDataVersion == self.lastSeenDataVersion {
                return nil
            }
            self.lastSeenDataVersion = currentDataVersion

            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, "SELECT table_name, version FROM _nexa_table_versions;", -1, &stmt, nil) == SQLITE_OK, let stmt else {
                return nil
            }
            defer { sqlite3_finalize(stmt) }
            var changedTables = Set<String>()
            var wildcardChanged = false

            while sqlite3_step(stmt) == SQLITE_ROW {
                guard let namePtr = sqlite3_column_text(stmt, 0) else { continue }
                let tName = String(cString: namePtr)
                let version = sqlite3_column_int64(stmt, 1)
                let prevVersion = self.knownTableVersions[tName] ?? 0
                if version > prevVersion {
                    self.knownTableVersions[tName] = version
                    if tName == "*" {
                        wildcardChanged = true
                    } else {
                        changedTables.insert(tName)
                    }
                }
            }

            if wildcardChanged {
                return nil
            }
            return changedTables.isEmpty ? nil : changedTables
        }
    }

    func close() {
        queue.async {
            if let handle = self.handle {
                sqlite3_close_v2(handle)
                self.handle = nil
            }
            self.disposed = true
            self.openFailure = .closed
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

private final class SQLiteDarwinSync: @unchecked Sendable {
    private let notificationName: CFNotificationName
    private var observer: UnsafeMutableRawPointer?

    init(name: String, onNotification: @escaping @MainActor () -> Void) {
        let notificationString = "dev.nexa.sqlite.\(name).invalidation" as CFString
        self.notificationName = CFNotificationName(notificationString)
        let center = CFNotificationCenterGetDarwinNotifyCenter()

        let observerPtr = Unmanaged.passRetained(DarwinObserverWrapper(handler: onNotification)).toOpaque()
        self.observer = observerPtr

        CFNotificationCenterAddObserver(
            center,
            observerPtr,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let wrapper = Unmanaged<DarwinObserverWrapper>.fromOpaque(observer).takeUnretainedValue()
                Task { @MainActor in
                    wrapper.handler()
                }
            },
            notificationName.rawValue,
            nil,
            .deliverImmediately
        )
    }

    func post() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(center, notificationName, nil, nil, true)
    }

    nonisolated func dispose() {
        if let observer {
            let center = CFNotificationCenterGetDarwinNotifyCenter()
            CFNotificationCenterRemoveObserver(center, observer, notificationName, nil)
            Unmanaged<DarwinObserverWrapper>.fromOpaque(observer).release()
            self.observer = nil
        }
    }

    deinit {
        dispose()
    }
}

private final class DarwinObserverWrapper: @unchecked Sendable {
    let handler: @MainActor () -> Void
    init(handler: @escaping @MainActor () -> Void) {
        self.handler = handler
    }
}

private func bumpTableVersions(_ database: OpaquePointer, changedTables: Set<String>?) throws(SQLiteFailure) {
    let tablesToBump = changedTables ?? ["*"]
    let upsertSql = "INSERT INTO _nexa_table_versions (table_name, version) VALUES (?, 1) ON CONFLICT(table_name) DO UPDATE SET version = _nexa_table_versions.version + 1;"
    let stmt = try prepare(database, sql: upsertSql)
    defer { sqlite3_finalize(stmt) }
    for table in tablesToBump {
        sqlite3_reset(stmt)
        sqlite3_clear_bindings(stmt)
        let byteCount = table.utf8.count
        table.withCString { text in
            _ = sqlite3_bind_text(stmt, 1, text, Int32(byteCount), sqliteTransient)
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw .executeFailed(SQLiteConnection.message(database))
        }
    }
}

private func sqliteExtractReadTables(_ database: OpaquePointer, sql: String) -> Set<String> {
    final class TableCollector {
        var tables = Set<String>()
    }
    let collector = TableCollector()
    let context = Unmanaged.passUnretained(collector).toOpaque()

    sqlite3_set_authorizer(database, { userData, action, arg1, _, _, _ in
        if action == SQLITE_READ, let arg1 = arg1 {
            let table = String(cString: arg1)
            if !table.isEmpty && !table.starts(with: "sqlite_") && !table.starts(with: "_nexa_") {
                let c = Unmanaged<TableCollector>.fromOpaque(userData!).takeUnretainedValue()
                c.tables.insert(sqliteTableKey(table))
            }
        }
        return SQLITE_OK
    }, context)

    var statement: OpaquePointer?
    let result = sql.withCString { source in
        sqlite3_prepare_v2(database, source, -1, &statement, nil)
    }
    if result == SQLITE_OK, let statement {
        sqlite3_finalize(statement)
    }
    sqlite3_set_authorizer(database, nil, nil)
    return collector.tables
}

private func unwrap<T: Sendable>(_ result: Result<T, SQLiteFailure>) throws(Failure) -> T {
    switch result {
    case .success(let value): value
    case .failure(let failure): throw failure.pluginError
    }
}

private func sqliteBindings(_ values: [Value]) throws(SQLiteFailure) -> [SQLiteBinding] {
    var bindings: [SQLiteBinding] = []
    bindings.reserveCapacity(values.count)
    for value in values {
        switch value {
        case .nullValue:
            bindings.append(.null)
        case .boolean(let value):
            bindings.append(.integer(value ? 1 : 0))
        case .int32(let value):
            bindings.append(.integer(Int64(value)))
        case .int64(let value):
            bindings.append(.integer(value))
        case .float64(let value):
            bindings.append(.real(value))
        case .text(let value):
            bindings.append(.text(value))
        case .blob(let value):
            bindings.append(.blob(value))
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
    guard sqlite3_column_count(statement) == 0 else {
        throw .executeFailed("This statement returns rows; use query instead.")
    }
    let kind = sqliteStatementKind(sql)
    guard kind == .insert || kind == .mutation || kind == .schema else {
        throw .executeFailed("Unsupported SQL statement for execute.")
    }
    let result = sqlite3_step(statement)
    guard result == SQLITE_DONE else {
        throw .executeFailed(SQLiteConnection.message(database))
    }
    let rowsAffected = Int64(sqlite3_changes(database))
    return NativeExecutionResult(
        rowsAffected: kind == .schema ? 0 : rowsAffected,
        lastInsertRowId: kind == .insert && rowsAffected > 0 ? sqlite3_last_insert_rowid(database) : 0
    )
}

private enum SQLiteStatementKind {
    case insert
    case mutation
    case schema
    case query
    case other
}

/// Identifies the top-level command without misreading tokens in comments,
/// quoted names, or CTE bodies. SQLite remains the syntax validator.
private func sqliteStatementKind(_ sql: String) -> SQLiteStatementKind {
    guard let first = sqliteKeyword(sql, at: sql.startIndex) else { return .other }
    let command = first.keyword == "WITH" ? sqliteCommandAfterWith(sql, at: first.end) : first.keyword
    switch command {
    case "INSERT", "REPLACE": return .insert
    case "UPDATE", "DELETE": return .mutation
    case "SELECT", "VALUES", "EXPLAIN": return .query
    case "CREATE", "ALTER", "DROP", "VACUUM", "REINDEX", "ANALYZE", "ATTACH", "DETACH":
        return .schema
    default: return .other
    }
}

private func sqliteCommandAfterWith(_ sql: String, at start: String.Index) -> String? {
    var position = sqliteSkipTrivia(sql, at: start)
    if let recursive = sqliteKeyword(sql, at: position), recursive.keyword == "RECURSIVE" {
        position = recursive.end
    }

    while position < sql.endIndex {
        position = sqliteSkipIdentifier(sql, at: position)
        position = sqliteSkipTrivia(sql, at: position)
        if position < sql.endIndex, sql[position] == "(" {
            position = sqliteSkipParenthesized(sql, at: position)
            position = sqliteSkipTrivia(sql, at: position)
        }
        guard let asKeyword = sqliteKeyword(sql, at: position), asKeyword.keyword == "AS" else { return nil }
        position = sqliteSkipTrivia(sql, at: asKeyword.end)
        if let optional = sqliteKeyword(sql, at: position) {
            if optional.keyword == "MATERIALIZED" {
                position = optional.end
            } else if optional.keyword == "NOT",
                      let materialized = sqliteKeyword(sql, at: optional.end),
                      materialized.keyword == "MATERIALIZED" {
                position = materialized.end
            }
        }
        position = sqliteSkipTrivia(sql, at: position)
        guard position < sql.endIndex, sql[position] == "(" else { return nil }
        position = sqliteSkipParenthesized(sql, at: position)
        position = sqliteSkipTrivia(sql, at: position)
        if position < sql.endIndex, sql[position] == "," {
            position = sql.index(after: position)
            continue
        }
        return sqliteKeyword(sql, at: position)?.keyword
    }
    return nil
}

private func sqliteKeyword(_ sql: String, at start: String.Index) -> (keyword: String, end: String.Index)? {
    var position = sqliteSkipTrivia(sql, at: start)
    guard position < sql.endIndex, sqliteIsIdentifierStart(sql[position]) else { return nil }
    let begin = position
    position = sql.index(after: position)
    while position < sql.endIndex, sqliteIsIdentifierCharacter(sql[position]) {
        position = sql.index(after: position)
    }
    return (String(sql[begin..<position]).uppercased(), position)
}

private func sqliteSkipTrivia(_ sql: String, at start: String.Index) -> String.Index {
    var position = start
    while position < sql.endIndex {
        if sql[position].isWhitespace {
            position = sql.index(after: position)
        } else if sql[position] == "-", sql.index(after: position) < sql.endIndex,
                  sql[sql.index(after: position)] == "-" {
            position = sql.index(position, offsetBy: 2)
            while position < sql.endIndex, sql[position] != "\n" { position = sql.index(after: position) }
        } else if sql[position] == "/", sql.index(after: position) < sql.endIndex,
                  sql[sql.index(after: position)] == "*" {
            position = sql.index(position, offsetBy: 2)
            while position < sql.endIndex {
                if sql[position] == "*", sql.index(after: position) < sql.endIndex,
                   sql[sql.index(after: position)] == "/" {
                    position = sql.index(position, offsetBy: 2)
                    break
                }
                position = sql.index(after: position)
            }
        } else {
            return position
        }
    }
    return position
}

private func sqliteSkipIdentifier(_ sql: String, at start: String.Index) -> String.Index {
    guard start < sql.endIndex else { return start }
    let quote = sql[start]
    if quote == "'" || quote == "\"" || quote == "`" {
        var position = sql.index(after: start)
        while position < sql.endIndex {
            if sql[position] == quote {
                let next = sql.index(after: position)
                if next < sql.endIndex, sql[next] == quote {
                    position = sql.index(after: next)
                } else {
                    return next
                }
            } else {
                position = sql.index(after: position)
            }
        }
        return position
    }
    if quote == "[" {
        guard let close = sql[start...].firstIndex(of: "]") else { return sql.endIndex }
        return sql.index(after: close)
    }
    return sqliteKeyword(sql, at: start)?.end ?? sql.index(after: start)
}

private func sqliteSkipParenthesized(_ sql: String, at start: String.Index) -> String.Index {
    var position = start
    var depth = 0
    while position < sql.endIndex {
        let character = sql[position]
        if character == "'" || character == "\"" || character == "`" || character == "[" {
            position = sqliteSkipIdentifier(sql, at: position)
        } else if character == "-", sql.index(after: position) < sql.endIndex,
                  sql[sql.index(after: position)] == "-" {
            position = sqliteSkipTrivia(sql, at: position)
        } else if character == "/", sql.index(after: position) < sql.endIndex,
                  sql[sql.index(after: position)] == "*" {
            position = sqliteSkipTrivia(sql, at: position)
        } else if character == "(" {
            depth += 1
            position = sql.index(after: position)
        } else if character == ")" {
            depth -= 1
            position = sql.index(after: position)
            if depth == 0 { return position }
        } else {
            position = sql.index(after: position)
        }
    }
    return position
}

private func sqliteIsIdentifierStart(_ character: Character) -> Bool {
    character == "_" || character.isASCII && character.isLetter
}

private func sqliteIsIdentifierCharacter(_ character: Character) -> Bool {
    character == "_" || character == "$" || character.isASCII && (character.isLetter || character.isNumber)
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

private func queryStatement<Row: Sendable>(
    _ database: OpaquePointer,
    sql: String,
    bindings: [SQLiteBinding],
    mapRow: NexaRowMapper<Value, Row, Failure>
) throws(SQLiteFailure) -> [Row] {
    let statement = try prepare(database, sql: sql)
    defer { sqlite3_finalize(statement) }
    try bind(statement, database: database, bindings: bindings)
    let columnCount = Int(sqlite3_column_count(statement))
    let columnNames = (0..<columnCount).map { index -> String in
        guard let name = sqlite3_column_name(statement, Int32(index)) else { return "" }
        return String(cString: name)
    }
    let decodeRow: @Sendable (NexaRowReader<Value, Failure>) throws(Failure) -> Row
    do {
        decodeRow = try mapRow(columnNames) { message in Failure.queryFailed(message: message) }
    } catch let error {
        throw SQLiteFailure(error)
    }
    var rows: [Row] = []
    rows.reserveCapacity(16)
    let rowReader = sqliteRowReader(statement)
    while true {
        let result = sqlite3_step(statement)
        if result == SQLITE_DONE { break }
        guard result == SQLITE_ROW else { throw .queryFailed(SQLiteConnection.message(database)) }
        do {
            rows.append(try decodeRow(rowReader))
        } catch let error {
            throw SQLiteFailure(error)
        }
    }
    return rows
}

private func sqliteRowReader(_ statement: OpaquePointer) -> NexaRowReader<Value, Failure> {
    NexaRowReader(
        value: { (index: Int32) -> Value in sqliteValue(readColumn(statement, index: index)) },
        boolean: { (index: Int32, column: String) throws(Failure) -> Bool in try readBoolean(statement, index: index, column: column) },
        optionalBoolean: { (index: Int32, column: String) throws(Failure) -> Bool? in try readOptionalBoolean(statement, index: index, column: column) },
        integer: { (index: Int32, column: String) throws(Failure) -> Int64 in try readInteger(statement, index: index, column: column) },
        optionalInteger: { (index: Int32, column: String) throws(Failure) -> Int64? in try readOptionalInteger(statement, index: index, column: column) },
        decimal: { (index: Int32, column: String) throws(Failure) -> Double in try readDecimal(statement, index: index, column: column) },
        optionalDecimal: { (index: Int32, column: String) throws(Failure) -> Double? in try readOptionalDecimal(statement, index: index, column: column) },
        text: { (index: Int32, column: String) throws(Failure) -> String in try readText(statement, index: index, column: column) },
        optionalText: { (index: Int32, column: String) throws(Failure) -> String? in try readOptionalText(statement, index: index, column: column) },
        bytes: { (index: Int32, column: String) throws(Failure) -> Data in try readBytes(statement, index: index, column: column) },
        optionalBytes: { (index: Int32, column: String) throws(Failure) -> Data? in try readOptionalBytes(statement, index: index, column: column) }
    )
}

private func incompatibleColumn(_ column: String) -> Failure {
    .invalidValue(message: "Column `\(column)` has an incompatible SQLite value.")
}

private func readBoolean(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Bool {
    let value = try readInteger(statement, index: index, column: column)
    guard value == 0 || value == 1 else { throw incompatibleColumn(column) }
    return value == 1
}

private func readOptionalBoolean(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Bool? {
    guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
    return try readBoolean(statement, index: index, column: column)
}

private func readInteger(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Int64 {
    guard sqlite3_column_type(statement, index) == SQLITE_INTEGER else { throw incompatibleColumn(column) }
    return sqlite3_column_int64(statement, index)
}

private func readOptionalInteger(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Int64? {
    guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
    return try readInteger(statement, index: index, column: column)
}

private func readDecimal(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Double {
    let type = sqlite3_column_type(statement, index)
    guard type == SQLITE_FLOAT || type == SQLITE_INTEGER else { throw incompatibleColumn(column) }
    return sqlite3_column_double(statement, index)
}

private func readOptionalDecimal(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Double? {
    guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
    return try readDecimal(statement, index: index, column: column)
}

private func readText(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> String {
    guard sqlite3_column_type(statement, index) == SQLITE_TEXT else { throw incompatibleColumn(column) }
    guard let value = sqlite3_column_text(statement, index) else { return "" }
    let count = Int(sqlite3_column_bytes(statement, index))
    return String(decoding: UnsafeBufferPointer(start: value, count: count), as: UTF8.self)
}

private func readOptionalText(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> String? {
    guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
    return try readText(statement, index: index, column: column)
}

private func readBytes(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Data {
    guard sqlite3_column_type(statement, index) == SQLITE_BLOB else { throw incompatibleColumn(column) }
    let count = Int(sqlite3_column_bytes(statement, index))
    guard count > 0, let value = sqlite3_column_blob(statement, index) else { return Data() }
    return Data(bytes: value, count: count)
}

private func readOptionalBytes(_ statement: OpaquePointer, index: Int32, column: String) throws(Failure) -> Data? {
    guard sqlite3_column_type(statement, index) != SQLITE_NULL else { return nil }
    return try readBytes(statement, index: index, column: column)
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

private func sqliteValue(_ value: NativeSQLiteValue) -> Value {
    switch value {
    case .null:
        .nullValue
    case .integer(let integer):
        .int64(value: integer)
    case .real(let real):
        .float64(value: real)
    case .text(let text):
        .text(value: text)
    case .blob(let blob):
        .blob(value: blob)
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
) throws(SQLiteFailure) -> NativeSQLiteMigrationResult {
    let ordered = migrations
    guard ordered.enumerated().allSatisfy({ Int32($0.offset + 1) == $0.element.version }) else {
        throw .migrationFailed("Migration versions must be unique and consecutive from 1.")
    }
    return try transaction(database, failure: SQLiteFailure.migrationFailed) {
        let originalVersion = try readUserVersion(database)
        var version = originalVersion
        for migration in ordered where migration.version > version {
            guard migration.version == version + 1 else {
                throw SQLiteFailure.migrationFailed("Missing migration version \(version + 1).")
            }
            for statement in migration.statements {
                _ = try executeStatement(database, sql: statement, bindings: [])
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
        return NativeSQLiteMigrationResult(
            version: version,
            didApplyMigrations: version != originalVersion
        )
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
