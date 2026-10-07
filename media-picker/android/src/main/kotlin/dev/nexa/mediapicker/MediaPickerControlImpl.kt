package dev.nexa.mediapicker

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import dev.nexa.core.NexaRuntimeCore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens Android's system photo picker and copies the selected item to private app storage. */
@Composable
public fun MediaPickerControlImpl(
    isVideo: Boolean,
    selectionLimit: Int,
    onPicked: ((List<String>) -> Unit)? = null,
    onFailed: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val latestOnPicked = rememberUpdatedState(onPicked)
    val latestOnFailed = rememberUpdatedState(onFailed)
    val scope = rememberCoroutineScope()
    fun copySelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            var copiedFiles: List<File> = emptyList()
            val selectedFiles = try {
                copySelectedMedia(context, uris).also { copiedFiles = it }
                    .also { coroutineContext.ensureActive() }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    copiedFiles.forEach(File::delete)
                }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    copiedFiles.forEach(File::delete)
                }
                latestOnFailed.value?.invoke(error.message ?: "The selected media could not be copied.")
                return@launch
            }
            val handler = latestOnPicked.value
            if (handler == null) {
                withContext(Dispatchers.IO) {
                    selectedFiles.forEach(File::delete)
                }
            } else {
                handler(selectedFiles.map { Uri.fromFile(it).toString() })
            }
        }
    }
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { copySelection(listOf(it)) } }
    val multipleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> copySelection(uris) }
    val mediaType = if (isVideo) {
        ActivityResultContracts.PickVisualMedia.VideoOnly
    } else {
        ActivityResultContracts.PickVisualMedia.ImageOnly
    }

    Box(
        modifier = Modifier
            .semantics { role = Role.Button }
            .clickable {
                if (selectionLimit < 0) {
                    latestOnFailed.value?.invoke("selectionLimit must be zero or greater.")
                    return@clickable
                }
                val request = if (selectionLimit >= 2) {
                    PickVisualMediaRequest(mediaType, maxItems = selectionLimit)
                } else {
                    PickVisualMediaRequest(mediaType)
                }
                if (selectionLimit == 1) {
                    singleLauncher.launch(request)
                } else {
                    multipleLauncher.launch(request)
                }
            },
    ) {
        content()
    }
}

private suspend fun copySelectedMedia(context: Context, sources: List<Uri>): List<File> {
    val copiedFiles = mutableListOf<File>()
    try {
        return withContext(Dispatchers.IO) {
            sources.forEach { source ->
                currentCoroutineContext().ensureActive()
                copiedFiles += copyPickedMedia(context, source)
            }
            currentCoroutineContext().ensureActive()
            copiedFiles.toList()
        }
    } catch (error: Exception) {
        withContext(NonCancellable + Dispatchers.IO) {
            copiedFiles.forEach(File::delete)
        }
        throw error
    }
}

/** Deletes only files directly owned by this plugin's private cache directory. */
public class MediaPickerCacheImpl : MediaPickerCacheSpec {
    override suspend fun remove(uri: String): Boolean = withContext(Dispatchers.IO) {
        val file = ownedCacheFile(uri) ?: return@withContext false
        file.isFile && file.delete()
    }

    override suspend fun removeMany(uris: List<String>): Int = withContext(Dispatchers.IO) {
        var removed = 0
        for (uri in uris) {
            val file = ownedCacheFile(uri) ?: continue
            if (file.isFile && file.delete()) removed++
        }
        removed
    }

    private fun ownedCacheFile(rawUri: String): File? {
        val uri = Uri.parse(rawUri)
        if (uri.scheme != "file" || !uri.host.isNullOrEmpty() || uri.query != null || uri.fragment != null) {
            return null
        }
        val file = File(uri.path ?: return null).canonicalFile
        val cacheDirectory = File(
            NexaRuntimeCore.context().applicationContext.cacheDir,
            MEDIA_PICKER_CACHE_DIRECTORY,
        ).canonicalFile
        return file.takeIf { it.parentFile == cacheDirectory }
    }
}

private fun copyPickedMedia(context: Context, source: Uri): File {
    val directory = File(context.cacheDir, "nexa-media-picker")
    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Could not create the media cache directory.")
    }
    val mimeType = context.contentResolver.getType(source)
    val extension = mimeType?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
        ?.takeIf(String::isNotBlank)
        ?: "media"
    val destination = File.createTempFile("picked-", ".$extension", directory)
    return try {
        val input = context.contentResolver.openInputStream(source)
            ?: throw IOException("Could not open the selected media.")
        input.use { sourceStream ->
            destination.outputStream().buffered().use { destinationStream ->
                sourceStream.copyTo(destinationStream, COPY_BUFFER_SIZE_BYTES)
            }
        }
        destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

private const val COPY_BUFFER_SIZE_BYTES = 64 * 1024
private const val MEDIA_PICKER_CACHE_DIRECTORY = "nexa-media-picker"
