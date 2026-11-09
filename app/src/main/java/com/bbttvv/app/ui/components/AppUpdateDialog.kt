package com.bbttvv.app.ui.components

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.bbttvv.app.core.update.AppUpdateController
import com.bbttvv.app.core.update.AppUpdateDownloadProgress
import com.bbttvv.app.core.update.AppUpdatePromptSource
import com.bbttvv.app.core.update.AvailableAppUpdate
import com.bbttvv.app.core.update.formatDownloadedSize
import com.bbttvv.app.core.update.percentOrNull
import com.bbttvv.app.ui.focus.RegisterTvFocusReturnTarget
import com.bbttvv.app.ui.theme.LocalIsLightTheme

private const val APP_UPDATE_PROMPT_FOCUS_KEY = "app_update_prompt"

/** Holds the pre-dialog focus across recompositions, including the dismissal recomposition. */
private class FocusHolder {
    @Volatile
    var view: View? = null
}

/**
 * The single update dialog used by both entry points. Both callers supply an explicit
 * return focus key: the settings page uses its stable row key, the automatic host uses
 * the pre-dialog focus captured in [AppUpdatePromptHost].
 */
@Composable
fun AppUpdateDialog(
    update: AvailableAppUpdate,
    isDownloading: Boolean,
    progress: AppUpdateDownloadProgress?,
    error: String?,
    returnFocusKey: String?,
    returnFocusFallbackKeys: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onIgnore: () -> Unit,
    onUpdate: () -> Unit,
) {
    val isLightTheme = LocalIsLightTheme.current
    val bodyColor = if (isLightTheme) Color(0xFF61666D) else Color(0xDDEAF2F8)

    TvDialog(
        title = "发现新版本 ${update.versionName}",
        onDismissRequest = onDismiss,
        returnFocusKey = returnFocusKey,
        returnFocusFallbackKeys = returnFocusFallbackKeys,
        content = {
            Text(
                text = update.releaseNotes.takeIf { it.isNotBlank() }
                    ?: "已发现适用于此电视的更新包。",
                color = bodyColor,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            if (isDownloading) {
                AppUpdateProgressContent(
                    progress = progress,
                    bodyColor = bodyColor,
                    isLightTheme = isLightTheme,
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                )
            }
        },
        actions = {
            TvDialogActionButton(
                text = "忽略此版本",
                onClick = { if (!isDownloading) onIgnore() },
            )
            TvDialogActionButton(
                text = if (isDownloading) "下载中" else "下载更新",
                onClick = { if (!isDownloading) onUpdate() },
            )
        },
    )
}

/** Global host for the startup check prompt. The settings page renders only MANUAL prompts. */
@Composable
fun AppUpdatePromptHost() {
    val hostView = LocalView.current
    val focusHolder = remember { FocusHolder() }
    val state by AppUpdateController.state.collectAsStateWithLifecycle()
    val prompt = state.prompt?.takeIf { it.source == AppUpdatePromptSource.AUTO }

    // Read once during the composition that first shows the prompt, before the Dialog
    // window takes focus. Progress recompositions keep the same key; SideEffect commits
    // the holder only after a successful composition, and dismissal leaves it intact so
    // onDispose can still restore the original focus.
    val promptVersion = prompt?.update?.versionName
    val capturedFocus: View? = remember(promptVersion) {
        if (promptVersion != null) hostView.rootView?.findFocus() else null
    }
    SideEffect {
        if (promptVersion != null) {
            focusHolder.view = capturedFocus
        }
    }

    RegisterTvFocusReturnTarget(
        key = APP_UPDATE_PROMPT_FOCUS_KEY,
        requestFocus = {
            val target = focusHolder.view
            val restored = target != null &&
                target.isAttachedToWindow &&
                target.isShown &&
                target.isEnabled &&
                target.isFocusable &&
                runCatching { target.requestFocus() }.getOrDefault(false)
            restored || runCatching { hostView.requestFocus() }.getOrDefault(false)
        },
    )

    if (prompt == null) return

    AppUpdateDialog(
        update = prompt.update,
        isDownloading = state.isDownloading,
        progress = state.progress,
        error = state.error,
        returnFocusKey = APP_UPDATE_PROMPT_FOCUS_KEY,
        returnFocusFallbackKeys = listOf("home"),
        onDismiss = AppUpdateController::dismissPrompt,
        onIgnore = AppUpdateController::ignorePrompt,
        onUpdate = AppUpdateController::startDownload,
    )
}

@Composable
private fun AppUpdateProgressContent(
    progress: AppUpdateDownloadProgress?,
    bodyColor: Color,
    isLightTheme: Boolean,
) {
    val percent = progress?.percentOrNull()
    val label = when {
        progress == null -> "正在准备下载…"
        percent != null ->
            "正在下载 $percent%  ·  已下载 ${formatDownloadedSize(progress.bytesDownloaded)}"
        else ->
            "正在下载（大小未知）  ·  已下载 ${formatDownloadedSize(progress.bytesDownloaded)}"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = bodyColor, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (isLightTheme) Color(0x1A000000) else Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((percent?.div(100f) ?: 0f).coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isLightTheme) Color(0xFFFB7299) else Color(0xFF00AEEC)),
            )
        }
        if (percent == null) {
            Text(
                text = "服务器未提供文件大小，进度以已下载体积显示。",
                color = bodyColor,
                fontSize = 12.sp,
            )
        }
    }
}
