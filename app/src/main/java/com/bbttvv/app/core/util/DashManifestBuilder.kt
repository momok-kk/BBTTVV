package com.bbttvv.app.core.util

import com.bbttvv.app.data.model.response.Dash
import com.bbttvv.app.data.model.response.DashAudio
import com.bbttvv.app.data.model.response.DashVideo
import com.bbttvv.app.data.model.response.SegmentBase

object DashManifestBuilder {
    fun build(
        dash: Dash,
        selectedVideo: DashVideo,
        selectedAudio: DashAudio?,
        fallbackDurationMs: Long
    ): String {
        val durationSeconds = dash.duration.takeIf { it > 0 }?.toDouble()
            ?: (fallbackDurationMs.coerceAtLeast(0L) / 1000.0)
        val minBufferSeconds = dash.minBufferTime.takeIf { it > 0f } ?: 1.5f
        return buildManifest(
            selectedVideo = selectedVideo,
            selectedAudio = selectedAudio,
            videoUrls = listOf(selectedVideo.baseUrl) + selectedVideo.backupUrl.orEmpty(),
            audioUrls = listOfNotNull(selectedAudio?.baseUrl) + selectedAudio?.backupUrl.orEmpty(),
            durationSeconds = durationSeconds,
            minBufferSeconds = minBufferSeconds.toDouble()
        )
    }

    fun buildFromTracks(
        selectedVideo: DashVideo,
        selectedAudio: DashAudio?,
        videoUrls: List<String>,
        audioUrls: List<String>,
        durationMs: Long
    ): String {
        val durationSeconds = durationMs.coerceAtLeast(0L) / 1000.0
        return buildManifest(
            selectedVideo = selectedVideo,
            selectedAudio = selectedAudio,
            videoUrls = videoUrls,
            audioUrls = audioUrls,
            durationSeconds = durationSeconds,
            minBufferSeconds = 1.5
        )
    }

    private fun buildManifest(
        selectedVideo: DashVideo,
        selectedAudio: DashAudio?,
        videoUrls: List<String>,
        audioUrls: List<String>,
        durationSeconds: Double,
        minBufferSeconds: Double
    ): String {
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:dvb="urn:dvb:dash:profile:dvb-dash:2014"""")
            appendLine("""     profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"""")
            appendLine("""     minBufferTime="PT${formatDecimal(minBufferSeconds)}S"""")
            appendLine("""     type="static"""")
            appendLine("""     mediaPresentationDuration="PT${formatDecimal(durationSeconds)}S">""")
            appendLine("""  <Period>""")
            appendVideoAdaptationSet(selectedVideo, normalizeUrls(videoUrls))
            val playableAudioUrls = normalizeUrls(audioUrls)
            if (selectedAudio != null && playableAudioUrls.isNotEmpty()) {
                appendAudioAdaptationSet(selectedAudio, playableAudioUrls)
            }
            appendLine("""  </Period>""")
            appendLine("""</MPD>""")
        }
    }

    private fun normalizeUrls(urls: List<String>): List<String> =
        urls.map(::preferHttpsUrl).filter { it.isNotBlank() }.distinct()

    private fun StringBuilder.appendVideoAdaptationSet(video: DashVideo, urls: List<String>) {
        appendLine(
            """    <AdaptationSet mimeType="${video.mimeType.ifBlank { "video/mp4" }}" contentType="video" subsegmentAlignment="true" subsegmentStartsWithSAP="1">"""
        )
        appendRepresentation(urls = urls, video = video)
        appendLine("""    </AdaptationSet>""")
    }

    private fun StringBuilder.appendAudioAdaptationSet(audio: DashAudio, urls: List<String>) {
        appendLine(
            """    <AdaptationSet mimeType="${audio.mimeType.ifBlank { "audio/mp4" }}" contentType="audio" subsegmentAlignment="true" subsegmentStartsWithSAP="1">"""
        )
        appendAudioChannelConfiguration(audio)
        appendRepresentation(urls = urls, audio = audio)
        appendLine("""    </AdaptationSet>""")
    }

    private fun StringBuilder.appendAudioChannelConfiguration(audio: DashAudio) {
        val channelCount = resolveAudioChannelCount(audio)
        appendLine(
            """      <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="$channelCount"/>"""
        )
    }

    private fun resolveAudioChannelCount(audio: DashAudio): Int {
        val codecs = audio.codecs.lowercase()
        return when {
            codecs.startsWith("ec-3") || codecs.startsWith("eac-3") -> 6
            codecs.startsWith("ac-3") -> 6
            codecs.contains("atmos") -> 8
            codecs.startsWith("flac") -> 2
            else -> 2
        }
    }

    private fun StringBuilder.appendRepresentation(
        urls: List<String>,
        video: DashVideo? = null,
        audio: DashAudio? = null
    ) {
        val streamId = video?.id ?: audio?.id ?: 0
        val codecs = video?.codecs ?: audio?.codecs ?: ""
        val bandwidth = video?.bandwidth ?: audio?.bandwidth ?: 0
        val width = video?.width
        val height = video?.height
        val frameRate = video?.frameRate.orEmpty()
        val segmentBase = video?.segmentBase ?: audio?.segmentBase

        append("""      <Representation id="$streamId" codecs="${escapeXml(codecs)}" bandwidth="$bandwidth"""")
        if (video != null) {
            append(""" width="${width ?: 0}" height="${height ?: 0}"""")
            if (frameRate.isNotBlank()) {
                append(""" frameRate="${escapeXml(frameRate)}"""")
            }
        }
        appendLine(""">""")

        // Media3 shares its priority exclusion list across audio and video.
        val firstPriority = if (video != null) 1 else 2
        urls.forEachIndexed { index, url ->
            val priority = firstPriority + index * 2
            appendLine("""        <BaseURL dvb:priority="$priority">${escapeXml(url)}</BaseURL>""")
        }

        segmentBase?.let { appendSegmentBase(it) }
        appendLine("""      </Representation>""")
    }

    private fun StringBuilder.appendSegmentBase(segmentBase: SegmentBase) {
        val indexRange = segmentBase.indexRange?.takeIf { it.isNotBlank() } ?: return
        appendLine("""        <SegmentBase indexRange="${escapeXml(indexRange)}">""")
        segmentBase.initialization
            ?.takeIf { it.isNotBlank() }
            ?.let { initialization ->
                appendLine("""          <Initialization range="${escapeXml(initialization)}"/>""")
            }
        appendLine("""        </SegmentBase>""")
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun formatDecimal(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }
}
