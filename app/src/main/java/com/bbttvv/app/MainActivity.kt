package com.bbttvv.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.bbttvv.app.app.BbtvApplication
import com.bbttvv.app.navigation.AppNavigation
import com.bbttvv.app.system.LauncherResumeRequest
import com.bbttvv.app.system.TclPlaybackHistory
import com.bbttvv.app.ui.components.LocalTvNoticeHostState
import com.bbttvv.app.ui.components.TvNoticeHost
import com.bbttvv.app.ui.components.rememberTvNoticeHostState
import com.bbttvv.app.ui.focus.LocalTvFocusEscapeGuard
import com.bbttvv.app.ui.focus.LocalTvFocusReturn
import com.bbttvv.app.ui.focus.GridFocusDebugLog
import com.bbttvv.app.ui.focus.TvFocusEscapeGuard
import com.bbttvv.app.ui.focus.TvFocusReturn
import com.bbttvv.app.ui.theme.AppTheme

/**
 * 应用主 Activity
 *
 * 通过 CompositionLocalProvider 注入 TvFocusEscapeGuard 和 TvFocusReturn 全局焦点基础设施。
 * dispatchKeyEvent 中优先由 TvFocusEscapeGuard 处理焦点逃逸事件。
 * 首帧渲染后通知 BbtvApplication.onFirstFrameRendered() 触发延迟启动任务。
 */
class MainActivity : ComponentActivity() {
    private val tvFocusEscapeGuard = TvFocusEscapeGuard()
    private val tvFocusReturn = TvFocusReturn()
    private var launcherResume by mutableStateOf<LauncherResumeRequest?>(null)
    private var launcherResumeConsumed = false

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherResumeConsumed = savedInstanceState?.getBoolean("launcherResumeConsumed") ?: false
        if (!launcherResumeConsumed) {
            launcherResume = TclPlaybackHistory.resumeRequest(intent, packageName)
        }
        isDebugInspectorInfoEnabled = false
        setContent {
            val tvNoticeHostState = rememberTvNoticeHostState()
            val startupTheme = androidx.compose.runtime.remember {
                com.bbttvv.app.core.store.SettingsManager.getThemeModeSync(this)
            }

            LaunchedEffect(Unit) {
                withFrameNanos { }
                (application as? BbtvApplication)?.onFirstFrameRendered()
                try {
                    if (com.bbttvv.app.core.store.SettingsManager.reconcileThemeCache(this@MainActivity)) {
                        recreate()
                    }
                } catch (e: java.io.IOException) {
                    android.util.Log.w("MainActivity", "Theme preference unavailable at startup", e)
                }
            }
            AppTheme(themeMode = startupTheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    CompositionLocalProvider(
                        LocalTvFocusEscapeGuard provides tvFocusEscapeGuard,
                        LocalTvFocusReturn provides tvFocusReturn,
                        LocalTvNoticeHostState provides tvNoticeHostState,
                    ) {
                        AppNavigation(
                            launcherResume = launcherResume,
                            onLauncherResumeConsumed = {
                                launcherResume = null
                                launcherResumeConsumed = true
                            },
                        )
                    }
                    TvNoticeHost(state = tvNoticeHostState)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val request = TclPlaybackHistory.resumeRequest(intent, packageName) ?: return
        setIntent(intent)
        launcherResumeConsumed = false
        launcherResume = request
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launcherResumeConsumed", launcherResumeConsumed)
        super.onSaveInstanceState(outState)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        GridFocusDebugLog.d {
            "MainActivity.dispatchKeyEvent before ${GridFocusDebugLog.event(event)} " +
                GridFocusDebugLog.view(currentFocus)
        }
        val recovered = tvFocusEscapeGuard.handleKeyEvent(event, currentFocus)
        GridFocusDebugLog.d {
            "MainActivity.dispatchKeyEvent guardResult=$recovered ${GridFocusDebugLog.event(event)} " +
                GridFocusDebugLog.view(currentFocus)
        }
        if (recovered) {
            return true
        }
        val handled = super.dispatchKeyEvent(event)
        GridFocusDebugLog.d {
            "MainActivity.dispatchKeyEvent superResult=$handled ${GridFocusDebugLog.event(event)} " +
                GridFocusDebugLog.view(currentFocus)
        }
        return handled
    }
}
