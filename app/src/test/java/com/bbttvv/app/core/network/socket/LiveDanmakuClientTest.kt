package com.bbttvv.app.core.network.socket

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDanmakuClientTest {
    @Test
    fun authRequestsBrotliFramesSupportedByDecoder() {
        assertEquals(DanmakuProtocol.PROTO_VER_BROTLI, LIVE_DANMAKU_AUTH_PROTOCOL_VERSION)
    }

    @Test
    fun serverNormalCloseReconnectsAndDeliversNewDanmaku() = exercise { fixture ->
        val first = fixture.connect()
        first.open()
        first.authenticate()
        assertEquals(DanmakuProtocol.OP_HEARTBEAT, first.sentOperation())

        first.listener.onClosing(first, 1000, "")
        first.listener.onClosed(first, 1000, "")
        assertEquals(1000, first.closeCode)
        assertEquals(LiveDanmakuConnectionState.RECONNECTING, fixture.client.connectionState.value)
        fixture.advanceDelay(1_000)
        val next = fixture.sockets.receive()
        next.open()
        next.authenticate()
        assertEquals(DanmakuProtocol.OP_HEARTBEAT, next.sentOperation())

        val message = async(start = CoroutineStart.UNDISPATCHED) { fixture.client.messageFlow.first() }
        next.receive(packet(DanmakuProtocol.OP_MESSAGE, "new danmaku".encodeToByteArray()))
        assertEquals("new danmaku", message.await().body.decodeToString())
        assertTrue(fixture.sockets.tryReceive().isFailure)
    }

    @Test
    fun heartbeatRepliesKeepSocketAliveAndMissingRepliesReconnect() = exercise { fixture ->
        val socket = fixture.connect()
        socket.open()
        socket.authenticate()
        repeat(3) {
            assertEquals(DanmakuProtocol.OP_HEARTBEAT, socket.sentOperation())
            val processed = async(start = CoroutineStart.UNDISPATCHED) { fixture.client.messageFlow.first() }
            // Wait for the following packet to ensure the asynchronous decoder handled the reply.
            socket.receive(packet(DanmakuProtocol.OP_HEARTBEAT_REPLY, ByteArray(4)) +
                packet(DanmakuProtocol.OP_MESSAGE, byteArrayOf(1)))
            processed.await()
            fixture.advanceDelay(30_000)
            assertTrue(fixture.client.isConnected)
        }
        assertEquals(DanmakuProtocol.OP_HEARTBEAT, socket.sentOperation())
        fixture.advanceDelay(30_000)
        assertEquals(DanmakuProtocol.OP_HEARTBEAT, socket.sentOperation())
        fixture.advanceDelay(30_000)
        assertTrue(socket.cancelled)
        assertFalse(fixture.client.isConnected)
        fixture.advanceDelay(1_000)
        fixture.sockets.receive()
    }

    @Test
    fun rejectedHeartbeatReconnectsImmediately() = exercise { fixture ->
        val socket = fixture.connect()
        socket.open()
        socket.acceptSends = false
        socket.authenticate()
        assertEquals(DanmakuProtocol.OP_HEARTBEAT, socket.sentOperation())
        fixture.advanceDelay(1_000)
        assertTrue(socket.cancelled)
        fixture.sockets.receive()
    }

    @Test
    fun missingAuthReplyRefreshesConfigAndReconnects() = exercise { fixture ->
        fixture.refreshConfig = LiveDanmakuConnectionConfig(
            endpoints = listOf(LiveDanmakuEndpoint("wss://refreshed.example/sub")),
            token = "test-token",
            realRoomId = 1,
        )
        val socket = fixture.connect()
        socket.open()
        fixture.advanceDelay(15_000)
        assertTrue(socket.cancelled)
        fixture.advanceDelay(1_000)
        assertEquals("refreshed.example", fixture.sockets.receive().request().url.host)
    }

    @Test
    fun repeatedShortLivedConnectionsExhaustRetryBudget() = exercise { fixture ->
        var socket = fixture.connect()
        repeat(LIVE_DANMAKU_MAX_TRANSPORT_RETRIES) { attempt ->
            socket.open()
            socket.authenticate()
            assertEquals(DanmakuProtocol.OP_HEARTBEAT, socket.sentOperation())
            socket.fail()
            fixture.advanceDelay((1_000L shl attempt).coerceAtMost(10_000L))
            socket = fixture.sockets.receive()
        }
        socket.fail()
        assertEquals(LiveDanmakuConnectionState.FAILED, fixture.client.connectionState.value)
        assertTrue(fixture.sockets.tryReceive().isFailure)
    }

    @Test
    fun lifecycleChangesCancelReconnectAndIgnoreOldSocketCallbacks() = exercise { fixture ->
        val socket = fixture.connect()
        socket.fail()
        val pending = fixture.pendingDelay(1_000)
        fixture.client.disconnect()
        pending.complete(Unit)
        socket.listener.onClosed(socket, 1000, "")
        assertEquals(LiveDanmakuConnectionState.IDLE, fixture.client.connectionState.value)
        assertTrue(fixture.sockets.tryReceive().isFailure)

        val next = fixture.connect()
        next.open()
        fixture.client.release()
        next.fail()
        assertEquals(LiveDanmakuConnectionState.RELEASED, fixture.client.connectionState.value)
        assertTrue(next.cancelled)
        assertTrue(fixture.sockets.tryReceive().isFailure)
    }

    private fun exercise(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            withTimeout(5_000) { block(fixture) }
        } finally {
            fixture.client.release()
            fixture.scope.cancel()
        }
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Channel<FakeSocket>(Channel.UNLIMITED)
        private val delays = Channel<Pair<Long, CompletableDeferred<Unit>>>(Channel.UNLIMITED)
        var refreshConfig: LiveDanmakuConnectionConfig? = null

        val client = LiveDanmakuClient(
            scope = scope,
            reconnectConfigProvider = { refreshConfig },
            openWebSocket = { request, listener ->
                FakeSocket(request, listener).also { check(sockets.trySend(it).isSuccess) }
            },
            wait = { time ->
                val gate = CompletableDeferred<Unit>()
                check(delays.trySend(time to gate).isSuccess)
                try {
                    gate.await()
                } finally {
                    gate.cancel()
                }
            },
        )

        suspend fun connect(): FakeSocket {
            client.connect("wss://danmaku.example/sub", "test-token", 1)
            return sockets.receive()
        }

        suspend fun pendingDelay(expectedTime: Long): CompletableDeferred<Unit> {
            while (true) {
                val (time, gate) = delays.receive()
                if (gate.isCancelled) continue
                assertEquals(expectedTime, time)
                return gate
            }
        }

        suspend fun advanceDelay(expectedTime: Long) {
            pendingDelay(expectedTime).complete(Unit)
        }
    }

    private class FakeSocket(
        private val request: Request,
        val listener: WebSocketListener,
    ) : WebSocket {
        private val sent = Channel<ByteString>(Channel.UNLIMITED)
        var cancelled = false
            private set
        var closeCode: Int? = null
            private set
        var acceptSends = true

        suspend fun open() {
            listener.onOpen(this, Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build())
            assertEquals(DanmakuProtocol.OP_AUTH, sentOperation())
        }

        fun authenticate() = receive(packet(DanmakuProtocol.OP_AUTH_REPLY, """{"code":0}""".encodeToByteArray()))

        fun receive(bytes: ByteArray) = listener.onMessage(this, bytes.toByteString())

        fun fail() = listener.onFailure(this, IOException("test failure"), null)

        suspend fun sentOperation(): Int = DanmakuProtocol.decode(sent.receive().toByteArray()).single().operation

        override fun request(): Request = request
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = error("Expected a binary frame")
        override fun send(bytes: ByteString): Boolean {
            check(sent.trySend(bytes).isSuccess)
            return acceptSends
        }
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        fun packet(operation: Int, body: ByteArray): ByteArray = DanmakuProtocol.encode(
            DanmakuProtocol.Packet(DanmakuProtocol.PROTO_VER_HEARTBEAT, operation, body = body),
        )
    }
}
