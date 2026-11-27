package com.bbttvv.app.core.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackTransferMeterTest {
    @Test
    fun explicitProgressiveSourceReportsNetworkTransferAndBufferAllocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val meter = BufferingSpeedMeter()
        val buffer = PlayerBufferConfiguration()
        val prepared = CountDownLatch(1)
        var error: PlaybackException? = null
        var player: ExoPlayer? = null
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 6_000
            thread(isDaemon = true) {
                runCatching { serveAudio(server) }
            }
            try {
                instrumentation.runOnMainSync {
                    val source = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(OkHttpClient()))
                        .createMediaSource(MediaItem.fromUri(Uri.parse("http://127.0.0.1:${server.localPort}/audio.wav")))
                    player = createConfiguredPlayer(context, meter, audioPassthrough = false, bufferConfiguration = buffer).apply {
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) prepared.countDown()
                            }

                            override fun onPlayerError(playbackError: PlaybackException) {
                                error = playbackError
                                prepared.countDown()
                            }
                        })
                        playWhenReady = false
                        setMediaSource(source)
                        prepare()
                    }
                }
                assertTrue("Progressive source prepared", prepared.await(6, TimeUnit.SECONDS))
                assertNull(error)
                assertTrue("Meter is attached to the explicit media source", meter.bytesPerSecond() > 0)
                assertTrue("Sample buffer contains media data", buffer.allocator.totalBytesAllocated > 0)
            } finally {
                instrumentation.runOnMainSync {
                    player?.release()
                    VolumeBalanceController.unregisterProcessor()
                }
            }
        }
    }

    private fun serveAudio(server: ServerSocket) {
        val sampleRate = 16_000
        val dataBytes = 2 * sampleRate * 8
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
        server.accept().use { socket ->
            socket.soTimeout = 5_000
            val request = socket.getInputStream().bufferedReader()
            while (!request.readLine().isNullOrEmpty()) Unit
            val response = socket.getOutputStream()
            response.write(
                ("HTTP/1.1 200 OK\r\nContent-Length: ${wav.size}\r\n" +
                    "Content-Type: audio/wav\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            response.write(wav, 0, wav.size / 2)
            response.flush()
            SystemClock.sleep(250)
            response.write(wav, wav.size / 2, wav.size - wav.size / 2)
            response.flush()
        }
    }
}
