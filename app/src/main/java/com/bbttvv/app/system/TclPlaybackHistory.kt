package com.bbttvv.app.system

import android.content.Context
import android.content.Intent
import com.bbttvv.app.core.coroutines.AppScope
import com.bbttvv.app.core.util.Logger
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uses the public third-party launcher play-record broadcast contract. The raw database is
 * intentionally read-only from this app: Lumen documents its schema and reads, not writes.
 */
internal object TclPlaybackHistory {
    private const val TCL_PACKAGE = "com.tcl.cyberui"
    private const val TAG = "TclPlaybackHistory"
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private var lastSequence = 0L

    fun resumeAction(packageName: String): String = "$packageName.action.RESUME_PLAYBACK"

    @Suppress("DEPRECATION")
    fun resumeRequest(intent: Intent?, packageName: String): LauncherResumeRequest? {
        if (intent?.action != resumeAction(packageName)) return null
        return try {
            val extras = intent.extras ?: return null
            parseLauncherResumeRequest(
                extras.get("videoId") as? String,
                extras.get("episodeId") as? String,
                extras.get("currentPosition"),
                extras.get("duration"),
            )
        } catch (_: RuntimeException) {
            // Malformed or unparcelable external extras must never interrupt startup.
            null
        }
    }

    fun publish(context: Context, record: TclPlaybackRecord) {
        val appContext = context.applicationContext
        val revision = sequence.incrementAndGet()
        AppScope.ioScope.launch {
            mutex.withLock {
                // A delayed flush from the old player must not overwrite a newer snapshot.
                if (revision <= lastSequence) return@withLock
                lastSequence = revision
                try {
                    if (record.mutation() == TclHistoryMutation.DELETE) {
                        appContext.sendBroadcast(
                            Intent("${appContext.packageName}.action.ACTION_DELETE_PALYRECORD")
                                .setPackage(TCL_PACKAGE)
                                .putExtra("srcApp", appContext.packageName)
                                .putExtra("videoId", record.bvid)
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                        )
                    } else {
                        val episodeId = record.cid.toString()
                        val positionMs = record.positionMs.toInt()
                        val durationMs = record.durationMs.toInt()
                        val cmdInfo = JSONObject()
                            .put("playType", "history")
                            .put("videoId", record.bvid)
                            .put("episodeId", episodeId)
                            .put("history", positionMs)
                            .put("customer", "tcl")
                            .toString()
                        appContext.sendBroadcast(
                            Intent("${appContext.packageName}.action.ACTION_ADD_PLAYRECORD")
                                .setPackage(TCL_PACKAGE)
                                .putExtra("srcApp", resumeAction(appContext.packageName))
                                .putExtra("videoId", record.bvid)
                                .putExtra("videoName", record.metadata.title)
                                .putExtra("videoImgUrl", record.metadata.coverUrl)
                                .putExtra("episodeId", episodeId)
                                .putExtra("chnId", "0")
                                .putExtra("longVideo", "1")
                                .putExtra("episodeName", record.metadata.episodeName)
                                .putExtra("episodeCount", "0")
                                // The public Launcher broadcast takes seconds; cmdInfo.history is ms.
                                .putExtra("currentPosition", (positionMs / 1000).toString())
                                .putExtra("duration", (durationMs / 1000).toString())
                                .putExtra("cmdstring", "playInfo")
                                .putExtra("cmdInfo", cmdInfo)
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                        )
                    }
                } catch (_: Exception) {
                    // No retry loop and no provider/broadcast error details in logs.
                    Logger.w(TAG, "Desktop history notification failed")
                }
            }
        }
    }
}
