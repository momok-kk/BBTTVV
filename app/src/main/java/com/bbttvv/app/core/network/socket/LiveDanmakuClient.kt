package com.bbttvv.app.core.network.socket

import android.util.Log
import com.bbttvv.app.core.network.NetworkModule
import com.bbttvv.app.core.network.resolveAppUserAgent
import com.bbttvv.app.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal val LIVE_DANMAKU_AUTH_PROTOCOL_VERSION = DanmakuProtocol.PROTO_VER_BROTLI
internal const val LIVE_DANMAKU_MAX_TRANSPORT_RETRIES = 6

internal fun shouldRetryLiveDanmakuTransport(retryCount: Int): Boolean {
    return retryCount.coerceAtLeast(0) < LIVE_DANMAKU_MAX_TRANSPORT_RETRIES
}

data class LiveDanmakuEndpoint(val url: String)

data class LiveDanmakuConnectionConfig(
    val endpoints: List<LiveDanmakuEndpoint>,
    val token: String,
    val realRoomId: Long,
    val uid: Long = 0L,
)

enum class LiveDanmakuConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    RECONNECTING,
    FAILED,
    RELEASED,
}

class LiveDanmakuClient internal constructor(
    private val scope: CoroutineScope,
    private val reconnectConfigProvider: (suspend () -> LiveDanmakuConnectionConfig?)?,
    private val openWebSocket: (Request, WebSocketListener) -> WebSocket,
    private val wait: suspend (Long) -> Unit,
) {
    constructor(
        scope: CoroutineScope,
        reconnectConfigProvider: (suspend () -> LiveDanmakuConnectionConfig?)? = null,
    ) : this(
        scope = scope,
        reconnectConfigProvider = reconnectConfigProvider,
        openWebSocket = { request, listener -> NetworkModule.okHttpClient.newWebSocket(request, listener) },
        wait = { delay(it) },
    )

    private val tag = "LiveDanmakuClient"

    // Serialize socket callbacks, timers and room lifecycle changes. Decode and config fetch run outside the lock.
    private val socketLock = Any()
    private var webSocket: WebSocket? = null
    private var connectionLifecycleGeneration: Long = 0L
    private var currentConfig: LiveDanmakuConnectionConfig? = null
    private var currentEndpointIndex: Int = 0
    private var reconnectJob: Job? = null
    private var authTimeoutJob: Job? = null
    private var heartbeatJob: Job? = null
    private var unansweredHeartbeats: Int = 0
    private var confirmedHeartbeats: Int = 0
    private var retryCount: Int = 0
    private var authFailureCount: Int = 0

    private val _connectionState = MutableStateFlow(LiveDanmakuConnectionState.IDLE)
    val connectionState = _connectionState.asStateFlow()
    val isConnected: Boolean get() = _connectionState.value == LiveDanmakuConnectionState.AUTHENTICATED

    private data class IncomingFrame(val socket: WebSocket, val data: ByteArray)

    private val incomingFrames = Channel<IncomingFrame>(
        capacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _messageFlow = MutableSharedFlow<DanmakuProtocol.Packet>(
        replay = 0,
        extraBufferCapacity = 200,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messageFlow = _messageFlow.asSharedFlow()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(socketLock) {
                if (this@LiveDanmakuClient.webSocket !== webSocket) {
                    webSocket.cancel()
                    return
                }
                val config = checkNotNull(currentConfig)
                _connectionState.value = LiveDanmakuConnectionState.CONNECTED
                val authBody = buildJsonObject {
                    put("uid", config.uid)
                    put("roomid", config.realRoomId)
                    put("protover", LIVE_DANMAKU_AUTH_PROTOCOL_VERSION)
                    put("platform", "web")
                    put("type", 2)
                    put("key", config.token)
                }.toString()
                if (!sendPacket(webSocket, DanmakuProtocol.OP_AUTH, authBody)) {
                    reconnectAfterLoss(webSocket, "Auth packet rejected")
                    return
                }
                authTimeoutJob = scope.launch {
                    wait(AUTH_TIMEOUT_MS)
                    synchronized(socketLock) {
                        if (isActive && this@LiveDanmakuClient.webSocket === webSocket && !isConnected) {
                            reconnectAfterLoss(webSocket, "Auth reply timed out", forceRefresh = true)
                        }
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(socketLock) {
                if (this@LiveDanmakuClient.webSocket !== webSocket) return
            }
            incomingFrames.trySend(IncomingFrame(webSocket, bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(socketLock) {
                if (this@LiveDanmakuClient.webSocket !== webSocket) return
                // Answer the peer's close frame even for code 1000, then restore the active room's feed.
                val closingSocket = detachSocket()
                closingSocket?.close(NORMAL_CLOSURE_CODE, null)
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(socketLock) {
                reconnectAfterLoss(webSocket, "WebSocket closed (code=$code)")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(socketLock) {
                reconnectAfterLoss(webSocket, "WebSocket failure (${t.javaClass.simpleName})")
            }
        }
    }

    private val decodeJob = scope.launch(Dispatchers.Default) {
        for (frame in incomingFrames) {
            if (synchronized(socketLock) { webSocket !== frame.socket }) continue
            handleMessage(frame)
        }
    }

    fun connect(url: String, token: String, roomId: Long, uid: Long = 0L) {
        connect(
            LiveDanmakuConnectionConfig(
                endpoints = listOf(LiveDanmakuEndpoint(url)),
                token = token,
                realRoomId = roomId,
                uid = uid,
            )
        )
    }

    fun connect(config: LiveDanmakuConnectionConfig) {
        val normalizedConfig = config.normalized()
        synchronized(socketLock) {
            if (_connectionState.value == LiveDanmakuConnectionState.RELEASED) return
            endConnection()
            retryCount = 0
            authFailureCount = 0
            currentConfig = normalizedConfig
            currentEndpointIndex = 0
            internalConnect()
        }
    }

    fun disconnect() {
        synchronized(socketLock) {
            if (_connectionState.value == LiveDanmakuConnectionState.RELEASED) return
            endConnection()
            _connectionState.value = LiveDanmakuConnectionState.IDLE
        }
    }

    fun release() {
        synchronized(socketLock) {
            if (_connectionState.value == LiveDanmakuConnectionState.RELEASED) return
            endConnection(force = true)
            decodeJob.cancel()
            incomingFrames.cancel()
            _connectionState.value = LiveDanmakuConnectionState.RELEASED
        }
    }

    private fun internalConnect() {
        val endpoint = currentConfig?.endpoints?.getOrNull(currentEndpointIndex)
            ?: return failConnection("No live danmaku endpoint available")
        val request = try {
            Request.Builder()
                .url(endpoint.url)
                .header("User-Agent", resolveAppUserAgent(NetworkModule.appContext))
                .header("Origin", "https://live.bilibili.com")
                .build()
        } catch (_: IllegalArgumentException) {
            failConnection("Invalid live danmaku endpoint")
            return
        }
        _connectionState.value = if (retryCount > 0) {
            LiveDanmakuConnectionState.RECONNECTING
        } else {
            LiveDanmakuConnectionState.CONNECTING
        }
        // OkHttp dispatches callbacks asynchronously. They cannot observe the socket before it is installed.
        webSocket = openWebSocket(request, listener)
    }

    private fun endConnection(force: Boolean = false) {
        connectionLifecycleGeneration += 1L
        reconnectJob?.cancel()
        reconnectJob = null
        currentConfig = null
        val socket = detachSocket()
        if (force) socket?.cancel() else socket?.close(NORMAL_CLOSURE_CODE, null)
    }

    private fun detachSocket(): WebSocket? {
        val socket = webSocket
        webSocket = null
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        return socket
    }

    private fun reconnectAfterLoss(socket: WebSocket, reason: String, forceRefresh: Boolean = false) {
        if (webSocket !== socket) return
        Log.w(tag, reason)
        detachSocket()?.cancel()
        scheduleReconnect(forceRefresh)
    }

    private fun startHeartbeat(socket: WebSocket) {
        unansweredHeartbeats = 0
        confirmedHeartbeats = 0
        heartbeatJob = scope.launch {
            while (isActive) {
                synchronized(socketLock) {
                    if (!isActive || webSocket !== socket) return@launch
                    if (unansweredHeartbeats >= 2) {
                        reconnectAfterLoss(socket, "Heartbeat replies timed out")
                        return@launch
                    }
                    if (!sendPacket(socket, DanmakuProtocol.OP_HEARTBEAT, "[object Object]")) {
                        reconnectAfterLoss(socket, "Heartbeat packet rejected")
                        return@launch
                    }
                    unansweredHeartbeats += 1
                }
                wait(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun scheduleReconnect(forceRefresh: Boolean = false) {
        if (reconnectJob?.isActive == true) return
        if (!shouldRetryLiveDanmakuTransport(retryCount)) {
            failConnection("Live danmaku reconnect attempts exhausted")
            return
        }

        val expectedLifecycle = connectionLifecycleGeneration
        val delayMs = (BASE_RECONNECT_DELAY_MS shl retryCount).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        _connectionState.value = LiveDanmakuConnectionState.RECONNECTING
        Logger.d(tag) { "Reconnecting in ${delayMs}ms, attempt=${retryCount + 1}" }
        reconnectJob = scope.launch {
            wait(delayMs)
            val shouldRefresh = synchronized(socketLock) {
                if (!isActive || connectionLifecycleGeneration != expectedLifecycle) return@launch
                retryCount += 1
                val endpoints = currentConfig?.endpoints.orEmpty()
                currentEndpointIndex = (currentEndpointIndex + 1) % endpoints.size
                forceRefresh || currentEndpointIndex == 0
            }
            val refreshed = if (shouldRefresh) refreshConnectionConfig() else null
            synchronized(socketLock) {
                if (!isActive || connectionLifecycleGeneration != expectedLifecycle) return@launch
                if (refreshed != null) {
                    currentConfig = refreshed
                    currentEndpointIndex = 0
                }
                reconnectJob = null
                internalConnect()
            }
        }
    }

    private suspend fun refreshConnectionConfig(): LiveDanmakuConnectionConfig? {
        return try {
            reconnectConfigProvider?.invoke()?.normalized()?.takeIf { it.endpoints.isNotEmpty() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w(tag, "Refresh live danmaku config failed")
            null
        }
    }

    private fun sendPacket(socket: WebSocket, operation: Int, body: String): Boolean {
        val packet = DanmakuProtocol.Packet(
            version = DanmakuProtocol.PROTO_VER_HEARTBEAT,
            operation = operation,
            body = body.toByteArray(),
        )
        return socket.send(DanmakuProtocol.encode(packet).toByteString())
    }

    private suspend fun handleMessage(frame: IncomingFrame) {
        try {
            val packets = DanmakuProtocol.decode(frame.data)
            for (packet in packets) {
                synchronized(socketLock) {
                    if (webSocket !== frame.socket) return
                    when (packet.operation) {
                        DanmakuProtocol.OP_HEARTBEAT_REPLY -> {
                            if (isConnected && packet.body.size >= 4 && unansweredHeartbeats > 0) {
                                unansweredHeartbeats = 0
                                // A socket that authenticates but never stays alive must consume retry budget.
                                if (confirmedHeartbeats < 2) {
                                    confirmedHeartbeats += 1
                                    if (confirmedHeartbeats == 2) retryCount = 0
                                }
                            }
                        }
                        DanmakuProtocol.OP_AUTH_REPLY -> {
                            if (_connectionState.value == LiveDanmakuConnectionState.CONNECTED) {
                                handleAuthReply(frame.socket, packet.body)
                            }
                        }
                        DanmakuProtocol.OP_MESSAGE -> if (isConnected) _messageFlow.tryEmit(packet)
                    }
                    Unit
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.e(tag, "Live danmaku message handling failed")
        }
    }

    private fun handleAuthReply(socket: WebSocket, body: ByteArray) {
        val authCode = runCatching {
            Json.parseToJsonElement(body.decodeToString()).jsonObject["code"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        if (authCode == 0) {
            authTimeoutJob?.cancel()
            authTimeoutJob = null
            authFailureCount = 0
            _connectionState.value = LiveDanmakuConnectionState.AUTHENTICATED
            startHeartbeat(socket)
        } else if (authFailureCount < MAX_AUTH_FAILURE_RETRIES) {
            authFailureCount += 1
            reconnectAfterLoss(socket, "Auth failed (code=$authCode)", forceRefresh = true)
        } else {
            detachSocket()?.cancel()
            failConnection("Live danmaku auth failed (code=$authCode)")
        }
    }

    private fun failConnection(message: String) {
        Log.e(tag, message)
        _connectionState.value = LiveDanmakuConnectionState.FAILED
    }

    private fun LiveDanmakuConnectionConfig.normalized(): LiveDanmakuConnectionConfig {
        return copy(
            endpoints = endpoints
                .map { endpoint -> LiveDanmakuEndpoint(endpoint.url.trim()) }
                .filter { endpoint -> endpoint.url.isNotBlank() }
                .distinctBy { endpoint -> endpoint.url },
        )
    }

    private companion object {
        private const val AUTH_TIMEOUT_MS = 15_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 10_000L
        private const val MAX_AUTH_FAILURE_RETRIES = 2
        private const val NORMAL_CLOSURE_CODE = 1000
    }
}
