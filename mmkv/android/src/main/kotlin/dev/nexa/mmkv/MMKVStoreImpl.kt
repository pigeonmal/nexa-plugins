package dev.nexa.mmkv

import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import dev.nexa.core.NexaRuntimeCore
import dev.nexa.core.NexaValueReadResult
import dev.nexa.core.NexaValueReader
import dev.nexa.core.NexaValueWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * One MMKV store, backed by the memory-mapped file MMKV opened for
 * [instanceID].
 *
 * MMKV is synchronous and thread-safe, so every method stays on the calling
 * thread's coroutine dispatcher. Nexa does not silently move plugin work to a
 * background dispatcher: a caller that blocks the main thread should say so.
 */
public class MMKVStoreImpl : MMKVStoreSpec {
    override val instanceID: String
    override val isMultiProcess: Boolean
    override val isEncrypted: Boolean
        get() = cryptKey != null
    override val rootDirectory: String
    override val version: String
    override val pageSize: Long

    @Volatile
    override var onValueChanged: ((String) -> Unit)? = null
    @Volatile
    override var onContentChanged: (() -> Unit)? = null

    @Volatile
    private var cryptKey: String?
    @Volatile
    private var compareBeforeSetForUnencryptedStore = true
    private val store: MMKV?
    @Volatile
    private var observedKeys: MutableSet<String>? = null
    private var isDisposed = false

    public constructor(instanceID: String, cryptKey: String?, multiProcess: Boolean) {
        this.instanceID = instanceID
        this.cryptKey = cryptKey
        this.isMultiProcess = multiProcess

        // MMKV.initialize loads its native library. Registering the handler
        // through initialization enables content notifications without a
        // second global registration call. Match iOS's quiet initialization;
        // MMKV's default INFO logger emits native logcat messages during
        // writes, adding work to the hot path.
        val rootDir = MMKV.initialize(
            NexaRuntimeCore.context().applicationContext,
            null,
            null,
            MMKVLogLevel.LevelNone,
            MMKVStoreObserver,
        )
        this.rootDirectory = rootDir
        this.version = MMKV.version()
        this.pageSize = MMKV.pageSize().toLong()

        val mode = if (multiProcess) {
            MMKV.MULTI_PROCESS_MODE
        } else {
            MMKV.SINGLE_PROCESS_MODE
        }
        this.store = try {
            MMKV.mmkvWithID(instanceID, mode, this.cryptKey)
        } catch (error: RuntimeException) {
            // A store that cannot be mapped reads as absent rather than
            // taking the process down; every operation below reports failure.
            null
        }
        if (this.cryptKey == null) {
            this.store?.enableCompareBeforeSet()
        }
        if (multiProcess) {
            // Outer-process callbacks only apply to stores using MMKV's shared
            // process mode. Keep ordinary single-process stores out of the
            // process-wide observer registry.
            MMKVStoreObserver.register(this)
        }
    }

    // MARK: - Scalars

    override fun setBool(key: String, value: Boolean): Boolean =
        write(key) { it.encode(key, value) }

    override fun getBool(key: String): Boolean? = store?.decodeBool(key)

    override fun setInt32(key: String, value: Int): Boolean =
        write(key) { it.encode(key, value) }

    override fun getInt32(key: String): Int? = store?.decodeInt(key)

    override fun setInt64(key: String, value: Long): Boolean =
        write(key) { it.encode(key, value) }

    override fun getInt64(key: String): Long? = store?.decodeLong(key)

    override fun setUInt32(key: String, value: UInt): Boolean =
        write(key) { it.encode(key, value.toInt()) }

    override fun getUInt32(key: String): UInt? = store?.decodeInt(key)?.toUInt()

    override fun setUInt64(key: String, value: ULong): Boolean =
        write(key) { it.encode(key, value.toLong()) }

    override fun getUInt64(key: String): ULong? = store?.decodeLong(key)?.toULong()

    override fun setFloat32(key: String, value: Float): Boolean =
        write(key) { it.encode(key, value) }

    override fun getFloat32(key: String): Float? = store?.decodeFloat(key)

    override fun setFloat64(key: String, value: Double): Boolean =
        write(key) { it.encode(key, value) }

    override fun getFloat64(key: String): Double? = store?.decodeDouble(key)

    override fun setString(key: String, value: String): Boolean =
        write(key) { it.encode(key, value) }

    override fun getString(key: String): String? = store?.decodeString(key)

    override fun setBuffer(key: String, value: ByteArray): Boolean =
        write(key) { it.encode(key, value) }

    override fun getBuffer(key: String): ByteArray? = store?.decodeBytes(key)

    // MARK: - Generic values
    //
    // One body per shape, generic over the bound type. The call site supplies
    // the codec the compiler generated for its concrete type, so nothing here
    // reflects, boxes, or re-encodes through an intermediate map.

    override fun <T> setObject(
        key: String,
        value: T,
        encode0: (T, NexaValueWriter) -> Unit,
    ): Boolean {
        val writer = NexaValueWriter()
        encode0(value, writer)
        return setBuffer(key, writer.toByteArray())
    }

    override fun <T> getObject(key: String, decode0: (NexaValueReader) -> NexaValueReadResult<T>): T? {
        val data = getBuffer(key) ?: return null
        return decode0(NexaValueReader(data)).valueOrNull()
    }

    override fun <T> setList(
        key: String,
        values: List<T>,
        encode0: (List<T>, NexaValueWriter) -> Unit,
    ): Boolean {
        val writer = NexaValueWriter()
        encode0(values, writer)
        return setBuffer(key, writer.toByteArray())
    }

    override fun <T> getList(key: String, decode0: (NexaValueReader) -> NexaValueReadResult<List<T>>): List<T>? {
        val data = getBuffer(key) ?: return null
        return decode0(NexaValueReader(data)).valueOrNull()
    }

    override fun <T> setSet(
        key: String,
        values: Set<T>,
        encode0: (Set<T>, NexaValueWriter) -> Unit,
    ): Boolean {
        val writer = NexaValueWriter()
        encode0(values, writer)
        return setBuffer(key, writer.toByteArray())
    }

    override fun <T> getSet(key: String, decode0: (NexaValueReader) -> NexaValueReadResult<Set<T>>): Set<T>? {
        val data = getBuffer(key) ?: return null
        return decode0(NexaValueReader(data)).valueOrNull()
    }

    override fun <K, V> setMap(
        key: String,
        values: Map<K, V>,
        encode0: (Map<K, V>, NexaValueWriter) -> Unit,
    ): Boolean {
        val writer = NexaValueWriter()
        encode0(values, writer)
        return setBuffer(key, writer.toByteArray())
    }

    override fun <K, V> getMap(key: String, decode0: (NexaValueReader) -> NexaValueReadResult<Map<K, V>>): Map<K, V>? {
        val data = getBuffer(key) ?: return null
        return decode0(NexaValueReader(data)).valueOrNull()
    }

    // MARK: - Key space

    override fun contains(key: String): Boolean = store?.containsKey(key) ?: false

    override fun getAllKeys(): List<String> = allKeys()

    override fun getAllKeysWithPrefix(prefix: String): List<String> =
        allKeys().filter { it.startsWith(prefix) }

    override fun getAllKeysWithSuffix(suffix: String): List<String> =
        allKeys().filter { it.endsWith(suffix) }

    override fun getAllKeysMatching(prefix: String, suffix: String): List<String> =
        allKeys().filter { it.startsWith(prefix) && it.endsWith(suffix) }

    override fun remove(key: String): Boolean {
        val store = store ?: return false
        if (!store.containsKey(key)) {
            return false
        }
        store.removeValueForKey(key)
        notify(key)
        return true
    }

    override fun removeMany(keys: List<String>): Int {
        val store = store ?: return 0
        val present = keys.filter { store.containsKey(it) }
        if (present.isEmpty()) {
            return 0
        }
        store.removeValuesForKeys(present.toTypedArray())
        present.forEach { notify(it) }
        return present.size
    }

    override fun clearAll(): Boolean {
        val store = store ?: return false
        store.clearAll()
        notifyObservedKeys()
        return true
    }

    override fun clearAllKeepingSpace(): Boolean {
        val store = store ?: return false
        store.clearAllWithKeepingSpace()
        notifyObservedKeys()
        return true
    }

    override fun trim(): Long {
        val store = store ?: return 0
        val before = store.actualSize()
        store.trim()
        return (before - store.actualSize()).coerceAtLeast(0)
    }

    // MARK: - Introspection and maintenance

    override fun count(): Long = store?.count() ?: 0

    override fun totalSize(): Long = store?.totalSize() ?: 0

    override fun actualSize(): Long = store?.actualSize() ?: 0

    override fun valueSize(key: String): Long {
        val store = store ?: return 0
        if (!store.containsKey(key)) {
            return 0
        }
        return store.getValueActualSize(key).toLong()
    }

    override fun stats(): MMKVStats =
        MMKVStats(
            keyCount = count(),
            totalSize = totalSize(),
            actualSize = actualSize(),
            pageSize = pageSize,
        )

    override fun sync() {
        store?.sync()
    }

    override fun asyncFlush() {
        store?.async()
    }

    override fun enableCompareBeforeSet(): Boolean {
        val store = store ?: return false
        if (isEncrypted) {
            return false
        }
        store.enableCompareBeforeSet()
        val enabled = store.isCompareBeforeSetEnabled
        if (enabled) {
            compareBeforeSetForUnencryptedStore = true
        }
        return enabled
    }

    override fun disableCompareBeforeSet(): Boolean {
        val store = store ?: return false
        if (isEncrypted) {
            compareBeforeSetForUnencryptedStore = false
            return true
        }
        store.disableCompareBeforeSet()
        val disabled = !store.isCompareBeforeSetEnabled
        if (disabled) {
            compareBeforeSetForUnencryptedStore = false
        }
        return disabled
    }

    override fun rekey(cryptKey: String?): Boolean {
        val store = store ?: return false
        val wasEncrypted = isEncrypted
        if (cryptKey != null && !wasEncrypted) {
            store.disableCompareBeforeSet()
        }

        if (!store.reKey(cryptKey)) {
            if (!wasEncrypted && compareBeforeSetForUnencryptedStore) {
                store.enableCompareBeforeSet()
            }
            return false
        }

        this.cryptKey = cryptKey
        if (cryptKey == null && compareBeforeSetForUnencryptedStore) {
            store.enableCompareBeforeSet()
        }
        return true
    }

    override fun checkContentChanged() {
        store?.checkContentChangedByOuterProcess()
    }

    override fun storageExists(): Boolean = MMKV.checkExist(instanceID)

    override fun backup(directory: String): Boolean = MMKV.backupOneToDirectory(instanceID, directory, null)

    override fun restore(directory: String): Boolean = MMKV.restoreOneMMKVFromDirectory(instanceID, directory, null)

    override fun removeStorage(): Boolean = MMKV.removeStorage(instanceID, null)

    // MARK: - Change listeners

    override fun observe(key: String) {
        observedKeySet(create = true)?.add(key)
    }

    override fun unobserve(key: String) {
        observedKeySet(create = false)?.remove(key)
    }

    override fun unobserveAll() {
        observedKeySet(create = false)?.clear()
    }

    override fun dispose() {
        if (isDisposed) {
            return
        }
        isDisposed = true
        if (isMultiProcess) {
            MMKVStoreObserver.unregister(this)
        }
        onValueChanged = null
        onContentChanged = null
        observedKeySet(create = false)?.clear()
        store?.close()
    }

    // MARK: - Internals

    /**
     * Performs a write and reports the key when it was observed. The listener
     * runs synchronously on the calling thread.
     */
    private inline fun write(key: String, body: (MMKV) -> Boolean): Boolean {
        val store = store ?: return false
        if (isDisposed) {
            return false
        }
        val written = body(store)
        if (written) {
            notify(key)
        }
        return written
    }

    private fun allKeys(): List<String> =
        store?.allKeys()?.filterIsInstance<String>()?.sorted() ?: emptyList()

    /** Avoid allocating a concurrent set for stores that never observe keys. */
    private fun observedKeySet(create: Boolean): MutableSet<String>? {
        observedKeys?.let { return it }
        if (!create) {
            return null
        }
        return synchronized(this) {
            observedKeys ?: java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also {
                observedKeys = it
            }
        }
    }

    private fun notify(key: String) {
        // Most stores never install a listener. Avoid hashing every written
        // key in the concurrent set on that hot path.
        val callback = onValueChanged ?: return
        if (observedKeySet(create = false)?.contains(key) != true) {
            return
        }
        callback.invoke(key)
    }

    private fun notifyObservedKeys() {
        val callback = onValueChanged ?: return
        observedKeySet(create = false)?.sorted()?.forEach { key -> callback.invoke(key) }
    }

    /**
     * Reports the observed keys of a store another process wrote to. MMKV
     * tells us the instance id, never the key, so every observed key of that
     * store is reported.
     */
    internal fun reportOuterProcessChange() {
        if (observedKeySet(create = false)?.isNotEmpty() != true) {
            return
        }
        notifyObservedKeys()
        onContentChanged?.invoke()
    }
}

/**
 * Routes MMKV's process-global notifications to the live stores. MMKV calls
 * back on its own thread, so the store callback is dispatched to the main
 * thread the way the generated iOS contract does.
 */
internal object MMKVStoreObserver : MMKVHandler {
    private val stores = ConcurrentHashMap<String, MMKVStoreImpl>()

    fun register(store: MMKVStoreImpl) {
        stores[store.instanceID] = store
    }

    fun unregister(store: MMKVStoreImpl) {
        stores.remove(store.instanceID, store)
    }

    override fun onContentChangedByOuterProcess(mmapID: String) {
        val store = stores[mmapID] ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            store.reportOuterProcessChange()
        }
    }

    /**
     * A file that fails its CRC or length check is recovered rather than
     * discarded. MMKV's own default deletes the file, which for a key-value
     * store means silently losing everything a user saved; recovering keeps
     * what is still readable and reports the fault in the log below.
     */
    override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic =
        MMKVRecoverStrategic.OnErrorRecover

    override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic =
        MMKVRecoverStrategic.OnErrorRecover

    /**
     * MMKV's native log is routed into logcat, where a crash report picks it
     * up. There is no file to write, so redirecting to a file is declined.
     */
    override fun wantLogRedirecting(): Boolean = false

    override fun wantContentChangeNotification(): Boolean = true

    override fun mmkvLog(
        level: MMKVLogLevel,
        file: String,
        line: Int,
        function: String,
        message: String,
    ) {
        val tag = "MMKV"
        val text = "$file:$line $function() $message"
        when (level) {
            MMKVLogLevel.LevelDebug -> android.util.Log.d(tag, text)
            MMKVLogLevel.LevelInfo -> android.util.Log.i(tag, text)
            MMKVLogLevel.LevelWarning -> android.util.Log.w(tag, text)
            MMKVLogLevel.LevelError -> android.util.Log.e(tag, text)
            else -> android.util.Log.w(tag, text)
        }
    }
}
