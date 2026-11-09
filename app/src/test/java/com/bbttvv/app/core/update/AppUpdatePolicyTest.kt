package com.bbttvv.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun `normalizeVersionName strips tag and build suffixes`() {
        assertEquals("1.30", normalizeVersionName("v1.30"))
        assertEquals("1.00", normalizeVersionName("1.00-debug"))
        assertEquals("1.30.2", normalizeVersionName("release-1.30.2"))
        assertNull(normalizeVersionName(""))
        assertNull(normalizeVersionName(null))
        assertNull(normalizeVersionName("nightly"))
    }

    @Test
    fun `resolveReleaseVersion prefers tag over asset filename`() {
        assertEquals(
            "1.31",
            resolveReleaseVersion("v1.31", "BBTTVV-1.30-arm64-v8a.apk"),
        )
        assertEquals(
            "1.30",
            resolveReleaseVersion(null, "BBTTVV-1.30-arm64-v8a.apk"),
        )
        assertNull(resolveReleaseVersion("nightly", "BBTTVV-universal.apk"))
    }

    @Test
    fun `compareVersionNames handles dotted numeric versions`() {
        assertEquals(0, compareVersionNames("v1.30", "1.30"))
        assertEquals(0, compareVersionNames("1.30", "1.30.0"))
        assertEquals(1, compareVersionNames("1.31", "1.30"))
        assertEquals(-1, compareVersionNames("1.29", "1.30"))
        assertEquals(1, compareVersionNames("1.30.1", "1.30"))
        assertEquals(-1, compareVersionNames("1.00-debug", "1.30"))
        assertNull(compareVersionNames("nightly", "1.30"))
    }

    @Test
    fun `same or older release is not offered as an update`() {
        assertFalse(shouldOfferUpdate("1.30", "1.30"))
        assertFalse(shouldOfferUpdate("v1.29", "1.30"))
        assertTrue(shouldOfferUpdate("v1.31", "1.30"))
        // Unknown versions must not be reported as "already up to date".
        assertTrue(shouldOfferUpdate(null, "1.30"))
    }

    @Test
    fun `automatic prompt appears once per version`() {
        assertFalse(shouldShowAutomaticPrompt("1.31", "1.31", isNewer = true))
        assertTrue(shouldShowAutomaticPrompt("1.30", "1.31", isNewer = true))
        assertTrue(shouldShowAutomaticPrompt(null, "1.31", isNewer = true))
        assertFalse(shouldShowAutomaticPrompt(null, "1.31", isNewer = false))
    }

    @Test
    fun `download percent covers boundary values`() {
        assertNull(resolveDownloadPercent(0L, -1L))
        assertNull(resolveDownloadPercent(0L, 0L))
        assertEquals(0, resolveDownloadPercent(0L, 1000L))
        assertEquals(50, resolveDownloadPercent(500L, 1000L))
        assertEquals(99, resolveDownloadPercent(999L, 1000L))
        assertEquals(100, resolveDownloadPercent(1000L, 1000L))
        assertEquals(100, resolveDownloadPercent(1200L, 1000L))
    }

    @Test
    fun `downloaded size uses one decimal MB`() {
        assertEquals("0.0 MB", formatDownloadedSize(0L))
        assertEquals("1.0 MB", formatDownloadedSize(1024L * 1024L))
        assertEquals("2.5 MB", formatDownloadedSize((2.5 * 1024 * 1024).toLong()))
    }
}
