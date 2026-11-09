package com.bbttvv.app.core.update

import android.content.Context
import com.bbttvv.app.BuildConfig
import com.bbttvv.app.core.coroutines.AppScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppUpdatePromptSource {
    AUTO,
    MANUAL,
}

enum class AppUpdateEventKind {
    INFO,
    ERROR,
}

data class AppUpdatePrompt(
    val update: AvailableAppUpdate,
    val source: AppUpdatePromptSource,
)

data class AppUpdateEvent(
    val message: String,
    val kind: AppUpdateEventKind,
)

data class AppUpdateUiState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val prompt: AppUpdatePrompt? = null,
    val progress: AppUpdateDownloadProgress? = null,
    val error: String? = null,
)

/**
 * Single owner of the updater state for both the startup check and the settings page.
 *
 * Composables only render [state] and send intents here; they never write update state
 * themselves, so there is exactly one prompt slot and one download in flight. Network,
 * preference and file work runs on Dispatchers.IO, while installer intents are dispatched
 * from the main thread.
 */
object AppUpdateController {
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AppUpdateEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AppUpdateEvent> = _events.asSharedFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var updateRepository: AppUpdateRepository? = null

    @Volatile
    private var updatePreferences: AppUpdatePreferences? = null

    /** Idempotent and allocation-only: it never performs IO or blocks the first frame. */
    fun initialize(context: Context) {
        val application = context.applicationContext
        if (appContext != null) return
        synchronized(this) {
            if (appContext == null) {
                appContext = application
                updateRepository = AppUpdateRepository(application)
                updatePreferences = AppUpdatePreferences(application)
            }
        }
    }

    /** Removes APKs left by a cancelled install or a device that skipped the replace broadcast. */
    fun cleanupStaleArtifacts() {
        val context = appContext ?: return
        AppScope.ioScope.launch {
            AppUpdateArtifacts.cleanup(context)
        }
    }

    fun checkForUpdates(manual: Boolean) {
        val repository = updateRepository ?: return
        val preferences = updatePreferences ?: return
        synchronized(this) {
            val current = _state.value
            if (current.isChecking || current.isDownloading) return
            _state.update { it.copy(isChecking = true, error = null) }
        }
        AppScope.ioScope.launch {
            try {
                when (val result = repository.checkLatest(BuildConfig.VERSION_NAME)) {
                    is AppUpdateCheckResult.UpToDate -> {
                        if (manual) {
                            emit("当前已是最新版本（${result.currentVersionName}）", AppUpdateEventKind.INFO)
                        }
                    }

                    AppUpdateCheckResult.NoCompatibleApk -> {
                        if (manual) {
                            emit("当前电视架构没有可用的更新包", AppUpdateEventKind.ERROR)
                        }
                    }

                    is AppUpdateCheckResult.UpdateAvailable -> {
                        val version = result.update.versionName
                        val isNewer = shouldOfferUpdate(version, BuildConfig.VERSION_NAME)
                        val shouldPrompt = manual || shouldShowAutomaticPrompt(
                            promptedVersion = preferences.promptedVersion(),
                            candidateVersion = version,
                            isNewer = isNewer,
                        )
                        if (shouldPrompt) {
                            // Mark as prompted when the prompt is created, not when a button is
                            // pressed: Back/outside dismissal must not resurrect it on next launch.
                            preferences.markPrompted(version)
                            _state.update {
                                it.copy(
                                    prompt = AppUpdatePrompt(
                                        update = result.update,
                                        source = if (manual) {
                                            AppUpdatePromptSource.MANUAL
                                        } else {
                                            AppUpdatePromptSource.AUTO
                                        },
                                    ),
                                    error = null,
                                )
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Automatic failures stay silent so a GitHub outage never disturbs TV startup.
                if (manual) emit(resolveUpdateNetworkError(error), AppUpdateEventKind.ERROR)
            } finally {
                _state.update { it.copy(isChecking = false) }
            }
        }
    }

    fun startDownload() {
        val context = appContext ?: return
        val repository = updateRepository ?: return
        val update = synchronized(this) {
            val current = _state.value
            if (current.isDownloading) return
            val pending = current.prompt?.update ?: return
            _state.update { it.copy(isDownloading = true, error = null, progress = null) }
            pending
        }
        AppScope.ioScope.launch {
            if (!AppUpdateInstaller.canRequestInstalls(context)) {
                val message = "请先在系统设置中允许 BBTTVV 安装未知应用，然后返回此处下载。"
                _state.update { it.copy(isDownloading = false, error = message) }
                emit(message, AppUpdateEventKind.ERROR)
                withContext(Dispatchers.Main) {
                    AppUpdateInstaller.openUnknownSourcesSettings(context)
                }
                return@launch
            }
            try {
                val result = repository.downloadAndVerify(update) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                when (result) {
                    is AppUpdateDownloadResult.Rejected -> {
                        _state.update {
                            it.copy(isDownloading = false, progress = null, error = result.reason)
                        }
                        emit(result.reason, AppUpdateEventKind.ERROR)
                    }

                    is AppUpdateDownloadResult.ReadyToInstall -> {
                        _state.update {
                            it.copy(
                                isDownloading = false,
                                progress = null,
                                prompt = null,
                                error = null,
                            )
                        }
                        withContext(Dispatchers.Main) {
                            AppUpdateInstaller.requestInstall(context, result.apkFile)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = resolveUpdateNetworkError(error)
                _state.update {
                    it.copy(isDownloading = false, progress = null, error = message)
                }
                emit(message, AppUpdateEventKind.ERROR)
            }
        }
    }

    fun dismissPrompt() {
        _state.update { it.copy(prompt = null, error = null, progress = null) }
    }

    fun ignorePrompt() {
        _state.value.prompt?.update?.versionName?.let { version ->
            updatePreferences?.markPrompted(version)
        }
        _state.update { it.copy(prompt = null, error = null, progress = null) }
    }

    private fun emit(message: String, kind: AppUpdateEventKind) {
        _events.tryEmit(AppUpdateEvent(message, kind))
    }
}
