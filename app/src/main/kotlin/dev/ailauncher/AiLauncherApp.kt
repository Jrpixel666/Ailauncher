package dev.ailauncher

import android.app.Application
import dev.ailauncher.data.LauncherPrefs

class AiLauncherApp : Application() {
    lateinit var prefs: LauncherPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = LauncherPrefs(this)
    }
}
