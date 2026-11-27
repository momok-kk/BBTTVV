package com.bbttvv.app.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.OkHttpClient

private const val GITHUB_VERSION_PROPERTIES_URL =
    "https://raw.githubusercontent.com/momok-kk/BBTTVV/main/version.properties"
private const val GITHUB_LATEST_DOWNLOAD_URL =
    "https://github.com/momok-kk/BBTTVV/releases/latest/download"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private val updateDownloadMutex = Mutex()

/** A GitHub release that has a compatible APK attached. */
data class AvailableAppUpdate(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val sha256: String?,
)

sealed interface AppUpdateCheckResult {
    data object NoCompatibleApk : AppUpdateCheckResult
    data object UpToDate : AppUpdateCheckResult
    data class UpdateAvailable(val update: AvailableAppUpdate) : AppUpdateCheckResult
}

sealed interface AppUpdateDownloadResult {
    data class ReadyToInstall(val apkFile: File, val versionName: String) : AppUpdateDownloadResult
    data class Rejected(val reason: String) : AppUpdateDownloadResult
}

internal sealed interface AppUpdateDownloadProgress {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : AppUpdateDownloadProgress {
        val percent: Int?
            get() = totalBytes?.let { ((downloadedBytes.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
    }

    data object Verifying : AppUpdateDownloadProgress
}

/**
 * Reads public GitHub Releases and validates an APK before passing it to Android's installer.
 *
 * The package name, versionCode and signing certificate checks below are the upgrade-security
 * boundary. A release title or asset filename is never trusted as a version identifier.
 */
class AppUpdateRepository(
    private val appContext: Context,
    private val httpClient: OkHttpClient = updateHttpClient,
) {
    suspend fun checkLatest(): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val publishedVersion = httpClient.newCall(
            Request.Builder()
                .url(GITHUB_VERSION_PROPERTIES_URL)
                .header("User-Agent", "BBTTVV-update-checker")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException(resolveGithubHttpError(response.code, response.body.string()))
            }
            val body = response.body.string()
            parsePublishedVersionProperties(body)
        }
        val currentVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (publishedVersion.versionCode <= packageVersionCode(currentVersion)) {
            return@withContext AppUpdateCheckResult.UpToDate
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
            ?: return@withContext AppUpdateCheckResult.NoCompatibleApk
        AppUpdateCheckResult.UpdateAvailable(
            AvailableAppUpdate(
                versionName = publishedVersion.versionName,
                releaseNotes = "",
                downloadUrl = "$GITHUB_LATEST_DOWNLOAD_URL/BBTTVV-${publishedVersion.versionName}-$abi.apk",
                sha256 = null,
            )
        )
    }

    internal suspend fun downloadAndVerify(
        update: AvailableAppUpdate,
        onProgress: (AppUpdateDownloadProgress) -> Unit,
    ): AppUpdateDownloadResult =
        withContext(Dispatchers.IO) {
            updateDownloadMutex.withLock {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val target = File(updatesDir, "BBTTVV-update.apk")
                val temporary = File(updatesDir, "BBTTVV-update.apk.part")
                try {
                    val responseCode = downloadApk(update, temporary, onProgress)
                    if (responseCode !in 200..299) {
                        return@withLock AppUpdateDownloadResult.Rejected("下载失败（$responseCode）")
                    }
                    currentCoroutineContext().ensureActive()
                    onProgress(AppUpdateDownloadProgress.Verifying)
                    if (update.sha256 != null && sha256(temporary) != update.sha256.lowercase()) {
                        return@withLock AppUpdateDownloadResult.Rejected("更新包校验失败，请稍后重试")
                    }
                    when (val verification = verifyApk(temporary)) {
                        is ApkVerification.Accepted -> {
                            currentCoroutineContext().ensureActive()
                            target.delete()
                            if (!temporary.renameTo(target)) {
                                return@withLock AppUpdateDownloadResult.Rejected("无法保存更新包")
                            }
                            AppUpdateDownloadResult.ReadyToInstall(target, verification.versionName)
                        }
                        is ApkVerification.Rejected -> AppUpdateDownloadResult.Rejected(verification.reason)
                    }
                } finally {
                    temporary.delete()
                }
            }
        }

    private suspend fun downloadApk(
        update: AvailableAppUpdate,
        destination: File,
        onProgress: (AppUpdateDownloadProgress.Downloading) -> Unit,
    ): Int = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(update.downloadUrl)
            .header("Accept", APK_MIME_TYPE)
            .header("User-Agent", "BBTTVV-updater")
            .build()
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            val code = call.execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    body.byteStream().use { input ->
                        destination.outputStream().buffered().use { output ->
                            copyUpdateApk(input, output, body.contentLength(), onProgress)
                        }
                    }
                }
                response.code
            }
            continuation.resume(code)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private fun verifyApk(apk: File): ApkVerification {
        val archive = appContext.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return ApkVerification.Rejected("更新包无效")
        if (archive.packageName != appContext.packageName) {
            return ApkVerification.Rejected("更新包的应用包名不匹配")
        }
        val current = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        if (packageVersionCode(archive) <= packageVersionCode(current)) {
            return ApkVerification.Rejected("该更新包不是更高版本")
        }
        if (certificateDigests(archive) != certificateDigests(current)) {
            return ApkVerification.Rejected("更新包签名与当前应用不一致")
        }
        return ApkVerification.Accepted(archive.versionName.orEmpty())
    }
}

internal fun copyUpdateApk(
    input: InputStream,
    output: OutputStream,
    contentLength: Long,
    onProgress: (AppUpdateDownloadProgress.Downloading) -> Unit,
) {
    val totalBytes = contentLength.takeIf { it > 0 }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var downloadedBytes = 0L
    var reportedBytes = 0L
    var lastReportAt = System.nanoTime()
    onProgress(AppUpdateDownloadProgress.Downloading(0, totalBytes))
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        downloadedBytes += count
        val now = System.nanoTime()
        if (now - lastReportAt >= TimeUnit.MILLISECONDS.toNanos(200)) {
            onProgress(AppUpdateDownloadProgress.Downloading(downloadedBytes, totalBytes))
            reportedBytes = downloadedBytes
            lastReportAt = now
        }
    }
    if (downloadedBytes != reportedBytes) {
        onProgress(AppUpdateDownloadProgress.Downloading(downloadedBytes, totalBytes))
    }
}

/**
 * Releases are not Bilibili API traffic. In particular, do not reuse guestOkHttpClient here:
 * that client adds Bilibili Origin/Cookie headers and Bilibili-specific request handling.
 */
private val updateHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal data class PublishedVersion(
    val versionCode: Long,
    val versionName: String,
)

internal fun parsePublishedVersionProperties(body: String): PublishedVersion {
    val properties = Properties().apply { load(body.byteInputStream()) }
    val versionCode = properties.getProperty("versionCode")?.trim()?.toLongOrNull()
        ?: error("GitHub 版本清单缺少有效的 versionCode")
    val versionName = properties.getProperty("versionName")?.trim()
        ?.takeIf { it.matches(Regex("\\d+\\.\\d+")) }
        ?: error("GitHub 版本清单缺少有效的 versionName")
    return PublishedVersion(versionCode = versionCode, versionName = versionName)
}

private fun resolveGithubHttpError(statusCode: Int, body: String?): String {
    val message = body
        ?.substringAfter("\"message\":\"")
        ?.substringBefore('"')
        ?.takeIf { it.isNotBlank() }
    return buildString {
        append("GitHub 请求失败（HTTP $statusCode）")
        if (message != null) append("：$message")
    }
}

internal fun resolveUpdateNetworkError(error: Exception): String = when (error) {
    is java.net.UnknownHostException -> "无法解析 GitHub 更新服务器，请检查电视的网络或 DNS 设置"
    is SocketTimeoutException -> "连接 GitHub 超时，请检查电视网络后重试"
    is IOException -> "连接 GitHub 失败：${error.message ?: error.javaClass.simpleName}"
    else -> error.message ?: "检查更新失败"
}

private sealed interface ApkVerification {
    data class Accepted(val versionName: String) : ApkVerification
    data class Rejected(val reason: String) : ApkVerification
}

@Suppress("DEPRECATION")
private fun packageVersionCode(packageInfo: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()

@Suppress("DEPRECATION")
private fun certificateDigests(packageInfo: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        packageInfo.signatures.orEmpty()
    }
    return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
}

private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

@Serializable
internal data class GithubReleaseAsset(
    val name: String,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

internal fun selectCompatibleApk(
    assets: List<GithubReleaseAsset>,
    supportedAbis: Array<String>,
): GithubReleaseAsset? = supportedAbis
    .mapNotNull { abi -> assets.firstOrNull { asset -> asset.name.endsWith("-$abi.apk", ignoreCase = true) } }
    .firstOrNull()
