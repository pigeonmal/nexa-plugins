package dev.nexa.videoplayer

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class VideoPlayerImplTest {
    @Test
    public fun twoPlayersPrepareAndDisposeIndependently() = runBlocking {
        val firstEngine = FakeVideoPlayerEngine(durationSeconds = 12.0)
        val secondEngine = FakeVideoPlayerEngine(durationSeconds = 38.5)
        val first = VideoPlayerImpl()
        val second = VideoPlayerImpl()
        var firstEnded = 0
        var secondEnded = 0
        first.onEnded = { firstEnded += 1 }
        second.onEnded = { secondEnded += 1 }
        first.attachEngine(firstEngine)
        second.attachEngine(secondEngine)
        first.volume = 0.25
        second.volume = 0.75

        val firstPrepare = async { first.prepare("https://media.example/first.mp4") }
        val secondPrepare = async { second.prepare("https://media.example/second.mp4") }
        firstPrepare.await()
        secondPrepare.await()

        assertEquals(listOf("https://media.example/first.mp4"), firstEngine.preparedUrls)
        assertEquals(listOf("https://media.example/second.mp4"), secondEngine.preparedUrls)
        assertEquals(PlayerState.ready, first.state)
        assertEquals(PlayerState.ready, second.state)
        assertEquals(12.0, first.duration, 0.0)
        assertEquals(38.5, second.duration, 0.0)
        assertEquals(0.25, firstEngine.volume, 0.0)
        assertEquals(0.75, secondEngine.volume, 0.0)

        first.play()
        assertEquals(PlayerState.playing, first.state)
        assertEquals(PlayerState.ready, second.state)
        firstEngine.finish()
        assertEquals(1, firstEnded)
        assertEquals(0, secondEnded)

        first.dispose()
        first.dispose()
        assertEquals(PlayerState.idle, first.state)
        assertEquals(1, firstEngine.releaseCount)
        assertNull(first.onEnded)
        assertEquals(PlayerState.ready, second.state)
        second.play()
        assertEquals(PlayerState.playing, second.state)
        assertEquals(0, secondEngine.releaseCount)

        second.dispose()
        assertEquals(1, secondEngine.releaseCount)
    }

    private class FakeVideoPlayerEngine(
        private val durationSeconds: Double,
    ) : VideoPlayerEngine {
        private var listener: VideoPlayerEngine.Listener? = null
        val preparedUrls = mutableListOf<String>()
        var volume = 1.0
            private set
        var releaseCount = 0
            private set

        override fun setListener(listener: VideoPlayerEngine.Listener?) {
            this.listener = listener
        }

        override suspend fun prepare(url: String) {
            preparedUrls += url
            listener?.onStateChanged(PlayerState.ready, durationSeconds)
        }

        override fun setVolume(volume: Double) {
            this.volume = volume
        }

        override fun play() {
            listener?.onStateChanged(PlayerState.playing)
        }

        override fun pause() {
            listener?.onStateChanged(PlayerState.paused)
        }

        override fun seek(position: Double) = Unit

        override fun release() {
            releaseCount += 1
            listener = null
        }

        fun finish() {
            listener?.onStateChanged(PlayerState.ended)
            listener?.onEnded()
        }
    }
}
