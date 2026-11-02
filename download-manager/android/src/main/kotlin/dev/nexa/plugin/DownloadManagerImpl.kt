package dev.nexa.plugin

import android.app.DownloadManager as PlatformDownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import dev.nexa.core.NexaRuntimeCore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

public class DownloadManagerImpl : DownloadManagerSpec {
    private val context: Context = NexaRuntimeCore.context().applicationContext
    private val platformManager: PlatformDownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? PlatformDownloadManager
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val hlsJobs = ConcurrentHashMap<String, Job>()
    private var disposed = false

    override var onProgress: ((String, Long, Long) -> Unit)? = null
    override var onCompleted: ((DownloadSnapshot) -> Unit)? = null
    override var onFailed: ((String, String) -> Unit)? = null

    override suspend fun start(id: String, url: String, fileName: String, headers: Map<String, String>): DownloadSnapshot {
        ensureActive()
        if (!isValidIdentifier(id)) throw DownloadManagerError.invalidIdentifier
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw DownloadManagerError.invalidURL
        }
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw DownloadManagerError.invalidIdentifier
        }
        val manager = platformManager ?: throw DownloadManagerError.destinationUnavailable

        var requestId = preferences.getLong(requestKey(id), INVALID_REQUEST_ID)
        if (requestId != INVALID_REQUEST_ID) {
            val existing = query(id, requestId)
            if (existing?.state == DownloadState.completed) return existing
            if (existing?.state == DownloadState.failed || existing?.state == DownloadState.cancelled) {
                manager.remove(requestId)
                preferences.edit().remove(requestKey(id)).apply()
                requestId = INVALID_REQUEST_ID
            }
        }

        if (requestId == INVALID_REQUEST_ID) {
            val request = PlatformDownloadManager.Request(uri)
                .setTitle(safeName)
                .setDescription("Mazica audio download")
                .setNotificationVisibility(PlatformDownloadManager.Request.VISIBILITY_HIDDEN)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, safeName)
            headers.forEach { (name, value) -> request.addRequestHeader(name, value) }
            requestId = manager.enqueue(request)
            preferences.edit()
                .putLong(requestKey(id), requestId)
                .putString(fileNameKey(id), safeName)
                .putString(stateKey(id), "downloading")
                .apply()
        }

        while (currentCoroutineContext().isActive) {
            val snapshot = query(id, requestId)
            if (snapshot == null) {
                throw DownloadManagerError.downloadFailed("Download task is no longer available")
            }
            onProgress?.invoke(id, snapshot.receivedBytes, snapshot.expectedBytes)
            when (snapshot.state) {
                DownloadState.completed -> {
                    saveSnapshot(snapshot)
                    onCompleted?.invoke(snapshot)
                    return snapshot
                }
                DownloadState.failed, DownloadState.cancelled -> {
                    saveSnapshot(snapshot)
                    val message = snapshot.error.ifBlank { "Download failed" }
                    onFailed?.invoke(id, message)
                    throw DownloadManagerError.downloadFailed(message)
                }
                DownloadState.queued, DownloadState.downloading -> delay(POLL_INTERVAL_MS)
            }
        }
        throw CancellationException("Download observation was cancelled")
    }

    override suspend fun startHls(
        id: String,
        url: String,
        packageName: String,
        headers: Map<String, String>,
        expectedBytes: Long?,
    ): DownloadSnapshot {
        ensureActive()
        if (!isValidIdentifier(id)) throw DownloadManagerError.invalidIdentifier
        val parsedUrl = runCatching { Uri.parse(url) }.getOrNull()
        if (parsedUrl == null || parsedUrl.scheme?.lowercase() !in setOf("http", "https")) {
            throw DownloadManagerError.invalidURL
        }
        val safeName = packageName.substringAfterLast('/').substringAfterLast('\\')
        if (safeName.isBlank() || safeName == "." || safeName == "..") {
            throw DownloadManagerError.invalidIdentifier
        }

        val saved = readSavedSnapshot(id)
        if (saved?.state == DownloadState.completed && File(saved.localPath).isFile) return saved
        val root = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir.resolve("downloads"))
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw DownloadManagerError.destinationUnavailable
        }
        val finalDirectory = File(root, "$safeName.hls")
        val stagingDirectory = File(root, ".$safeName.hls.tmp")
        val expectedHint = expectedBytes?.takeIf { it > 0L }
        val previousRequestID = preferences.getLong(requestKey(id), INVALID_REQUEST_ID)
        if (previousRequestID != INVALID_REQUEST_ID) {
            platformManager?.remove(previousRequestID)
            preferences.edit().remove(requestKey(id)).apply()
        }
        val activeJob = currentCoroutineContext().job
        val previousJob = hlsJobs.putIfAbsent(id, activeJob)
        if (previousJob != null && previousJob.isActive) {
            throw DownloadManagerError.downloadFailed("An HLS download with this identifier is already active")
        }
        hlsJobs[id] = activeJob

        try {
            stagingDirectory.deleteRecursively()
            if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) {
                throw DownloadManagerError.destinationUnavailable
            }
            preferences.edit()
                .putString(fileNameKey(id), safeName)
                .putString(stateKey(id), "downloading")
                .apply()
            val result = HlsPackageDownloader(context).download(url, stagingDirectory, headers) { received ->
                val expected = expectedHint?.let { maxOf(it, received) } ?: 0L
                onProgress?.invoke(id, received, expected)
                saveSnapshot(DownloadSnapshot(id, DownloadState.downloading, received, expected, "", ""))
            }
            currentCoroutineContext().ensureActive()
            ensureActive()
            if (finalDirectory.exists() && !finalDirectory.deleteRecursively()) {
                throw IllegalStateException("Unable to replace the existing HLS package")
            }
            android.system.Os.rename(stagingDirectory.absolutePath, finalDirectory.absolutePath)
            val localPath = File(finalDirectory, result.playlist.name).absolutePath
            val bytes = result.receivedBytes
            val snapshot = DownloadSnapshot(
                id,
                DownloadState.completed,
                bytes,
                maxOf(expectedHint ?: bytes, bytes),
                localPath,
                "",
            )
            saveSnapshot(snapshot)
            onCompleted?.invoke(snapshot)
            return snapshot
        } catch (error: CancellationException) {
            stagingDirectory.deleteRecursively()
            saveSnapshot(DownloadSnapshot(id, DownloadState.cancelled, 0L, expectedHint ?: 0L, "", "Download cancelled"))
            throw error
        } catch (error: DownloadManagerError) {
            stagingDirectory.deleteRecursively()
            val message = error.message ?: "HLS download failed"
            saveSnapshot(DownloadSnapshot(id, DownloadState.failed, 0L, expectedHint ?: 0L, "", message))
            onFailed?.invoke(id, message)
            throw error
        } catch (error: Throwable) {
            stagingDirectory.deleteRecursively()
            val message = error.message ?: "HLS download failed"
            saveSnapshot(DownloadSnapshot(id, DownloadState.failed, 0L, expectedHint ?: 0L, "", message))
            onFailed?.invoke(id, message)
            throw DownloadManagerError.downloadFailed(message)
        } finally {
            hlsJobs.remove(id, activeJob)
        }
    }

    override suspend fun status(id: String): DownloadSnapshot? {
        if (!isValidIdentifier(id)) throw DownloadManagerError.invalidIdentifier
        val requestId = preferences.getLong(requestKey(id), INVALID_REQUEST_ID)
        if (requestId != INVALID_REQUEST_ID) {
            query(id, requestId)?.let { return it }
        }
        return readSavedSnapshot(id)
    }

    override suspend fun completedPath(id: String): String? {
        val snapshot = status(id) ?: return null
        if (snapshot.state != DownloadState.completed) return null
        return snapshot.localPath.takeIf { path -> path.isNotBlank() && File(path).isFile }
    }

    override suspend fun adoptExistingDownloads(ids: List<String>) {
        ensureActive()
        val requested = ids.filter(::isValidIdentifier).toSet()
        if (requested.isEmpty()) return

        val adopted = linkedMapOf<String, DownloadSnapshot>()
        for (id in requested) {
            val saved = readSavedSnapshot(id)
            if (saved?.state == DownloadState.completed && File(saved.localPath).isFile) {
                adopted[id] = saved
            } else if (saved != null) {
                clearSavedSnapshot(id)
            }
        }
        if (adopted.size == requested.size) return

        val roots = listOfNotNull(
            context.filesDir,
            context.cacheDir,
            context.getExternalFilesDir(null),
        ).mapNotNull { directory ->
            runCatching { directory.canonicalFile }.getOrNull()
        }.distinctBy(File::getPath)
        val pending = requested - adopted.keys
        val visitedDirectories = hashSetOf<String>()
        val directories = java.util.ArrayDeque<Pair<File, Int>>()
        roots.forEach { root -> directories.addLast(root to 0) }
        var visitedFiles = 0

        while (directories.isNotEmpty() && pending.any { it !in adopted } && visitedFiles < SOURCE_SCAN_LIMIT) {
            val (directory, depth) = directories.removeFirst()
            val canonicalPath = runCatching { directory.canonicalPath }.getOrNull() ?: continue
            if (!visitedDirectories.add(canonicalPath)) continue
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (depth < SOURCE_SCAN_MAX_DEPTH && !child.name.startsWith(".")) {
                        directories.addLast(child to depth + 1)
                    }
                    continue
                }
                visitedFiles++
                if (!child.isFile || child.length() <= 0L) continue
                val id = child.nameWithoutExtension.takeIf { it in pending && it !in adopted } ?: continue
                val filePath = runCatching { child.canonicalPath }.getOrNull() ?: continue
                val snapshot = DownloadSnapshot(id, DownloadState.completed, child.length(), child.length(), filePath, "")
                preferences.edit().putString(fileNameKey(id), child.name).apply()
                saveSnapshot(snapshot)
                adopted[id] = snapshot
            }
        }
    }

    override fun cancel(id: String) {
        if (!isValidIdentifier(id)) return
        hlsJobs.remove(id)?.cancel()
        val packageName = preferences.getString(fileNameKey(id), null)
        if (!packageName.isNullOrBlank()) {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir.resolve("downloads")
            File(root, ".$packageName.hls.tmp").deleteRecursively()
            File(root, "$packageName.hls").deleteRecursively()
        }
        val requestId = preferences.getLong(requestKey(id), INVALID_REQUEST_ID)
        if (requestId != INVALID_REQUEST_ID) {
            platformManager?.remove(requestId)
        }
        val fileName = preferences.getString(fileNameKey(id), null)
        if (fileName != null) destination(fileName).delete()
        val cancelled = DownloadSnapshot(id, DownloadState.cancelled, 0L, 0L, "", "Download cancelled")
        saveSnapshot(cancelled)
        preferences.edit().remove(requestKey(id)).apply()
    }

    override suspend fun remove(id: String): Boolean {
        if (!isValidIdentifier(id)) throw DownloadManagerError.invalidIdentifier
        val existing = status(id)
        cancel(id)
        val localPath = existing?.localPath.orEmpty()
        val localFile = File(localPath)
        val hlsDirectory = localFile.parentFile?.takeIf { it.name.endsWith(".hls") }
        val removedFile = if (hlsDirectory != null) {
            hlsDirectory.deleteRecursively() || !hlsDirectory.exists()
        } else {
            localPath.isBlank() || localFile.delete() || !localFile.exists()
        }
        clearSavedSnapshot(id)
        return removedFile
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        hlsJobs.values.forEach { job -> job.cancel() }
        hlsJobs.clear()
        onProgress = null
        onCompleted = null
        onFailed = null
    }

    private fun query(id: String, requestId: Long): DownloadSnapshot? {
        val manager = platformManager ?: return null
        val cursor = manager.query(PlatformDownloadManager.Query().setFilterById(requestId)) ?: return null
        cursor.use { rows ->
            if (!rows.moveToFirst()) return null
            return snapshotFromCursor(id, rows)
        }
    }

    private fun snapshotFromCursor(id: String, cursor: Cursor): DownloadSnapshot {
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_STATUS))
        val received = cursor.getLong(cursor.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            .coerceAtLeast(0L)
        val expected = cursor.getLong(cursor.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            .coerceAtLeast(0L)
        val fileName = preferences.getString(fileNameKey(id), null).orEmpty()
        val localPath = if (fileName.isBlank()) "" else destination(fileName).absolutePath
        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(PlatformDownloadManager.COLUMN_REASON))
        val state = when (status) {
            PlatformDownloadManager.STATUS_PENDING -> DownloadState.queued
            PlatformDownloadManager.STATUS_RUNNING -> DownloadState.downloading
            PlatformDownloadManager.STATUS_PAUSED -> DownloadState.queued
            PlatformDownloadManager.STATUS_SUCCESSFUL -> DownloadState.completed
            PlatformDownloadManager.STATUS_FAILED -> DownloadState.failed
            else -> DownloadState.failed
        }
        val message = if (state == DownloadState.failed) "Download failed (reason $reason)" else ""
        return DownloadSnapshot(id, state, received, expected, localPath, message)
    }

    private fun saveSnapshot(snapshot: DownloadSnapshot) {
        preferences.edit()
            .putString(stateKey(snapshot.id), stateName(snapshot.state))
            .putLong(receivedKey(snapshot.id), snapshot.receivedBytes)
            .putLong(expectedKey(snapshot.id), snapshot.expectedBytes)
            .putString(pathKey(snapshot.id), snapshot.localPath)
            .putString(errorKey(snapshot.id), snapshot.error)
            .apply()
    }

    private fun readSavedSnapshot(id: String): DownloadSnapshot? {
        val stateName = preferences.getString(stateKey(id), null) ?: return null
        val state = when (stateName) {
            "queued" -> DownloadState.queued
            "downloading" -> DownloadState.downloading
            "completed" -> DownloadState.completed
            "failed" -> DownloadState.failed
            "cancelled" -> DownloadState.cancelled
            else -> return null
        }
        return DownloadSnapshot(
            id,
            state,
            preferences.getLong(receivedKey(id), 0L),
            preferences.getLong(expectedKey(id), 0L),
            preferences.getString(pathKey(id), "").orEmpty(),
            preferences.getString(errorKey(id), "").orEmpty(),
        )
    }

    private fun clearSavedSnapshot(id: String) {
        preferences.edit()
            .remove(stateKey(id))
            .remove(receivedKey(id))
            .remove(expectedKey(id))
            .remove(pathKey(id))
            .remove(errorKey(id))
            .remove(fileNameKey(id))
            .remove(requestKey(id))
            .apply()
    }

    private fun destination(fileName: String): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir.resolve("downloads")
        return File(directory, fileName)
    }

    private fun ensureActive() {
        if (disposed) throw DownloadManagerError.downloadFailed("Download manager is disposed")
    }

    private fun isValidIdentifier(id: String): Boolean =
        id.isNotBlank() && id.all { character -> character.isLetterOrDigit() || character == '-' || character == '_' }

    private fun stateName(state: DownloadState): String = when (state) {
        DownloadState.queued -> "queued"
        DownloadState.downloading -> "downloading"
        DownloadState.completed -> "completed"
        DownloadState.failed -> "failed"
        DownloadState.cancelled -> "cancelled"
    }

    private fun requestKey(id: String): String = "request.$id"
    private fun fileNameKey(id: String): String = "filename.$id"
    private fun stateKey(id: String): String = "state.$id"
    private fun receivedKey(id: String): String = "received.$id"
    private fun expectedKey(id: String): String = "expected.$id"
    private fun pathKey(id: String): String = "path.$id"
    private fun errorKey(id: String): String = "error.$id"

    private companion object {
        const val PREFERENCES_NAME = "nexa_download_manager"
        const val INVALID_REQUEST_ID = -1L
        const val POLL_INTERVAL_MS = 350L
        const val SOURCE_SCAN_MAX_DEPTH = 5
        const val SOURCE_SCAN_LIMIT = 20_000
    }
}
