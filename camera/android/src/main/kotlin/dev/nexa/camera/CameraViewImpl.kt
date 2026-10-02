package dev.nexa.camera

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.UUID

@Composable
public fun CameraViewImpl(
    facing: CameraFacing,
    photoRequestId: Int,
    recording: Boolean,
    recordAudio: Boolean,
    imageStreamEnabled: Boolean,
    barcodeScanningEnabled: Boolean,
    barcodeFormat: CameraBarcodeFormat,
    frameResolution: CameraFrameResolution,
    maxFramesPerSecond: Int,
    onPhotoCaptured: ((String) -> Unit)? = null,
    onVideoCaptured: ((String) -> Unit)? = null,
    onBarcodeDetected: ((CameraBarcode) -> Unit)? = null,
    onFrameAvailable: ((CameraFrame) -> Unit)? = null,
    onFailed: ((CameraError) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var hasCameraPermission by remember(context) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var hasAudioPermission by remember(context) {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val latestPhoto = rememberUpdatedState(onPhotoCaptured)
    val latestVideo = rememberUpdatedState(onVideoCaptured)
    val latestBarcode = rememberUpdatedState(onBarcodeDetected)
    val latestFrame = rememberUpdatedState(onFrameAvailable)
    val latestFailure = rememberUpdatedState(onFailed)

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) latestFailure.value?.invoke(CameraError.cameraPermissionDenied)
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
        if (!granted) latestFailure.value?.invoke(CameraError.microphonePermissionDenied)
    }

    LaunchedEffect(context) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(recording, recordAudio, hasCameraPermission) {
        if (hasCameraPermission && recording && recordAudio && !hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val controller = remember(context, activity) {
        CameraCaptureController(context.applicationContext)
    }
    SideEffect {
        controller.updateCallbacks(
            photo = { latestPhoto.value?.invoke(it) },
            video = { latestVideo.value?.invoke(it) },
            barcode = { latestBarcode.value?.invoke(it) },
            frame = { latestFrame.value?.invoke(it) },
            failure = { latestFailure.value?.invoke(it) },
        )
    }

    val effectiveRecording = recording && (!recordAudio || hasAudioPermission)
    LaunchedEffect(
        hasCameraPermission,
        activity,
        facing,
        effectiveRecording,
        recordAudio,
        imageStreamEnabled,
        barcodeScanningEnabled,
        barcodeFormat,
        frameResolution,
        maxFramesPerSecond,
        previewView,
        photoRequestId,
    ) {
        if (hasCameraPermission && activity != null) {
            controller.update(
                previewView = previewView,
                lifecycleOwner = activity,
                options = CameraOptions(
                    facing = facing,
                    recording = effectiveRecording,
                    recordAudio = recordAudio,
                    imageStreamEnabled = imageStreamEnabled,
                    barcodeScanningEnabled = barcodeScanningEnabled,
                    barcodeFormat = barcodeFormat,
                    frameResolution = frameResolution,
                    maxFramesPerSecond = maxFramesPerSecond,
                ),
                photoRequestId = photoRequestId,
            )
        } else if (hasCameraPermission) {
            controller.reportFailure(CameraError.cameraUnavailable)
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }

    Box(modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private data class CameraOptions(
    val facing: CameraFacing,
    val recording: Boolean,
    val recordAudio: Boolean,
    val imageStreamEnabled: Boolean,
    val barcodeScanningEnabled: Boolean,
    val barcodeFormat: CameraBarcodeFormat,
    val frameResolution: CameraFrameResolution,
    val maxFramesPerSecond: Int,
)

/** Owns one lifecycle-bound CameraX graph and keeps analysis on a single worker. */
private class CameraCaptureController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var isProviderRequested = false
    private var previewView: PreviewView? = null
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null
    private var options: CameraOptions? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val scannerLock = Any()
    private var scannerEntry: ScannerEntry? = null
    private var pendingPhotoRequestId: Int? = null
    private var lastPhotoRequestId = 0
    private var frameSequence = 0L
    private var lastFrameTimeNanos = 0L
    private var lastBarcodeScanTimeNanos = 0L
    private var lastBarcodeValue: String? = null
    private var lastBarcodeTimeNanos = 0L
    @Volatile private var isDisposed = false
    private var onPhotoCaptured: ((String) -> Unit)? = null
    private var onVideoCaptured: ((String) -> Unit)? = null
    private var onBarcodeDetected: ((CameraBarcode) -> Unit)? = null
    private var onFrameAvailable: ((CameraFrame) -> Unit)? = null
    private var onFailed: ((CameraError) -> Unit)? = null

    fun updateCallbacks(
        photo: ((String) -> Unit)?,
        video: ((String) -> Unit)?,
        barcode: ((CameraBarcode) -> Unit)?,
        frame: ((CameraFrame) -> Unit)?,
        failure: ((CameraError) -> Unit)?,
    ) {
        onPhotoCaptured = photo
        onVideoCaptured = video
        onBarcodeDetected = barcode
        onFrameAvailable = frame
        onFailed = failure
    }

    fun update(
        previewView: PreviewView,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        options: CameraOptions,
        photoRequestId: Int,
    ) {
        if (isDisposed) return
        this.previewView = previewView
        this.lifecycleOwner = lifecycleOwner
        if (photoRequestId != lastPhotoRequestId) {
            lastPhotoRequestId = photoRequestId
            if (photoRequestId > 0) pendingPhotoRequestId = photoRequestId
        }

        val optionsChanged = this.options != options
        this.options = options
        if (options.maxFramesPerSecond !in 1..30) {
            reportFailure(CameraError.invalidFrameRate(options.maxFramesPerSecond))
            return
        }

        if (optionsChanged) {
            if (recording != null && !options.recording) {
                recording?.stop()
            } else {
                bindUseCases()
            }
        } else {
            ensureCameraProvider()
            capturePendingPhoto()
        }
    }

    fun reportFailure(error: CameraError) {
        onFailed?.invoke(error)
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        recording?.stop()
        recording = null
        provider?.unbindAll()
        retireScanner()
        imageCapture = null
        videoCapture = null
        previewView = null
        lifecycleOwner = null
        onPhotoCaptured = null
        onVideoCaptured = null
        onBarcodeDetected = null
        onFrameAvailable = null
        onFailed = null
    }

    private fun ensureCameraProvider() {
        if (provider != null || isProviderRequested || isDisposed) return
        isProviderRequested = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (isDisposed) return@addListener
                try {
                    provider = future.get()
                    bindUseCases()
                } catch (_: Exception) {
                    isProviderRequested = false
                    reportFailure(CameraError.cameraUnavailable)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun bindUseCases() {
        val cameraProvider = provider
        val view = previewView
        val owner = lifecycleOwner
        val current = options
        if (cameraProvider == null || view == null || owner == null || current == null || isDisposed) {
            ensureCameraProvider()
            return
        }
        if (recording != null) {
            // CameraX finalizes the file asynchronously; its callback rebinds the requested graph.
            return
        }

        try {
            cameraProvider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val selector = CameraSelector.Builder()
                .requireLensFacing(
                    if (current.facing == CameraFacing.front) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    },
                )
                .build()

            val useCases = ArrayList<androidx.camera.core.UseCase>(3)
            useCases += preview

            val imageCaptureForMode = if (current.recording) {
                null
            } else {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                    .also(useCases::add)
            }
            val videoCaptureForMode = if (current.recording) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                        ),
                    )
                    .build()
                VideoCapture.withOutput(recorder).also(useCases::add)
            } else {
                null
            }
            val needsAnalysis = current.imageStreamEnabled || current.barcodeScanningEnabled
            val analysis = if (needsAnalysis) {
                createAnalysis(current).also(useCases::add)
            } else {
                null
            }

            cameraProvider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
            imageCapture = imageCaptureForMode
            videoCapture = videoCaptureForMode
            if (analysis == null) {
                retireScanner()
            }
            if (current.recording) startRecording(current)
            capturePendingPhoto()
        } catch (_: SecurityException) {
            reportFailure(CameraError.cameraPermissionDenied)
        } catch (_: Exception) {
            imageCapture = null
            videoCapture = null
            reportFailure(CameraError.cameraUnavailable)
        }
    }

    private fun createAnalysis(current: CameraOptions): ImageAnalysis {
        val size = when (current.frameResolution) {
            CameraFrameResolution.vga -> android.util.Size(640, 480)
            CameraFrameResolution.hd -> android.util.Size(1280, 720)
        }
        val aspectRatio = if (current.frameResolution == CameraFrameResolution.vga) {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatio)
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val analysisScanner = if (current.barcodeScanningEnabled) {
            ensureScanner(current.barcodeFormat)
        } else {
            null
        }
        analysis.setAnalyzer(CameraAnalysisExecutor.executor) { image ->
            analyzeFrame(image, current, analysisScanner)
        }
        return analysis
    }

    private fun ensureScanner(format: CameraBarcodeFormat): ScannerEntry = synchronized(scannerLock) {
        scannerEntry?.let { if (!it.closed && it.format == format) return@synchronized it }
        scannerEntry?.let(::retireScannerLocked)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(format.toMlKitFormat())
            .build()
        ScannerEntry(BarcodeScanning.getClient(options), format).also { scannerEntry = it }
    }

    private fun retireScanner() {
        synchronized(scannerLock) {
            scannerEntry?.let(::retireScannerLocked)
            scannerEntry = null
        }
    }

    private fun retireScannerLocked(entry: ScannerEntry) {
        entry.retired = true
        if (entry.pendingOperations == 0 && !entry.closed) {
            entry.closed = true
            entry.scanner.close()
        }
    }

    private fun beginScannerOperation(entry: ScannerEntry): Boolean = synchronized(scannerLock) {
        if (entry.closed) return@synchronized false
        entry.pendingOperations += 1
        true
    }

    private fun scannerCanStart(entry: ScannerEntry): Boolean = synchronized(scannerLock) {
        !entry.closed
    }

    private fun finishScannerOperation(entry: ScannerEntry) {
        synchronized(scannerLock) {
            entry.pendingOperations -= 1
            if (entry.retired && entry.pendingOperations == 0 && !entry.closed) {
                entry.closed = true
                entry.scanner.close()
            }
        }
    }

    private fun analyzeFrame(
        image: ImageProxy,
        current: CameraOptions,
        analysisScanner: ScannerEntry?,
    ) {
        if (isDisposed) {
            image.close()
            return
        }

        try {
            val now = SystemClock.elapsedRealtimeNanos()
            val intervalNanos = 1_000_000_000L / current.maxFramesPerSecond
            val scanner = analysisScanner
            val shouldEmitFrame = current.imageStreamEnabled && now - lastFrameTimeNanos >= intervalNanos
            val scannerToScan = scanner?.takeIf {
                current.barcodeScanningEnabled && now - lastBarcodeScanTimeNanos >= intervalNanos &&
                    scannerCanStart(it)
            }
            val shouldScanBarcode = scannerToScan != null
            if (!shouldEmitFrame && !shouldScanBarcode) {
                image.close()
                return
            }
            if (shouldEmitFrame) {
                lastFrameTimeNanos = now
                val frame = image.toI420Frame(++frameSequence)
                mainHandler.post {
                    if (!isDisposed) onFrameAvailable?.invoke(frame)
                }
            }

            val mediaImage = image.image
            if (mediaImage != null && scannerToScan != null && beginScannerOperation(scannerToScan)) {
                lastBarcodeScanTimeNanos = now
                try {
                    scannerToScan.scanner.process(InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees))
                    .addOnSuccessListener(CameraAnalysisExecutor.executor) { barcodes ->
                        for (barcode in barcodes) {
                            val value = barcode.rawValue ?: continue
                            val barcodeFormat = barcode.format.toNexaFormat() ?: continue
                            val timestamp = SystemClock.elapsedRealtimeNanos()
                            if (value == lastBarcodeValue && timestamp - lastBarcodeTimeNanos < BARCODE_REPEAT_INTERVAL_NANOS) {
                                continue
                            }
                            lastBarcodeValue = value
                            lastBarcodeTimeNanos = timestamp
                            val result = CameraBarcode(value, barcodeFormat)
                            mainHandler.post {
                                if (!isDisposed) onBarcodeDetected?.invoke(result)
                            }
                        }
                    }
                    .addOnCompleteListener(CameraAnalysisExecutor.executor) {
                        image.close()
                        finishScannerOperation(scannerToScan)
                    }
                } catch (_: Exception) {
                    image.close()
                    finishScannerOperation(scannerToScan)
                }
            } else {
                image.close()
            }
        } catch (_: Exception) {
            image.close()
        }
    }

    private fun capturePendingPhoto() {
        if (recording != null) return
        val requestId = pendingPhotoRequestId ?: return
        val capture = imageCapture ?: return
        val file = try {
            newCacheFile("photo-", ".jpg")
        } catch (error: IOException) {
            pendingPhotoRequestId = null
            reportFailure(CameraError.captureFailed(error.message ?: "Could not create the photo file."))
            return
        }
        pendingPhotoRequestId = null
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (!isDisposed) onPhotoCaptured?.invoke(Uri.fromFile(file).toString())
                }

                override fun onError(exception: ImageCaptureException) {
                    if (!isDisposed) {
                        reportFailure(CameraError.captureFailed(exception.message ?: "Photo capture failed."))
                    }
                }
            },
        )
    }

    private fun startRecording(current: CameraOptions) {
        val capture = videoCapture ?: return
        if (recording != null) return
        val file = try {
            newCacheFile("video-", ".mp4")
        } catch (error: IOException) {
            reportFailure(CameraError.recordingFailed(error.message ?: "Could not create the video file."))
            return
        }
        try {
            var pending: PendingRecording = capture.output.prepareRecording(
                context,
                FileOutputOptions.Builder(file).build(),
            )
            if (current.recordAudio) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                        if (!isDisposed) onVideoCaptured?.invoke(Uri.fromFile(file).toString())
                    } else if (!isDisposed) {
                        reportFailure(CameraError.recordingFailed(event.cause?.message ?: "Video recording failed."))
                    }
                    if (!isDisposed) bindUseCases()
                }
            }
        } catch (error: SecurityException) {
            recording = null
            reportFailure(CameraError.microphonePermissionDenied)
        } catch (error: Exception) {
            recording = null
            reportFailure(CameraError.recordingFailed(error.message ?: "Video recording could not start."))
        }
    }

    private fun newCacheFile(prefix: String, suffix: String): File {
        val directory = File(context.cacheDir, "nexa-camera")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the camera cache directory.")
        }
        return File(directory, "$prefix${UUID.randomUUID()}$suffix")
    }

    private fun CameraBarcodeFormat.toMlKitFormat(): Int = when (this) {
        CameraBarcodeFormat.aztec -> Barcode.FORMAT_AZTEC
        CameraBarcodeFormat.codabar -> Barcode.FORMAT_CODABAR
        CameraBarcodeFormat.code39 -> Barcode.FORMAT_CODE_39
        CameraBarcodeFormat.code93 -> Barcode.FORMAT_CODE_93
        CameraBarcodeFormat.code128 -> Barcode.FORMAT_CODE_128
        CameraBarcodeFormat.dataMatrix -> Barcode.FORMAT_DATA_MATRIX
        CameraBarcodeFormat.ean8 -> Barcode.FORMAT_EAN_8
        CameraBarcodeFormat.ean13 -> Barcode.FORMAT_EAN_13
        CameraBarcodeFormat.itf -> Barcode.FORMAT_ITF
        CameraBarcodeFormat.pdf417 -> Barcode.FORMAT_PDF417
        CameraBarcodeFormat.qr -> Barcode.FORMAT_QR_CODE
        CameraBarcodeFormat.upcA -> Barcode.FORMAT_UPC_A
        CameraBarcodeFormat.upcE -> Barcode.FORMAT_UPC_E
    }

    private fun Int.toNexaFormat(): CameraBarcodeFormat? = when (this) {
        Barcode.FORMAT_AZTEC -> CameraBarcodeFormat.aztec
        Barcode.FORMAT_CODABAR -> CameraBarcodeFormat.codabar
        Barcode.FORMAT_CODE_39 -> CameraBarcodeFormat.code39
        Barcode.FORMAT_CODE_93 -> CameraBarcodeFormat.code93
        Barcode.FORMAT_CODE_128 -> CameraBarcodeFormat.code128
        Barcode.FORMAT_DATA_MATRIX -> CameraBarcodeFormat.dataMatrix
        Barcode.FORMAT_EAN_8 -> CameraBarcodeFormat.ean8
        Barcode.FORMAT_EAN_13 -> CameraBarcodeFormat.ean13
        Barcode.FORMAT_ITF -> CameraBarcodeFormat.itf
        Barcode.FORMAT_PDF417 -> CameraBarcodeFormat.pdf417
        Barcode.FORMAT_QR_CODE -> CameraBarcodeFormat.qr
        Barcode.FORMAT_UPC_A -> CameraBarcodeFormat.upcA
        Barcode.FORMAT_UPC_E -> CameraBarcodeFormat.upcE
        else -> null
    }

    private companion object {
        const val BARCODE_REPEAT_INTERVAL_NANOS = 1_000_000_000L
    }

    private class ScannerEntry(
        val scanner: BarcodeScanner,
        val format: CameraBarcodeFormat,
        var pendingOperations: Int = 0,
        var retired: Boolean = false,
        var closed: Boolean = false,
    )
}

private object CameraAnalysisExecutor {
    val executor: Executor = Executors.newSingleThreadExecutor { command ->
        Thread(command, "NexaCameraAnalysis").apply { isDaemon = true }
    }
}

private fun ImageProxy.toI420Frame(sequence: Long): CameraFrame {
    val width = this.width
    val height = this.height
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val ySize = width * height
    val chromaSize = chromaWidth * chromaHeight
    val pixels = ByteArray(ySize + 2 * chromaSize)
    val planes = this.planes
    copyPlane(planes[0], pixels, 0, width, height)
    copyPlane(planes[1], pixels, ySize, chromaWidth, chromaHeight)
    copyPlane(planes[2], pixels, ySize + chromaSize, chromaWidth, chromaHeight)
    return CameraFrame(
        sequence = sequence,
        width = width,
        height = height,
        rotationDegrees = imageInfo.rotationDegrees,
        presentationTimeNanoseconds = imageInfo.timestamp,
        pixels = pixels,
    )
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    destination: ByteArray,
    destinationOffset: Int,
    width: Int,
    height: Int,
) {
    val buffer: ByteBuffer = plane.buffer.duplicate()
    val base = buffer.position()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    var target = destinationOffset
    for (row in 0 until height) {
        val rowOffset = base + row * rowStride
        if (pixelStride == 1) {
            buffer.position(rowOffset)
            buffer.get(destination, target, width)
            target += width
        } else {
            for (column in 0 until width) {
                destination[target++] = buffer.get(rowOffset + column * pixelStride)
            }
        }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> {
        val base = baseContext
        if (base === this) null else base.findActivity()
    }
    else -> null
}
