package com.bbttvv.app.core.player

internal data class PlayerBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
)

private const val MIB = 1024L * 1024
private const val ON_DEMAND_BUFFER_MIN_HEAP_BYTES = 192 * MIB
private const val ON_DEMAND_BUFFER_TARGET_BYTES = 48 * MIB

internal fun resolvePlayerBufferPolicy(
    heapLimitBytes: Long = Runtime.getRuntime().maxMemory(),
    isOnDemand: Boolean = false,
): PlayerBufferPolicy {
    val defaultTargetBytes = (heapLimitBytes / 6).coerceIn(16 * MIB, 96 * MIB)
    // 有足够堆空间时给点播高码率预留更多样本，字节预算仍优先。
    val targetBytes = if (isOnDemand && heapLimitBytes >= ON_DEMAND_BUFFER_MIN_HEAP_BYTES) {
        maxOf(defaultTargetBytes, ON_DEMAND_BUFFER_TARGET_BYTES)
    } else {
        defaultTargetBytes
    }
    return PlayerBufferPolicy(
        minBufferMs = 15_000,
        maxBufferMs = 50_000,
        bufferForPlaybackMs = 1_600,
        bufferForPlaybackAfterRebufferMs = 3_000,
        targetBufferBytes = targetBytes.toInt(),
    )
}
