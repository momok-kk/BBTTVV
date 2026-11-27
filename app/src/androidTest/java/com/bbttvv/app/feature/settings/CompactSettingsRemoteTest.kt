package com.bbttvv.app.feature.settings

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bbttvv.app.MainActivity
import com.bbttvv.app.core.update.AppUpdateRepository
import com.bbttvv.app.ui.theme.AppTheme
import com.bbttvv.app.ui.components.LocalTvNoticeHostState
import com.bbttvv.app.ui.components.rememberTvNoticeHostState
import com.bbttvv.app.ui.focus.LocalTvFocusReturn
import com.bbttvv.app.ui.focus.TvFocusReturn
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactSettingsRemoteTest {
    @Test
    fun updateDownloadShowsProgressAndBackCancelsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val halfDownloaded = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("version.properties")) {
                "versionCode=1000\nversionName=99.99\n".toResponseBody()
            } else {
                val call = chain.call()
                object : ResponseBody() {
                    private val stream = object : Source {
                        private var halfSent = false

                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (!halfSent) {
                                SystemClock.sleep(250)
                                sink.write(ByteArray(8192))
                                halfSent = true
                                halfDownloaded.countDown()
                                return 8192
                            }
                            val deadline = SystemClock.elapsedRealtime() + 15_000
                            while (!call.isCanceled() && SystemClock.elapsedRealtime() < deadline) {
                                SystemClock.sleep(25)
                            }
                            if (call.isCanceled()) canceled.countDown()
                            throw IOException("Stopped test download")
                        }

                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() = Unit
                    }.buffer()

                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = 16_384
                    override fun source() = stream
                }
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }.build()

        fun clickAction(label: String) {
            assertTrue(device.wait(Until.hasObject(By.text(label)), 5000))
            val node = instrumentation.uiAutomation.rootInActiveWindow.findLabel(label)
                ?: error("Missing accessibility label: $label")
            val action = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                ?: error("Missing accessibility action: $label")
            assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val repository = AppUpdateRepository(activity.applicationContext, httpClient)
                activity.setContent {
                    CompositionLocalProvider(
                        LocalTvNoticeHostState provides rememberTvNoticeHostState(),
                        LocalTvFocusReturn provides remember { TvFocusReturn() },
                    ) {
                        AppTheme { SettingsScreen(onBack = {}, appUpdateRepository = repository) }
                    }
                }
            }
            assertTrue(device.wait(Until.hasObject(By.text("优先最高画质")), 5000))
            repeat(6) {
                device.pressDPadDown()
                device.waitForIdle()
            }
            device.pressDPadRight()
            clickAction("检查更新")
            clickAction("下载更新")
            assertTrue("The response body is read", halfDownloaded.await(5, TimeUnit.SECONDS))
            assertTrue("Halfway progress is displayed", device.wait(Until.hasObject(By.textContains("正在下载更新 50%")), 5000))
            assertTrue(device.hasObject(By.clazz("android.widget.ProgressBar")))
            assertTrue(device.hasObject(By.text("取消下载")))

            device.pressBack()
            assertTrue(canceled.await(5, TimeUnit.SECONDS))
            assertTrue(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("检查更新"))), 5000))
            assertTrue(device.wait(Until.hasObject(By.text("检查")), 5000))
            assertFalse(device.hasObject(By.text("取消下载")))
            scenario.onActivity { activity ->
                assertFalse(File(activity.cacheDir, "updates/BBTTVV-update.apk.part").exists())
            }
        }
    }

    @Test
    fun compactRowsKeepChoiceCancellationAndCategoryNavigationReachable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var exited = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CompositionLocalProvider(
                        LocalTvNoticeHostState provides rememberTvNoticeHostState(),
                        LocalTvFocusReturn provides remember { TvFocusReturn() },
                    ) {
                        AppTheme { SettingsScreen(onBack = { exited = true }) }
                    }
                }
            }
            assertTrue(device.wait(Until.hasObject(By.text("优先最高画质")), 5000))
            device.executeShellCommand("screencap -p /data/local/tmp/bbttvv-compact-settings.png")
            device.pressDPadRight()
            device.pressDPadDown()
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("跟随普通视频")), 5000))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("跟随普通视频").focused(true)), 3000))
            device.waitForIdle()
            val focused = device.findObject(By.focused(true))
            assertTrue(focused != null && focused.hasObject(By.text("番剧和影视画质")))
            device.pressDPadDown()
            device.pressDPadCenter()
            device.waitForIdle()
            // The popup starts at the saved speed; the first option may be above the viewport.
            for (step in 0 until 12) {
                if (device.hasObject(By.text("记住上次"))) break
                device.pressDPadUp()
            }
            assertTrue(device.wait(Until.hasObject(By.text("记住上次")), 5000))
            device.pressBack()
            device.waitForIdle()
            val speedRow = device.findObject(By.focused(true))
            assertTrue(speedRow != null && speedRow.hasObject(By.text("起始倍速")))
            device.pressDPadLeft()
            device.pressDPadDown()
            device.pressDPadRight()
            assertTrue(device.wait(Until.hasObject(By.text("默认开启弹幕")), 5000))
            device.pressBack()
            device.pressBack()
            scenario.onActivity { assertEquals(true, exited) }
        }
    }

    private fun AccessibilityNodeInfo.findLabel(label: String): AccessibilityNodeInfo? {
        if (text?.toString() == label) return this
        for (index in 0 until childCount) {
            getChild(index)?.findLabel(label)?.let { return it }
        }
        return null
    }
}
