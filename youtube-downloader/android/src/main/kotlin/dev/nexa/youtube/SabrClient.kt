package dev.nexa.youtube

import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val UMP_MEDIA_TYPE = "application/vnd.yt-ump"

/** YouTube SABR/UMP transfer using the app's shared Nexa Cronet engine. */
object SabrClient {
    fun buildRequest(
        bootstrap: SabrBootstrap,
        selectedItag: Int,
        poToken: String? = null,
        userAgent: String? = null,
    ): SabrRequest {
        val payload = SabrProtoCodec.buildAbrRequest(
            selectedItag = selectedItag,
            ustreamerConfigBase64 = bootstrap.videoPlaybackUstreamerConfig,
            poToken = poToken,
        )
        return SabrRequest(bootstrap.serverAbrStreamingUrl, payload, userAgent)
    }

    fun fetchMediaChunks(
        bootstrap: SabrBootstrap,
        selectedItag: Int,
        poToken: String? = null,
        userAgent: String? = null,
    ): List<SabrChunk> = createInputStream(bootstrap, selectedItag, poToken, userAgent).use { stream ->
        UmpReader.parseParts(stream.readBytes()).let(SabrProtoCodec::demuxParts)
    }

    fun streamMediaTo(
        bootstrap: SabrBootstrap,
        selectedItag: Int,
        output: OutputStream,
        poToken: String? = null,
        userAgent: String? = null,
        onBytesWritten: ((Long) -> Unit)? = null,
    ): Long = createInputStream(bootstrap, selectedItag, poToken, userAgent).use { stream ->
        var totalBytes = 0L
        while (true) {
            val part = UmpReader.readPart(stream) ?: break
            when (part.type) {
                SabrPart.TYPE_MEDIA -> if (part.payload.isNotEmpty()) {
                    output.write(part.payload)
                    totalBytes += part.payload.size
                    onBytesWritten?.invoke(totalBytes)
                }
                SabrPart.TYPE_MEDIA_END -> break
            }
        }
        output.flush()
        totalBytes
    }

    fun createInputStream(
        bootstrap: SabrBootstrap,
        selectedItag: Int,
        poToken: String? = null,
        userAgent: String? = null,
    ): InputStream = SabrInputStream(
        buildRequest(bootstrap, selectedItag, poToken, userAgent),
    )
}

data class SabrRequest(
    val url: String,
    val body: ByteArray,
    val userAgent: String?,
)

/** Bounded pipe keeps media bytes off the heap while Cronet streams the UMP response. */
private class SabrInputStream(requestData: SabrRequest) : InputStream() {
    private val input = PipedInputStream(256 * 1024)
    private val output = PipedOutputStream(input)
    private val responseReady = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    @Volatile private var failure: IOException? = null
    @Volatile private var responseCode = -1
    private lateinit var request: UrlRequest

    init {
        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                responseCode = info.httpStatusCode
                if (responseCode !in 200..299) {
                    failure = IOException("SABR HTTP $responseCode")
                    responseReady.countDown()
                    closeWriter()
                    request.cancel()
                    return
                }
                responseReady.countDown()
                request.read(ByteBuffer.allocateDirect(32 * 1024))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                try {
                    buffer.flip()
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    output.write(bytes)
                    if (!closed.get()) {
                        buffer.clear()
                        request.read(buffer)
                    }
                } catch (error: IOException) {
                    failure = error
                    closeWriter()
                    request.cancel()
                }
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                responseReady.countDown()
                closeWriter()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                failure = IOException("SABR request failed", error)
                responseReady.countDown()
                closeWriter()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                responseReady.countDown()
                closeWriter()
            }
        }
        val builder = YoutubeNetwork.cronetEngine().newUrlRequestBuilder(
            requestData.url,
            callback,
            YoutubeNetwork.cronetExecutor(),
        )
            .setHttpMethod("POST")
            .addHeader("Content-Type", UMP_MEDIA_TYPE)
            .addHeader("Accept", UMP_MEDIA_TYPE)
            .setUploadDataProvider(ByteArrayUploadProvider(requestData.body), YoutubeNetwork.cronetExecutor())
        requestData.userAgent?.takeIf(String::isNotBlank)?.let { builder.addHeader("User-Agent", it) }
        request = builder.build()
        request.start()
        if (!responseReady.await(35, TimeUnit.SECONDS)) {
            close()
            throw IOException("SABR request timed out")
        }
        failure?.let { throw it }
        if (responseCode !in 200..299) throw IOException("SABR HTTP $responseCode")
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed.get()) throw IOException("Stream closed")
        if (length == 0) return 0
        return try {
            input.read(buffer, offset, length).also { count ->
                if (count < 0) failure?.let { throw it }
            }
        } catch (error: IOException) {
            failure?.let { throw it }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        request.cancel()
        closeWriter()
        input.close()
    }

    private fun closeWriter() {
        runCatching { output.close() }
    }
}

private class ByteArrayUploadProvider(private val payload: ByteArray) : org.chromium.net.UploadDataProvider() {
    private var offset = 0

    override fun getLength(): Long = payload.size.toLong()

    override fun read(sink: org.chromium.net.UploadDataSink, destination: ByteBuffer) {
        val count = minOf(destination.remaining(), payload.size - offset)
        if (count > 0) {
            destination.put(payload, offset, count)
            offset += count
        }
        sink.onReadSucceeded(offset >= payload.size)
    }

    override fun rewind(sink: org.chromium.net.UploadDataSink) {
        offset = 0
        sink.onRewindSucceeded()
    }

    override fun close() = Unit
}
