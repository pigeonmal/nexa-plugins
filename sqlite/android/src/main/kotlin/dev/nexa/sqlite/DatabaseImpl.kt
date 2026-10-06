package dev.nexa.sqlite

import android.database.ContentObserver
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteQuery
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.nexa.core.NexaRuntimeCore
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A serialized app-private SQLite connection. Blocking work always runs on IO. */
public class DatabaseImpl(
    name: String,
    private val sharedWithWidgets: Boolean,
    private val deviceProtectedStorage: Boolean = false,
) : DatabaseSpec {
    public constructor(name: String, sharedWithWidgets: Boolean) : this(name, sharedWithWidgets, false)

    private val name = name
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disposed = AtomicBoolean(false)
    private val invalidationKey = name
    private val invalidationUri = Uri.parse("content://dev.nexa.sqlite.provider/$name/invalidation")
    private val knownTableVersions = ConcurrentHashMap<String, Long>()
    private val lastSeenDataVersion = AtomicLong(0L)
    private var database: PlatformSQLiteDatabase? = null
    private var openFailure: Failure? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            ioScope.launch(Dispatchers.IO) {
                checkForExternalChanges()
            }
        }
    }

    init {
        if (!DATABASE_NAME.matches(name)) {
            openFailure = Failure.invalidDatabaseName(
                "Database names must contain 1–64 ASCII letters, digits, underscores, or hyphens.",
            )
        } else {
            try {
                val context = NexaRuntimeCore.context().applicationContext
                context.contentResolver.registerContentObserver(invalidationUri, true, observer)
            } catch (_: Exception) {}
        }
    }

    override suspend fun execute(sql: String, parameters: List<Value>): ExecutionResult =
        executeInternal(sql, parameters, changedTables = null)

    override suspend fun executeRaw(sql: String, parameters: List<Value>): ExecutionResult =
        execute(sql, parameters)

    override suspend fun executeTracked(
        sql: String,
        parameters: List<Value>,
        changedTables: List<String>,
    ): ExecutionResult = executeInternal(
        sql,
        parameters,
        changedTables = changedTables.mapTo(HashSet(changedTables.size), ::sqliteTableKey),
    )

    private suspend fun executeInternal(
        sql: String,
        parameters: List<Value>,
        changedTables: Set<String>?,
    ): ExecutionResult {
        val result = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val db = requireDatabase()
                try {
                    db.beginTransactionNonExclusive()
                    val res = executeLocked(db, sql, parameters)
                    if (res.rowsAffected > 0L) {
                        bumpTableVersionsLocked(db, changedTables)
                    }
                    db.setTransactionSuccessful()
                    res
                } finally {
                    if (db.inTransaction()) db.endTransaction()
                }
            }
        }
        if (result.rowsAffected > 0L) {
            syncAndNotifyAfterWrite(changedTables)
        }
        requestWidgetRefreshIfNeeded(result.rowsAffected)
        return result
    }

    override suspend fun <T> query(
        sql: String,
        parameters: List<Value>,
        mapRow: NexaRowMapper<Value, T, Failure>,
    ): List<T> =
        withContext(Dispatchers.IO) {
            synchronized(lock) { queryLocked(requireDatabase(), sql, parameters, mapRow) }
        }

    override fun <T> observeQuery(
        sql: String,
        parameters: List<Value>,
        mapRow: NexaRowMapper<Value, T, Failure>,
    ): NexaSignal<List<T>> {
        val initialRows = try {
            if (disposed.get()) emptyList()
            else synchronized(lock) {
                queryLocked(requireDatabase(), sql, parameters, mapRow)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val readTables = SQLiteParameterScanner.extractReadTables(sql)
        val filter = readTables.mapTo(HashSet(readTables.size), ::sqliteTableKey).takeIf { it.isNotEmpty() }

        return NexaSignal(initialRows) { emit ->
            val subscription = SQLiteInvalidationCenter.observe(invalidationKey, filter)
            subscription.onInvalidated = {
                ioScope.launch(Dispatchers.IO) {
                    try {
                        val next = synchronized(lock) {
                            queryLocked(requireDatabase(), sql, parameters, mapRow)
                        }
                        emit(next)
                    } catch (_: Exception) {}
                }
            }
            {
                subscription.dispose()
            }
        }
    }

    override suspend fun queryRaw(sql: String, parameters: List<Value>): QueryResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) { queryRawLocked(requireDatabase(), sql, parameters) }
        }

    override suspend fun executeBatch(
        sql: String,
        rows: List<List<Value>>,
    ): ExecutionResult {
        val result = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val db = requireDatabase()
                if (rows.isEmpty()) return@synchronized ExecutionResult(0, 0)
                val kind = SQLiteParameterScanner.statementKind(sql)
                if (kind != SQLiteStatementKind.INSERT && kind != SQLiteStatementKind.MUTATION) {
                    throw Failure.executeFailed("Batch SQL must be an INSERT, UPDATE, or DELETE statement.")
                }
                val parameterCount = SQLiteParameterScanner.count(sql)
                val statement = try {
                    db.compileStatement(sql)
                } catch (error: Exception) {
                    throw Failure.prepareFailed(error.message ?: "Could not prepare batch statement")
                }
                try {
                    db.beginTransactionNonExclusive()
                    var rowsAffected = 0L
                    var lastInsertRowId = 0L
                    rows.forEach { row ->
                        statement.clearBindings()
                        bindAll(statement, row, parameterCount)
                        if (kind == SQLiteStatementKind.INSERT) {
                            val rowId = statement.executeInsert()
                            if (rowId >= 0L) {
                                rowsAffected++
                                lastInsertRowId = rowId
                            }
                        } else {
                            rowsAffected += statement.executeUpdateDelete().coerceAtLeast(0).toLong()
                        }
                    }
                    if (rowsAffected > 0L) {
                        bumpTableVersionsLocked(db, null)
                    }
                    db.setTransactionSuccessful()
                    ExecutionResult(rowsAffected, lastInsertRowId)
                } catch (error: Failure) {
                    throw Failure.transactionFailed(error.message ?: "Batch failed")
                } catch (error: Exception) {
                    throw Failure.transactionFailed(error.message ?: "Batch failed")
                } finally {
                    if (db.inTransaction()) db.endTransaction()
                    statement.close()
                }
            }
        }
        if (result.rowsAffected > 0L) syncAndNotifyAfterWrite(null)
        requestWidgetRefreshIfNeeded(result.rowsAffected)
        return result
    }

    override suspend fun executeTransaction(
        statements: List<Statement>,
    ): List<ExecutionResult> {
        val results = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val db = requireDatabase()
                try {
                    db.beginTransactionNonExclusive()
                    val results = statements.map { statement ->
                        executeLocked(db, statement.sql, statement.parameters)
                    }
                    if (results.any { it.rowsAffected > 0L }) {
                        bumpTableVersionsLocked(db, null)
                    }
                    db.setTransactionSuccessful()
                    results
                } catch (error: Failure) {
                    throw Failure.transactionFailed(error.message ?: "Transaction failed")
                } catch (error: Exception) {
                    throw Failure.transactionFailed(error.message ?: "Transaction failed")
                } finally {
                    if (db.inTransaction()) db.endTransaction()
                }
            }
        }
        if (results.any { it.rowsAffected > 0L }) {
            syncAndNotifyAfterWrite(null)
            requestWidgetRefreshIfNeeded(1L)
        }
        return results
    }

    override fun observe(): InvalidationSubscriptionImpl =
        SQLiteInvalidationCenter.observe(invalidationKey)

    override fun observeTables(tables: List<String>): InvalidationSubscriptionImpl =
        SQLiteInvalidationCenter.observe(
            invalidationKey,
            tables.mapTo(HashSet(tables.size), ::sqliteTableKey),
        )

    override fun attach(subscription: InvalidationSubscriptionImpl) {
        subscription.attach(invalidationKey)
    }

    override fun attachTables(subscription: InvalidationSubscriptionImpl, tables: List<String>) {
        subscription.attach(
            invalidationKey,
            tables.mapTo(HashSet(tables.size), ::sqliteTableKey),
            replaceTables = true,
        )
    }

    override suspend fun migrate(migrations: List<Migration>): Int {
        val (version, didApplyMigrations) = withContext(Dispatchers.IO) {
            synchronized(lock) {
                val db = requireDatabase()
                val ordered = migrations
                if (ordered.withIndex().any { (index, migration) -> migration.version != index + 1 }) {
                    throw Failure.migrationFailed("Migration versions must be unique and consecutive from 1.")
                }

                try {
                    db.beginTransactionNonExclusive()
                    val originalVersion = db.version
                    var version = originalVersion
                    for (migration in ordered) {
                        if (migration.version <= version) continue
                        if (migration.version != version + 1) {
                            throw Failure.migrationFailed("Missing migration version ${version + 1}.")
                        }
                        for (statement in migration.statements) {
                            db.execSQL(statement)
                        }
                        db.version = migration.version
                        version = migration.version
                    }
                    if (version != originalVersion) {
                        bumpTableVersionsLocked(db, null)
                    }
                    db.setTransactionSuccessful()
                    version to (version != originalVersion)
                } catch (error: Failure) {
                    throw error
                } catch (error: Exception) {
                    throw Failure.migrationFailed(error.message ?: "Migration failed")
                } finally {
                    if (db.inTransaction()) db.endTransaction()
                }
            }
        }
        if (didApplyMigrations) {
            syncAndNotifyAfterWrite(null)
            requestWidgetRefreshIfNeeded(1L)
        }
        return version
    }

    override suspend fun userVersion(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { requireDatabase().version }
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            try {
                val context = NexaRuntimeCore.context().applicationContext
                context.contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {}
            ioScope.launch {
                synchronized(lock) {
                    database?.close()
                    database = null
                }
            }
        }
    }

    private fun requireDatabase(): PlatformSQLiteDatabase {
        if (disposed.get()) throw Failure.closed
        openFailure?.let { throw it }
        database?.let { return it }
        val opened = try {
            val rawContext = NexaRuntimeCore.context().applicationContext
            val context = if (deviceProtectedStorage) {
                rawContext.createDeviceProtectedStorageContext()
            } else {
                rawContext
            }
            val file = context.getDatabasePath("$name.sqlite3")
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    throw IllegalStateException("Could not create the app database directory")
                }
            }
            PlatformSQLiteDatabase.openOrCreateDatabase(file, null).apply {
                enableWriteAheadLogging()
                setForeignKeyConstraintsEnabled(true)
                execSQL("CREATE TABLE IF NOT EXISTS _nexa_table_versions (table_name TEXT PRIMARY KEY, version INTEGER NOT NULL)")
                rawQuery("SELECT table_name, version FROM _nexa_table_versions", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        knownTableVersions[cursor.getString(0)] = cursor.getLong(1)
                    }
                }
                rawQuery("PRAGMA data_version", null).use { cursor ->
                    if (cursor.moveToNext()) {
                        lastSeenDataVersion.set(cursor.getLong(0))
                    }
                }
            }
        } catch (error: Exception) {
            val failure = Failure.openFailed(error.message ?: "Could not open database")
            openFailure = failure
            throw failure
        }
        database = opened
        return opened
    }

    private fun bumpTableVersionsLocked(db: PlatformSQLiteDatabase, changedTables: Set<String>?) {
        val tables = changedTables ?: setOf("*")
        val sql = "INSERT INTO _nexa_table_versions (table_name, version) VALUES (?, 1) ON CONFLICT(table_name) DO UPDATE SET version = _nexa_table_versions.version + 1"
        val stmt = db.compileStatement(sql)
        try {
            for (table in tables) {
                stmt.clearBindings()
                stmt.bindString(1, table)
                stmt.execute()
                val prev = knownTableVersions[table] ?: 0L
                knownTableVersions[table] = prev + 1L
            }
        } finally {
            stmt.close()
        }
    }

    private fun syncAndNotifyAfterWrite(changedTables: Set<String>?) {
        val db = database
        if (db != null && db.isOpen) {
            try {
                db.rawQuery("PRAGMA data_version", null).use { cursor ->
                    if (cursor.moveToNext()) {
                        lastSeenDataVersion.set(cursor.getLong(0))
                    }
                }
            } catch (_: Exception) {}
        }
        SQLiteInvalidationCenter.invalidate(invalidationKey, changedTables)
        try {
            val context = NexaRuntimeCore.context().applicationContext
            context.contentResolver.notifyChange(invalidationUri, null)
        } catch (_: Exception) {}
    }

    private fun checkForExternalChanges() {
        if (disposed.get()) return
        val db = synchronized(lock) {
            try { requireDatabase() } catch (_: Exception) { null }
        } ?: return

        val currentDataVersion = try {
            db.rawQuery("PRAGMA data_version", null).use { cursor ->
                if (cursor.moveToNext()) cursor.getLong(0) else return
            }
        } catch (_: Exception) { return }

        if (currentDataVersion == lastSeenDataVersion.get()) {
            return
        }
        lastSeenDataVersion.set(currentDataVersion)

        val changedTables = HashSet<String>()
        var wildcardChanged = false

        try {
            db.rawQuery("SELECT table_name, version FROM _nexa_table_versions", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0)
                    val version = cursor.getLong(1)
                    val prev = knownTableVersions[table] ?: 0L
                    if (version > prev) {
                        knownTableVersions[table] = version
                        if (table == "*") {
                            wildcardChanged = true
                        } else {
                            changedTables.add(table)
                        }
                    }
                }
            }
        } catch (_: Exception) { return }

        if (wildcardChanged) {
            SQLiteInvalidationCenter.invalidate(invalidationKey, null)
        } else if (changedTables.isNotEmpty()) {
            SQLiteInvalidationCenter.invalidate(invalidationKey, changedTables)
        }
    }

    private fun requestWidgetRefreshIfNeeded(rowsAffected: Long) {
        if (sharedWithWidgets && rowsAffected > 0L) {
            NexaRuntimeCore.requestWidgetRefresh()
        }
    }

    private fun executeLocked(
        db: PlatformSQLiteDatabase,
        sql: String,
        parameters: List<Value>,
    ): ExecutionResult {
        val statement = try {
            db.compileStatement(sql)
        } catch (error: Exception) {
            throw Failure.prepareFailed(error.message ?: "Could not prepare statement")
        }
        try {
            bindAll(statement, parameters, SQLiteParameterScanner.count(sql))
            return when (SQLiteParameterScanner.statementKind(sql)) {
                SQLiteStatementKind.INSERT -> {
                    val rowId = statement.executeInsert()
                    ExecutionResult(if (rowId >= 0L) 1 else 0, rowId.coerceAtLeast(0L))
                }
                SQLiteStatementKind.MUTATION -> ExecutionResult(
                    statement.executeUpdateDelete().coerceAtLeast(0).toLong(),
                    0,
                )
                SQLiteStatementKind.SCHEMA -> {
                    statement.execute()
                    ExecutionResult(0, 0)
                }
                SQLiteStatementKind.QUERY -> throw Failure.executeFailed(
                    "This statement returns rows; use query instead.",
                )
                SQLiteStatementKind.OTHER -> throw Failure.executeFailed(
                    "Unsupported SQL statement for execute.",
                )
            }
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            throw Failure.executeFailed(error.message ?: "Statement execution failed")
        } finally {
            statement.close()
        }
    }

    private fun <T> queryLocked(
        db: PlatformSQLiteDatabase,
        sql: String,
        parameters: List<Value>,
        mapRow: NexaRowMapper<Value, T, Failure>,
    ): List<T> {
        val parameterCount = SQLiteParameterScanner.count(sql)
        val cursor = try {
            db.rawQueryWithFactory(
                { _, driver, editTable, query ->
                    val actualQuery = query ?: throw IllegalStateException("Missing SQLite query")
                    bindAll(actualQuery, parameters, parameterCount)
                    SQLiteCursor(
                        driver ?: throw IllegalStateException("Missing SQLite cursor driver"),
                        editTable,
                        actualQuery,
                    )
                },
                sql,
                null,
                "",
            )
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            throw Failure.queryFailed(error.message ?: "Query failed")
        }

        cursor.use { cursor ->
            val columnNames = cursor.columnNames.toList()
            val decodeRow = try {
                mapRow(columnNames) { message -> Failure.queryFailed(message) }
            } catch (error: Failure) {
                throw error
            } catch (error: Exception) {
                throw Failure.invalidValue("Could not map query columns: ${error.message ?: "invalid columns"}")
            }
            val rows = ArrayList<T>(16)
            val rowReader = sqliteRowReader(cursor)
            while (cursor.moveToNext()) {
                try {
                    rows += decodeRow(rowReader)
                } catch (error: Failure) {
                    throw error
                } catch (error: Exception) {
                    throw Failure.invalidValue("Could not map query row: ${error.message ?: "invalid value"}")
                }
            }
            return rows
        }
    }

    private fun queryRawLocked(
        db: PlatformSQLiteDatabase,
        sql: String,
        parameters: List<Value>,
    ): QueryResult {
        val parameterCount = SQLiteParameterScanner.count(sql)
        val cursor = try {
            db.rawQueryWithFactory(
                { _, driver, editTable, query ->
                    val actualQuery = query ?: throw IllegalStateException("Missing SQLite query")
                    bindAll(actualQuery, parameters, parameterCount)
                    SQLiteCursor(
                        driver ?: throw IllegalStateException("Missing SQLite cursor driver"),
                        editTable,
                        actualQuery,
                    )
                },
                sql,
                null,
                "",
            )
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            throw Failure.queryFailed(error.message ?: "Query failed")
        }

        cursor.use {
            val columnNames = it.columnNames.toList()
            val rows = ArrayList<List<Value>>()
            while (it.moveToNext()) {
                val row = ArrayList<Value>(columnNames.size)
                for (index in columnNames.indices) row += readValue(it, index)
                rows += row
            }
            return QueryResult(columnNames, rows)
        }
    }

    private fun bindAll(
        program: SQLiteProgram,
        parameters: List<Value>,
        expectedCount: Int,
    ) {
        if (parameters.size != expectedCount) {
            throw Failure.bindFailed(
                "Statement expects $expectedCount value(s), received ${parameters.size}.",
            )
        }
        parameters.forEachIndexed { index, value ->
            try {
                when (value) {
                    Value.nullValue -> {
                        program.bindNull(index + 1)
                    }
                    is Value.boolean -> {
                        program.bindLong(index + 1, if (value.value) 1L else 0L)
                    }
                    is Value.int32 -> {
                        program.bindLong(index + 1, value.value.toLong())
                    }
                    is Value.int64 -> {
                        program.bindLong(index + 1, value.value)
                    }
                    is Value.float64 -> {
                        program.bindDouble(index + 1, value.value)
                    }
                    is Value.text -> {
                        program.bindString(index + 1, value.value)
                    }
                    is Value.blob -> {
                        program.bindBlob(index + 1, value.value)
                    }
                }
            } catch (error: Failure) {
                throw error
            } catch (error: Exception) {
                throw Failure.bindFailed(error.message ?: "Could not bind parameter ${index + 1}")
            }
        }
    }

    private fun readValue(cursor: Cursor, index: Int): Value = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> Value.nullValue
        Cursor.FIELD_TYPE_INTEGER -> Value.int64(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> Value.float64(cursor.getDouble(index))
        Cursor.FIELD_TYPE_BLOB -> Value.blob(cursor.getBlob(index))
        else -> Value.text(cursor.getString(index))
    }

    private fun sqliteRowReader(cursor: Cursor): NexaRowReader<Value> = NexaRowReader(
        value = { index -> readValue(cursor, index) },
        boolean = { index, column -> readBoolean(cursor, index, column) },
        optionalBoolean = { index, column -> readOptionalBoolean(cursor, index, column) },
        integer = { index, column -> readInteger(cursor, index, column) },
        optionalInteger = { index, column -> readOptionalInteger(cursor, index, column) },
        decimal = { index, column -> readDecimal(cursor, index, column) },
        optionalDecimal = { index, column -> readOptionalDecimal(cursor, index, column) },
        text = { index, column -> readText(cursor, index, column) },
        optionalText = { index, column -> readOptionalText(cursor, index, column) },
        bytes = { index, column -> readBytes(cursor, index, column) },
        optionalBytes = { index, column -> readOptionalBytes(cursor, index, column) },
    )

    private fun invalidColumn(column: String): Failure =
        Failure.invalidValue("Column `$column` has an incompatible SQLite value.")

    private fun readBoolean(cursor: Cursor, index: Int, column: String): Boolean {
        val value = readInteger(cursor, index, column)
        if (value != 0L && value != 1L) throw invalidColumn(column)
        return value == 1L
    }

    private fun readOptionalBoolean(cursor: Cursor, index: Int, column: String): Boolean? =
        if (cursor.getType(index) == Cursor.FIELD_TYPE_NULL) null else readBoolean(cursor, index, column)

    private fun readInteger(cursor: Cursor, index: Int, column: String): Long {
        if (cursor.getType(index) != Cursor.FIELD_TYPE_INTEGER) throw invalidColumn(column)
        return cursor.getLong(index)
    }

    private fun readOptionalInteger(cursor: Cursor, index: Int, column: String): Long? =
        if (cursor.getType(index) == Cursor.FIELD_TYPE_NULL) null else readInteger(cursor, index, column)

    private fun readDecimal(cursor: Cursor, index: Int, column: String): Double =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toDouble()
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            else -> throw invalidColumn(column)
        }

    private fun readOptionalDecimal(cursor: Cursor, index: Int, column: String): Double? =
        if (cursor.getType(index) == Cursor.FIELD_TYPE_NULL) null else readDecimal(cursor, index, column)

    private fun readText(cursor: Cursor, index: Int, column: String): String {
        if (cursor.getType(index) != Cursor.FIELD_TYPE_STRING) throw invalidColumn(column)
        return cursor.getString(index)
    }

    private fun readOptionalText(cursor: Cursor, index: Int, column: String): String? =
        if (cursor.getType(index) == Cursor.FIELD_TYPE_NULL) null else readText(cursor, index, column)

    private fun readBytes(cursor: Cursor, index: Int, column: String): ByteArray {
        if (cursor.getType(index) != Cursor.FIELD_TYPE_BLOB) throw invalidColumn(column)
        return cursor.getBlob(index)
    }

    private fun readOptionalBytes(cursor: Cursor, index: Int, column: String): ByteArray? =
        if (cursor.getType(index) == Cursor.FIELD_TYPE_NULL) null else readBytes(cursor, index, column)

    private companion object {
        val DATABASE_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** One event handle per observer; callbacks are coalesced onto the UI thread. */
public class InvalidationSubscriptionImpl() : InvalidationSubscriptionSpec {
    @Volatile
    private var invalidationKey: String? = null
    @Volatile
    private var observedTables: Set<String>? = null
    private val disposed = AtomicBoolean(false)
    private val deliveryPending = AtomicBoolean(false)

    @Volatile
    override var onInvalidated: (() -> Unit)? = null

    internal constructor(invalidationKey: String, tables: Set<String>? = null) : this() {
        attach(invalidationKey, tables, replaceTables = true)
    }

    internal fun attach(key: String, tables: Set<String>? = null, replaceTables: Boolean = false) {
        invalidationKey?.let { SQLiteInvalidationCenter.remove(it, this) }
        invalidationKey = key
        if (replaceTables) observedTables = tables
        deliveryPending.set(false)
        disposed.set(false)
        SQLiteInvalidationCenter.add(key, this)
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            onInvalidated = null
            invalidationKey?.let { SQLiteInvalidationCenter.remove(it, this) }
            invalidationKey = null
        }
    }

    internal fun postInvalidation() {
        if (disposed.get() || !deliveryPending.compareAndSet(false, true)) return
        mainHandler.post {
            deliveryPending.set(false)
            if (!disposed.get()) onInvalidated?.invoke()
        }
    }

    internal fun isActive(): Boolean = !disposed.get()

    internal fun matches(changedTables: Set<String>?): Boolean {
        val observed = observedTables ?: return true
        val changed = changedTables ?: return true
        return observed.any(changed::contains)
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}

/** Weak subscriptions prevent a long-lived database from retaining screens. */
private object SQLiteInvalidationCenter {
    private val lock = Any()
    private val subscriptions = HashMap<String, MutableList<WeakReference<InvalidationSubscriptionImpl>>>()

    fun observe(key: String, tables: Set<String>? = null): InvalidationSubscriptionImpl {
        val subscription = InvalidationSubscriptionImpl(key, tables)
        return subscription
    }

    fun add(key: String, subscription: InvalidationSubscriptionImpl) {
        synchronized(lock) {
            subscriptions.getOrPut(key, ::ArrayList).add(WeakReference(subscription))
        }
    }

    fun remove(key: String, subscription: InvalidationSubscriptionImpl) {
        synchronized(lock) {
            val entries = subscriptions[key] ?: return
            entries.removeAll { reference -> reference.get().let { it == null || it === subscription } }
            if (entries.isEmpty()) subscriptions.remove(key)
        }
    }

    fun invalidate(key: String, changedTables: Set<String>? = null) {
        val observers = synchronized(lock) {
            val entries = subscriptions[key] ?: return
            entries.removeAll { reference -> reference.get()?.isActive() != true }
            if (entries.isEmpty()) {
                subscriptions.remove(key)
                emptyList()
            } else {
                entries.mapNotNull(WeakReference<InvalidationSubscriptionImpl>::get)
            }
        }
        observers.asSequence()
            .filter { it.matches(changedTables) }
            .forEach(InvalidationSubscriptionImpl::postInvalidation)
    }
}

/** Match SQLite's ASCII-only case-insensitive identifier comparison. */
private fun sqliteTableKey(table: String): String = buildString(table.length) {
    table.forEach { character ->
        append(if (character in 'A'..'Z') character + ('a' - 'A') else character)
    }
}
