package dev.nexa.mediapicker

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Opens Android's system photo picker and copies the selected item to app cache. */
@Composable
public fun MediaPickerControlImpl(
    isVideo: Boolean,
    onPicked: ((String) -> Unit)? = null,
    onFailed: ((String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val latestOnPicked = rememberUpdatedState(onPicked)
    val latestOnFailed = rememberUpdatedState(onFailed)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            MediaPickerWorker.ioExecutor.execute {
                val result = try {
                    Result.success(copyPickedMedia(context, uri))
                } catch (error: Exception) {
                    Result.failure(error)
                }
                MediaPickerWorker.mainHandler.post {
                    result.onSuccess { latestOnPicked.value?.invoke(it) }
                    result.onFailure {
                        latestOnFailed.value?.invoke(it.message ?: "The selected media could not be copied.")
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            latestOnFailed.value?.invoke(error.message ?: "The media picker is unavailable.")
        }
    }
    val mediaType = if (isVideo) {
        ActivityResultContracts.PickVisualMedia.VideoOnly
    } else {
        ActivityResultContracts.PickVisualMedia.ImageOnly
    }

    Box(
        modifier = Modifier
            .semantics { role = Role.Button }
            .clickable { launcher.launch(PickVisualMediaRequest(mediaType)) },
    ) {
        content()
    }
}

private fun copyPickedMedia(context: Context, source: Uri): String {
    val directory = File(context.cacheDir, "nexa-media-picker")
    if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Could not create the media cache directory.")
    }
    val cutoff = System.currentTimeMillis() - MEDIA_CACHE_MAX_AGE_MS
    directory.listFiles()?.forEach { file ->
        if (file.lastModified() < cutoff) file.delete()
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
        Uri.fromFile(destination).toString()
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

private object MediaPickerWorker {
    val ioExecutor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "NexaMediaPicker").apply { isDaemon = true }
    }
    val mainHandler = Handler(Looper.getMainLooper())
}

private const val COPY_BUFFER_SIZE_BYTES = 64 * 1024
private const val MEDIA_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
