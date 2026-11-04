package com.bbttvv.app.system

private val playbackIdPattern = Regex("(?:BV[0-9A-Za-z]{10}|(?:ep|ss)[1-9][0-9]{0,17})")

/** Only business identifiers and a bounded position may enter the player from another app. */
data class LauncherResumeRequest(val bvid: String, val cid: Long, val positionMs: Long)

internal fun parseLauncherResumeRequest(
    videoId: String?,
    episodeId: String?,
    position: Any?,
    duration: Any?,
): LauncherResumeRequest? {
    if (videoId == null || !playbackIdPattern.matches(videoId)) return null
    val cid = episodeId?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    fun millis(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is String -> value.toLongOrNull()
        else -> null
    }?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
    val positionMs = millis(position) ?: return null
    val durationMs = if (duration == null) 0L else millis(duration) ?: return null
    if (durationMs > 0L && positionMs >= durationMs) return null
    return LauncherResumeRequest(videoId, cid, positionMs)
}

internal data class TclPlaybackMetadata(
    val title: String,
    val coverUrl: String,
    val episodeName: String,
)

internal data class TclPlaybackRecord(
    val bvid: String,
    val cid: Long,
    val metadata: TclPlaybackMetadata,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAtMs: Long,
    val completed: Boolean,
)

internal fun buildTclPlaybackRecord(
    bvid: String,
    cid: Long,
    metadata: TclPlaybackMetadata?,
    positionMs: Long,
    durationMs: Long,
    realPlayedTimeSec: Long,
    watchedAtMs: Long,
    ended: Boolean,
): TclPlaybackRecord? {
    if (!playbackIdPattern.matches(bvid) || cid <= 0L || metadata == null) return null
    if (metadata.title.isBlank() || realPlayedTimeSec <= 0L) return null
    if (positionMs !in 0L..Int.MAX_VALUE.toLong() || durationMs !in 0L..Int.MAX_VALUE.toLong()) return null
    // Match Lumen's 97% completion rule; also honor our player's final ten-second guard.
    val completed = ended || (durationMs > 0L &&
        (positionMs >= durationMs * 97L / 100L || positionMs >= (durationMs - 10_000L).coerceAtLeast(0L)))
    return TclPlaybackRecord(bvid, cid, metadata, positionMs, durationMs, watchedAtMs, completed)
}

internal enum class TclHistoryMutation { UPSERT, DELETE }

internal fun TclPlaybackRecord.mutation(): TclHistoryMutation =
    if (completed || positionMs < 10_000L) TclHistoryMutation.DELETE else TclHistoryMutation.UPSERT
