package com.bbttvv.app.feature.live

import com.bbttvv.app.core.store.SettingsManager

internal data class LiveStreamCandidate(
    val key: String,
    val lineKey: String,
    val lineBaseLabel: String,
    val lineSubtitle: String?,
    val protocolName: String,
    val protocolLabel: String,
    val formatName: String,
    val formatLabel: String,
    val codecName: String,
    val codecLabel: String,
    val currentQn: Int,
    val host: String,
    val hostLabel: String,
    val url: String,
    val hdrType: Int = 0,
    val eotf: Int = 0,
    val codecs: String = "",
    val acceptedQns: List<Int> = emptyList(),
    val drm: Boolean = false,
)

internal fun selectLiveStreamCandidate(
    candidates: List<LiveStreamCandidate>,
    preferredLineKey: String?,
    preferredQualityKey: Int = 0,
    preferHighBitrate: Boolean,
    cdnPreference: SettingsManager.PlayerCdnPreference,
): LiveStreamCandidate? {
    val qualityScopedCandidates = prioritizeLiveQuality(candidates, preferredQualityKey)
    val lineScopedCandidates = preferredLineKey
        ?.let { key -> qualityScopedCandidates.filter { it.lineKey == key } }
        .orEmpty()
        .ifEmpty { qualityScopedCandidates }
    return rankLiveStreamCandidates(
        candidates = lineScopedCandidates,
        preferHighBitrate = preferHighBitrate,
        cdnPreference = cdnPreference,
    ).firstOrNull()
}

private fun prioritizeLiveQuality(
    candidates: List<LiveStreamCandidate>,
    requestedQualityKey: Int,
): List<LiveStreamCandidate> {
    if (requestedQualityKey <= 0) return candidates
    val requestedQn = liveQualityQn(requestedQualityKey)
    val requestedHdrType = liveQualityHdrType(requestedQualityKey)
    val requestedEotf = liveQualityEotf(requestedQualityKey)
    val matchingVariant = candidates.filter {
        it.hdrType == requestedHdrType && it.eotf == requestedEotf
    }
    val exact = matchingVariant.filter { it.currentQn == requestedQn }
    if (exact.isNotEmpty()) return exact
    val advertised = matchingVariant.filter { requestedQn in it.acceptedQns }
    return advertised.ifEmpty { matchingVariant.ifEmpty { candidates } }
}

internal fun selectLiveRecoveryCandidate(
    candidates: List<LiveStreamCandidate>,
    failedCandidateKeys: Set<String>,
    currentLineKey: String?,
    preferHighBitrate: Boolean,
    cdnPreference: SettingsManager.PlayerCdnPreference,
    preferCompatibleCodec: Boolean,
    currentQualityKey: Int = 0,
): LiveStreamCandidate? {
    var remaining = candidates.filterNot { it.key in failedCandidateKeys }
        .filter { (it.hdrType == 0 && it.eotf == 0) ||
            (it.hdrType == liveQualityHdrType(currentQualityKey) && it.eotf == liveQualityEotf(currentQualityKey)) }
    if (preferCompatibleCodec && remaining.any { it.codecName.equals("avc", ignoreCase = true) }) {
        remaining = remaining.filter { it.codecName.equals("avc", ignoreCase = true) }
    }
    remaining = scopeLiveVariants(remaining, currentQualityKey)
    val sameLine = currentLineKey
        ?.let { key -> remaining.filter { it.lineKey == key } }
        .orEmpty()
    val otherLines = if (sameLine.isEmpty()) {
        remaining
    } else {
        remaining.filterNot { it.lineKey == currentLineKey }
    }
    return rankLiveStreamCandidates(
        candidates = sameLine,
        preferHighBitrate = preferHighBitrate,
        cdnPreference = cdnPreference,
    ).firstOrNull() ?: rankLiveStreamCandidates(
        candidates = otherLines,
        preferHighBitrate = preferHighBitrate,
        cdnPreference = cdnPreference,
    ).firstOrNull()
}

internal fun rankLiveStreamCandidates(
    candidates: List<LiveStreamCandidate>,
    preferHighBitrate: Boolean,
    cdnPreference: SettingsManager.PlayerCdnPreference,
): List<LiveStreamCandidate> {
    return candidates.sortedWith(
        compareByDescending<LiveStreamCandidate> {
            scoreLiveStreamCandidate(
                candidate = it,
                preferHighBitrate = preferHighBitrate,
                cdnPreference = cdnPreference,
            )
        }
            .thenByDescending { it.currentQn }
            .thenBy { it.hostLabel }
            .thenBy { it.key }
    )
}

internal fun resolveEffectiveLiveQuality(
    candidate: LiveStreamCandidate,
    responseQuality: Int,
): Int {
    return candidate.currentQn.takeIf { it > 0 }
        ?.let { liveQualityKey(it, candidate.hdrType, candidate.eotf) }
        ?: responseQuality.coerceAtLeast(0)
}

internal fun scoreLiveStreamCandidate(
    candidate: LiveStreamCandidate,
    preferHighBitrate: Boolean,
    cdnPreference: SettingsManager.PlayerCdnPreference,
): Int {
    val host = candidate.hostLabel.lowercase()
    val matchesPreferredCdn = when (cdnPreference) {
        SettingsManager.PlayerCdnPreference.BILIVIDEO -> {
            host.contains("bilivideo") || host.contains("upos") || host.contains("cn-")
        }
        SettingsManager.PlayerCdnPreference.MCDN -> host.contains("mcdn")
    }
    val protocolScore = when (candidate.protocolName) {
        "http_hls" -> if (preferHighBitrate) 120 else 220
        "http_stream" -> if (preferHighBitrate) 220 else 140
        else -> 80
    }
    val formatScore = when (candidate.formatName) {
        "ts" -> if (preferHighBitrate) 240 else 200
        "flv" -> if (preferHighBitrate) 220 else 160
        "fmp4" -> if (preferHighBitrate) 160 else 190
        else -> 100
    }
    val codecScore = when (candidate.codecName.lowercase()) {
        "hevc" -> if (preferHighBitrate) 200 else 120
        "avc" -> if (preferHighBitrate) 150 else 190
        else -> 110
    }
    val hdrContainerScore = if (candidate.hdrType == 0) 0 else when {
        candidate.protocolName == "http_hls" && candidate.formatName == "fmp4" -> 3_000
        candidate.protocolName == "http_hls" && candidate.formatName == "ts" -> 2_000
        else -> 1_000
    }
    return candidate.currentQn * 1000 +
        hdrContainerScore +
        protocolScore +
        formatScore +
        codecScore +
        if (matchesPreferredCdn) 300 else 0
}
