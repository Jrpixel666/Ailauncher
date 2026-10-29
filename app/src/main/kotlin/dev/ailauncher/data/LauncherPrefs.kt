package dev.ailauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight first-run / setup state. Kept as plain SharedPreferences (not DataStore)
 * so it can be read synchronously at app start before Compose is up.
 */
class LauncherPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("ailauncher_prefs", Context.MODE_PRIVATE)

    var setupCompleted: Boolean
        get() = sp.getBoolean(KEY_SETUP_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    var rootfsInstalled: Boolean
        get() = sp.getBoolean(KEY_ROOTFS_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_ROOTFS_DONE, value).apply()

    var installedAgentIds: Set<String>
        get() = sp.getStringSet(KEY_AGENTS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_AGENTS, value).apply()

    companion object {
        private const val KEY_SETUP_DONE = "setup_completed"
        private const val KEY_ROOTFS_DONE = "rootfs_installed"
        private const val KEY_AGENTS = "installed_agents"
    }
}
