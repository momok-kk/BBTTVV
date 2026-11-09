package com.bbttvv.app.core.update

import android.content.Context

/**
 * Persists the already-prompted release version. SharedPreferences is used here because the
 * startup check runs on Dispatchers.IO and only needs a synchronous, process-restart-safe value.
 */
internal class AppUpdatePreferences(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun promptedVersion(): String? = preferences.getString(KEY_PROMPTED_VERSION, null)

    fun isPrompted(version: String): Boolean = promptedVersion() == version

    fun markPrompted(version: String) {
        preferences.edit().putString(KEY_PROMPTED_VERSION, version).apply()
    }

    private companion object {
        const val PREFS_NAME = "app_update_state"
        const val KEY_PROMPTED_VERSION = "prompted_version"
    }
}
