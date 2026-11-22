package com.bbttvv.app.core.util

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.BaseUrlExclusionList
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bbttvv.app.data.model.response.DashAudio
import com.bbttvv.app.data.model.response.DashVideo
import com.bbttvv.app.data.model.response.SegmentBase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DashManifestCdnTest {
    @Test
    fun media3UsesSelectedUrlsAndFallsBackWithinTheFailedTrack() {
        val videoPrimary = "https://preferred.example/video.m4s?first=1&second=2"
        val videoBackup = "https://backup.example/video.m4s"
        val audioPrimary = "https://preferred.example/audio.m4s"
        val audioBackup = "https://backup.example/audio.m4s"
        val xml = DashManifestBuilder.buildFromTracks(
            selectedVideo = DashVideo(
                codecs = "avc1.640028",
                baseUrl = "https://raw.example/video.m4s",
                segmentBase = SegmentBase(initialization = "0-799", indexRange = "800-999"),
            ),
            selectedAudio = DashAudio(
                codecs = "ec-3",
                baseUrl = "https://raw.example/audio.m4s",
                segmentBase = SegmentBase(initialization = "0-699", indexRange = "700-899"),
            ),
            videoUrls = listOf(videoPrimary, videoPrimary, videoBackup),
            audioUrls = listOf(audioPrimary, audioBackup),
            durationMs = 60_000L,
        )
        val period = DashManifestParser()
            .parse(Uri.parse("file:///manifest.mpd"), xml.byteInputStream())
            .getPeriod(0)
        val video = period.adaptationSets[0].representations.single()
        val audio = period.adaptationSets[1].representations.single()
        val locations = BaseUrlExclusionList()

        assertEquals(listOf(videoPrimary, videoBackup), video.baseUrls.map { it.url })
        assertEquals(listOf(audioPrimary, audioBackup), audio.baseUrls.map { it.url })
        assertEquals("ec-3", audio.format.codecs)
        assertEquals(6, audio.format.channelCount)
        assertEquals(800L, video.indexUri?.start)
        assertEquals(700L, audio.indexUri?.start)
        assertEquals(videoPrimary, locations.selectBaseUrl(video.baseUrls)?.url)
        assertEquals(audioPrimary, locations.selectBaseUrl(audio.baseUrls)?.url)
        locations.exclude(video.baseUrls.first(), 60_000L)
        assertEquals(videoBackup, locations.selectBaseUrl(video.baseUrls)?.url)
        assertEquals(audioPrimary, locations.selectBaseUrl(audio.baseUrls)?.url)
    }
}
