package com.bbttvv.app.core.update

import java.util.Locale

/**
 * Android-independent updater decisions.
 *
 * These functions are intentionally free of Context/PackageManager so version comparison,
 * prompt deduplication and progress arithmetic can be covered by plain JVM unit tests.
 * A version name is only used to decide whether to show a prompt; the upgrade security
 * boundary stays in AppUpdateRepository.verifyApk (package name, versionCode, signature).
 */
private val VERSION_NUMBER = Regex("\\d+(?:\\.\\d+)*")

internal const val UNKNOWN_LENGTH_REPORT_INTERVAL_BYTES = 512L * 1024L

/**
 * A download sample. [totalBytes] <= 0 means the server did not provide Content-Length.
 */
data class AppUpdateDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
)

internal fun AppUpdateDownloadProgress.percentOrNull(): Int? =
    resolveDownloadPercent(bytesDownloaded, totalBytes)

/** Extracts the leading dotted numeric version, e.g. "v1.30" -> "1.30", "1.00-debug" -> "1.00". */
internal fun normalizeVersionName(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return VERSION_NUMBER.find(raw)?.value
}

/** Release tag is authoritative; asset filename is a fallback for releases without a usable tag. */
internal fun resolveReleaseVersion(tagName: String?, assetName: String): String? =
    normalizeVersionName(tagName) ?: normalizeVersionName(assetName)

/**
 * Compares dotted numeric versions. Returns null when either side cannot be parsed, which
 * callers must treat as "unknown" rather than "up to date" to avoid hiding a real update.
 */
internal fun compareVersionNames(latest: String?, current: String?): Int? {
    val left = normalizeVersionName(latest) ?: return null
    val right = normalizeVersionName(current) ?: return null
    val leftParts = left.split('.').map { it.toLongOrNull() ?: return null }
    val rightParts = right.split('.').map { it.toLongOrNull() ?: return null }
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val leftPart = leftParts.getOrElse(index) { 0L }
        val rightPart = rightParts.getOrElse(index) { 0L }
        if (leftPart != rightPart) return if (leftPart > rightPart) 1 else -1
    }
    return 0
}

/** Unparsable versions keep the previous "offer the update" behaviour (fail toward showing it). */
internal fun shouldOfferUpdate(latestVersion: String?, currentVersion: String): Boolean {
    val comparison = compareVersionNames(latestVersion, currentVersion) ?: return true
    return comparison > 0
}

/** One automatic prompt per version; a different version is a new prompt. */
internal fun shouldShowAutomaticPrompt(
    promptedVersion: String?,
    candidateVersion: String,
    isNewer: Boolean,
): Boolean = isNewer && promptedVersion != candidateVersion

/** Integer percent in [0, 100], or null for unknown total length. */
internal fun resolveDownloadPercent(bytesDownloaded: Long, totalBytes: Long): Int? {
    if (totalBytes <= 0L || bytesDownloaded < 0L) return null
    if (bytesDownloaded >= totalBytes) return 100
    return ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
}

internal fun formatDownloadedSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes.coerceAtLeast(0L) / 1024.0 / 1024.0)
