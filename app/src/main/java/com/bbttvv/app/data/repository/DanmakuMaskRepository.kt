package com.bbttvv.app.data.repository

import com.bbttvv.app.core.network.NetworkModule
import com.bbttvv.app.core.util.Logger
import com.bbttvv.app.feature.video.danmaku.DanmakuMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Loads Bilibili's precomputed mask; network and binary parsing stay off the UI thread. */
object DanmakuMaskRepository {
    private const val MAX_MASK_BYTES = 64L * 1024L * 1024L
    private val client by lazy {
        NetworkModule.okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }

    suspend fun load(maskUrl: String): DanmakuMask? = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(maskUrl)
        if (normalizedUrl == null) {
            Logger.w("DanmakuMask", "Invalid mask URL")
            return@withContext null
        }

        val primary = loadFromUrl(normalizedUrl)
        if (primary != null) return@withContext primary

        // The API may return a webmask URL while the mobile endpoint only exposes mobmask,
        // and vice versa. Try the paired format once before reporting an unavailable mask.
        val alternateUrl = when {
            normalizedUrl.contains("webmask", ignoreCase = true) ->
                normalizedUrl.replace("webmask", "mobmask", ignoreCase = true)
            normalizedUrl.contains("mobmask", ignoreCase = true) ->
                normalizedUrl.replace("mobmask", "webmask", ignoreCase = true)
            else -> null
        }
        if (alternateUrl == null || alternateUrl == normalizedUrl) return@withContext null
        Logger.w("DanmakuMask", "Trying alternate mask format")
        loadFromUrl(alternateUrl)
    }

    private suspend fun loadFromUrl(normalizedUrl: String): DanmakuMask? {
        val request = runCatching { Request.Builder().url(normalizedUrl).get().build() }
            .onFailure { error -> Logger.w("DanmakuMask", "Invalid mask request: ${error.message}") }
            .getOrNull() ?: return null
        val bytes = download(request) ?: return null
        return DanmakuMask.fromBinary(bytes, normalizedUrl).also { mask ->
            val type = if (normalizedUrl.contains("mobmask", ignoreCase = true)) "mob" else "web"
            Logger.w(
                "DanmakuMask",
                if (mask == null) {
                    "Invalid ${type}mask header url=${normalizedUrl.substringBefore("?")} bytes=${bytes.size}"
                } else {
                    "Mask loaded type=$type bytes=${bytes.size} segments=${mask.segmentCount}"
                },
            )
        }
    }

    private fun normalizeUrl(maskUrl: String): String? = when {
        maskUrl.startsWith("//") -> "https:$maskUrl"
        maskUrl.startsWith("http://") || maskUrl.startsWith("https://") -> maskUrl
        else -> null
    }

    private suspend fun download(request: Request): ByteArray? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                Logger.w("DanmakuMask", "Mask download failed: ${e.message}")
                continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bytes = runCatching {
                    response.use {
                        if (!it.isSuccessful) {
                            Logger.w("DanmakuMask", "Mask HTTP status=${it.code}")
                            return@use null
                        }
                        val body = it.body
                        require(body.contentLength() <= MAX_MASK_BYTES)
                        body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = input.read(buffer)
                                if (size == -1) break
                                require(output.size().toLong() + size <= MAX_MASK_BYTES)
                                output.write(buffer, 0, size)
                            }
                            output.toByteArray()
                        }
                    }
                }.onFailure { error ->
                    Logger.w("DanmakuMask", "Mask body read failed: ${error.message}")
                }.getOrNull()
                if (continuation.isActive) continuation.resume(bytes)
            }
        })
    }
}
