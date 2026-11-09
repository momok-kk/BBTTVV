package com.bbttvv.app.core.update

import android.content.Context
import java.io.File

/**
 * Owns the on-disk update APK. The directory must stay in sync with
 * res/xml/update_file_paths.xml (cache-path "updates/") used by the FileProvider.
 */
internal object AppUpdateArtifacts {
    const val APK_FILE_NAME = "BBTTVV-update.apk"
    const val PART_FILE_NAME = "BBTTVV-update.apk.part"

    private const val UPDATES_DIR = "updates"

    fun updatesDir(context: Context): File = File(context.applicationContext.cacheDir, UPDATES_DIR)

    /**
     * Removes every leftover update file. Callers must only invoke this when the system
     * installer is not reading the APK: after a package replacement, at app startup, or
     * immediately before a new download starts.
     */
    fun cleanup(context: Context) {
        updatesDir(context).listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }
}
