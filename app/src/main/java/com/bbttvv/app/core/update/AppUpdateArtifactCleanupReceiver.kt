package com.bbttvv.app.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bbttvv.app.core.coroutines.AppScope
import kotlinx.coroutines.launch

/**
 * Deletes the update APK as soon as Android reports that this package was replaced.
 * The receiver runs in the upgraded process, so the installer has already finished
 * reading the file. Startup cleanup in AppUpdateController remains the fallback for
 * devices that do not deliver this broadcast or when the user cancels installation.
 */
class AppUpdateArtifactCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        AppScope.ioScope.launch {
            try {
                AppUpdateArtifacts.cleanup(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
