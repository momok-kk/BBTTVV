package com.bbttvv.app.core.update

import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun copyUpdateApk_reportsKnownLengthAndCompletion() {
        val payload = ByteArray(20_000) { it.toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<AppUpdateDownloadProgress.Downloading>()

        copyUpdateApk(payload.inputStream(), output, payload.size.toLong(), progress::add)

        assertArrayEquals(payload, output.toByteArray())
        assertEquals(0, progress.first().percent)
        assertEquals(100, progress.last().percent)
        assertEquals(payload.size.toLong(), progress.last().downloadedBytes)
    }

    @Test
    fun copyUpdateApk_reportsBytesWithoutPercentageWhenLengthIsUnknown() {
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<AppUpdateDownloadProgress.Downloading>()

        copyUpdateApk(byteArrayOf(1, 2, 3).inputStream(), output, -1, progress::add)

        assertEquals(AppUpdateDownloadProgress.Downloading(0, null), progress.first())
        assertEquals(AppUpdateDownloadProgress.Downloading(3, null), progress.last())
        assertNull(progress.last().percent)
    }

    @Test
    fun selectCompatibleApk_prefersDeviceAbiOrder() {
        val arm64 = GithubReleaseAsset(
            name = "BBTTV-1.28-arm64-v8a.apk",
            downloadUrl = "https://example.test/arm64.apk",
        )
        val armv7 = GithubReleaseAsset(
            name = "BBTTV-1.28-armeabi-v7a.apk",
            downloadUrl = "https://example.test/armv7.apk",
        )

        assertEquals(
            arm64,
            selectCompatibleApk(listOf(armv7, arm64), arrayOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun selectCompatibleApk_returnsNullWhenNoMatchingAbiExists() {
        val asset = GithubReleaseAsset(
            name = "BBTTV-1.28-arm64-v8a.apk",
            downloadUrl = "https://example.test/arm64.apk",
        )

        assertNull(selectCompatibleApk(listOf(asset), arrayOf("x86_64")))
    }

    @Test
    fun parsePublishedVersionProperties_readsVersionForUpdateManifest() {
        assertEquals(
            PublishedVersion(versionCode = 35, versionName = "1.33"),
            parsePublishedVersionProperties("versionCode=35\nversionName=1.33\n"),
        )
    }

    @Test
    fun resolveUpdateNetworkError_explainsDnsFailure() {
        assertEquals(
            "无法解析 GitHub 更新服务器，请检查电视的网络或 DNS 设置",
            resolveUpdateNetworkError(UnknownHostException("api.github.com")),
        )
    }
}
