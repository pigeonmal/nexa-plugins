import Foundation
import MMKV

/// One MMKV store, backed by the memory-mapped file MMKV opened for
/// `instanceID`.
///
/// MMKV is synchronous and thread-safe: a read walks the mapped page and a
/// write appends to it, so every method here stays on the calling thread. The
/// generated contract is main-actor isolated, which means `.nx` code calls
/// these on the main actor; MMKV does not need a different queue, and moving
/// to one would only hide a caller that blocks.
@MainActor
public final class MMKVStoreImpl: MMKVStoreSpec {
    public let instanceID: String
    public var isMultiProcess: Bool { mode == .multiProcess }
    public var isEncrypted: Bool { cryptKey != nil }
    public var rootDirectory: String { MMKV.mmkvBasePath() }
    public var version: String { MMKV.version() }
    public var pageSize: Int64 { Int64(MMKV.pageSize()) }

    public var onValueChanged: ((String) -> Void)?
    public var onContentChanged: (() -> Void)?

    private let mode: MMKVMode
    private var cryptKey: Data?
    private var compareBeforeSetForUnencryptedStore = true
    private let store: MMKV?
    private var observedKeys: Set<String> = []
    private var isDisposed = false

    public init(_ instanceID: String, _ cryptKey: String?, _ multiProcess: Bool) {
        self.instanceID = instanceID
        self.cryptKey = cryptKey.map { Data($0.utf8) }
        self.mode = multiProcess ? .multiProcess : .singleProcess
        // MMKV must be initialized once per process; this call is idempotent
        // and installs the handler that reports writes from other processes.
        MMKV.initialize(rootDir: nil, logLevel: .none, handler: MMKVStoreObserver.shared)
        self.store = MMKV(
            mmapID: instanceID,
            cryptKey: self.cryptKey,
            aes256: false,
            mode: self.mode
        )
        if self.cryptKey == nil {
            self.store?.enableCompareBeforeSet()
        }
        if multiProcess {
            MMKVStoreObserver.shared.register(self)
        }
    }

    // MARK: - Scalars

    public func setBool(_ key: String, _ value: Bool) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getBool(_ key: String) -> Bool? {
        store?.bool(forKey: key)
    }

    public func setInt32(_ key: String, _ value: Int32) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getInt32(_ key: String) -> Int32? {
        store?.int32(forKey: key)
    }

    public func setInt64(_ key: String, _ value: Int64) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getInt64(_ key: String) -> Int64? {
        store?.int64(forKey: key)
    }

    public func setUInt32(_ key: String, _ value: UInt32) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getUInt32(_ key: String) -> UInt32? {
        store?.uint32(forKey: key)
    }

    public func setUInt64(_ key: String, _ value: UInt64) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getUInt64(_ key: String) -> UInt64? {
        store?.uint64(forKey: key)
    }

    public func setFloat32(_ key: String, _ value: Float) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getFloat32(_ key: String) -> Float? {
        store?.float(forKey: key)
    }

    public func setFloat64(_ key: String, _ value: Double) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getFloat64(_ key: String) -> Double? {
        store?.double(forKey: key)
    }

    public func setString(_ key: String, _ value: String) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getString(_ key: String) -> String? {
        store?.string(forKey: key)
    }

    public func setBuffer(_ key: String, _ value: Data) -> Bool {
        write(key) { store in store.set(value, forKey: key) }
    }

    public func getBuffer(_ key: String) -> Data? {
        store?.data(forKey: key)
    }

    // MARK: - Generic values
    //
    // One body per shape, generic over the bound type. The call site supplies
    // the codec the compiler generated for its concrete type, so nothing here
    // reflects, boxes, or re-encodes through an intermediate dictionary.

    public func setObject<T>(_ key: String, _ value: T, _ encode: (T, NexaValueWriter) -> Void) -> Bool {
        let writer = NexaValueWriter()
        encode(value, writer)
        return setBuffer(key, writer.data)
    }

    public func getObject<T>(_ key: String, _ decode: (NexaValueReader) -> T?) -> T? {
        guard let data = getBuffer(key) else { return nil }
        return decode(NexaValueReader(data))
    }

    public func setList<T>(_ key: String, _ values: [T], _ encode: ([T], NexaValueWriter) -> Void) -> Bool {
        let writer = NexaValueWriter()
        encode(values, writer)
        return setBuffer(key, writer.data)
    }

    public func getList<T>(_ key: String, _ decode: (NexaValueReader) -> [T]?) -> [T]? {
        guard let data = getBuffer(key) else { return nil }
        return decode(NexaValueReader(data))
    }

    public func setSet<T>(_ key: String, _ values: Set<T>, _ encode: (Set<T>, NexaValueWriter) -> Void) -> Bool {
        let writer = NexaValueWriter()
        encode(values, writer)
        return setBuffer(key, writer.data)
    }

    public func getSet<T>(_ key: String, _ decode: (NexaValueReader) -> Set<T>?) -> Set<T>? {
        guard let data = getBuffer(key) else { return nil }
        return decode(NexaValueReader(data))
    }

    public func setMap<K, V>(_ key: String, _ values: [K: V], _ encode: ([K: V], NexaValueWriter) -> Void) -> Bool {
        let writer = NexaValueWriter()
        encode(values, writer)
        return setBuffer(key, writer.data)
    }

    public func getMap<K, V>(_ key: String, _ decode: (NexaValueReader) -> [K: V]?) -> [K: V]? {
        guard let data = getBuffer(key) else { return nil }
        return decode(NexaValueReader(data))
    }

    // MARK: - Key space

    public func contains(_ key: String) -> Bool {
        store?.contains(key: key) ?? false
    }

    public func getAllKeys() -> Array<String> {
        allKeys()
    }

    public func getAllKeysWithPrefix(_ prefix: String) -> Array<String> {
        allKeys().filter { $0.hasPrefix(prefix) }
    }

    public func getAllKeysWithSuffix(_ suffix: String) -> Array<String> {
        allKeys().filter { $0.hasSuffix(suffix) }
    }

    public func getAllKeysMatching(_ prefix: String, _ suffix: String) -> Array<String> {
        allKeys().filter { $0.hasPrefix(prefix) && $0.hasSuffix(suffix) }
    }

    public func remove(_ key: String) -> Bool {
        guard let store, store.contains(key: key) else { return false }
        store.removeValue(forKey: key)
        notify(key)
        return true
    }

    public func removeMany(_ keys: Array<String>) -> Int32 {
        guard let store else { return 0 }
        let present = keys.filter { store.contains(key: $0) }
        guard !present.isEmpty else { return 0 }
        store.removeValues(forKeys: present)
        present.forEach { notify($0) }
        return Int32(present.count)
    }

    public func clearAll() -> Bool {
        guard let store else { return false }
        store.clearAll()
        notifyAll()
        return true
    }

    public func clearAllKeepingSpace() -> Bool {
        guard let store else { return false }
        store.clearAllWithKeepingSpace()
        notifyAll()
        return true
    }

    public func trim() -> Int64 {
        guard let store else { return 0 }
        let before = store.actualSize()
        store.trim()
        return Int64(max(0, before - store.actualSize()))
    }

    // MARK: - Introspection and maintenance

    public func count() -> Int64 {
        guard let store else { return 0 }
        return Int64(store.count())
    }

    public func totalSize() -> Int64 {
        guard let store else { return 0 }
        return Int64(store.totalSize())
    }

    public func actualSize() -> Int64 {
        guard let store else { return 0 }
        return Int64(store.actualSize())
    }

    public func valueSize(_ key: String) -> Int64 {
        guard let store, store.contains(key: key) else { return 0 }
        return Int64(store.valueSize(forKey: key, actualSize: true))
    }

    public func stats() -> MMKVStats {
        MMKVStats(
            keyCount: count(),
            totalSize: totalSize(),
            actualSize: actualSize(),
            pageSize: pageSize
        )
    }

    public func sync() {
        store?.sync()
    }

    public func asyncFlush() {
        store?.async()
    }

    public func enableCompareBeforeSet() -> Bool {
        guard !isEncrypted, let store else { return false }
        let enabled = store.enableCompareBeforeSet()
        if enabled {
            compareBeforeSetForUnencryptedStore = true
        }
        return enabled
    }

    public func disableCompareBeforeSet() -> Bool {
        guard let store else { return false }
        if isEncrypted {
            compareBeforeSetForUnencryptedStore = false
            return true
        }
        let disabled = store.disableCompareBeforeSet()
        if disabled {
            compareBeforeSetForUnencryptedStore = false
        }
        return disabled
    }

    public func rekey(_ cryptKey: String?) -> Bool {
        guard let store else { return false }
        let wasEncrypted = isEncrypted
        if cryptKey != nil && !wasEncrypted {
            store.disableCompareBeforeSet()
        }

        guard store.reset(cryptKey: cryptKey.map({ Data($0.utf8) })) else {
            if !wasEncrypted && compareBeforeSetForUnencryptedStore {
                store.enableCompareBeforeSet()
            }
            return false
        }

        self.cryptKey = cryptKey.map { Data($0.utf8) }
        if cryptKey == nil && compareBeforeSetForUnencryptedStore {
            store.enableCompareBeforeSet()
        }
        return true
    }

    public func checkContentChanged() {
        store?.checkContentChanged()
    }

    public func storageExists() -> Bool {
        MMKV.checkExist(for: instanceID, rootPath: nil)
    }

    public func backup(_ directory: String) -> Bool {
        MMKV.backup(mmapID: instanceID, rootPath: nil, dstDir: directory)
    }

    public func restore(_ directory: String) -> Bool {
        MMKV.restore(mmapID: instanceID, rootPath: nil, srcDir: directory)
    }

    public func removeStorage() -> Bool {
        MMKV.removeStorage(for: instanceID, rootPath: nil)
    }

    // MARK: - Change listeners

    public func observe(_ key: String) {
        observedKeys.insert(key)
    }

    public func unobserve(_ key: String) {
        observedKeys.remove(key)
    }

    public func unobserveAll() {
        observedKeys.removeAll()
    }

    public func dispose() {
        guard !isDisposed else { return }
        isDisposed = true
        if isMultiProcess {
            MMKVStoreObserver.shared.unregister(self)
        }
        onValueChanged = nil
        onContentChanged = nil
        observedKeys.removeAll()
        store?.close()
    }

    // MARK: - Internals

    /// Performs a write and reports the key when it was observed. The
    /// listener runs synchronously on the calling thread, which is the main
    /// actor here.
    private func write(_ key: String, _ body: (MMKV) -> Bool) -> Bool {
        guard let store, !isDisposed else { return false }
        let written = body(store)
        if written {
            notify(key)
        }
        return written
    }

    private func allKeys() -> Array<String> {
        guard let store else { return [] }
        return (store.allKeys() as? [String] ?? []).sorted()
    }

    private func notify(_ key: String) {
        guard observedKeys.contains(key) else { return }
        onValueChanged?(key)
    }

    private func notifyAll() {
        observedKeys.sorted().forEach { key in
            onValueChanged?(key)
        }
    }

    /// Reports the observed keys of a store another process wrote to. MMKV
    /// tells us the instance id, never the key, so every observed key of that
    /// store is reported.
    fileprivate func reportOuterProcessChange() {
        guard !observedKeys.isEmpty else { return }
        notifyAll()
        onContentChanged?()
    }
}

/// Routes MMKV's process-global notifications to the live stores.
///
/// MMKV calls this on its own thread and the protocol is not actor-isolated,
/// so the observer stays off the main actor: it records which stores are live
/// under a lock, then hops to the main actor to call back into the
/// main-actor-isolated contract. The lock covers the lookup only; the store
/// itself stays on the main actor.
final class MMKVStoreObserver: NSObject, MMKVHandler, @unchecked Sendable {
    static let shared = MMKVStoreObserver()

    private let lock = NSLock()
    private var stores: [String: Weak<MMKVStoreImpl>] = [:]

    func register(_ store: MMKVStoreImpl) {
        lock.withLock {
            stores[store.instanceID] = Weak(store)
            stores = stores.filter { $0.value.value != nil }
        }
    }

    func unregister(_ store: MMKVStoreImpl) {
        _ = lock.withLock {
            stores.removeValue(forKey: store.instanceID)
        }
    }

    func onMMKVContentChange(_ mmapID: String) {
        let store = lock.withLock { stores[mmapID]?.value }
        guard let store else { return }
        Task { @MainActor in
            store.reportOuterProcessChange()
        }
    }
}

/// A weak reference in a dictionary of values.
private final class Weak<Value: AnyObject> {
    private(set) weak var value: Value?

    init(_ value: Value) {
        self.value = value
    }
}
