package dev.ailauncher.window

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * One instance of this Activity == one bridged Linux (X11) top-level window == one entry
 * in Android Recents, independent of AiLauncher's own task (see android:taskAffinity=""
 * and documentLaunchMode="always" on this Activity in AndroidManifest.xml, plus
 * FLAG_ACTIVITY_NEW_DOCUMENT used when it's started from [XWindowWatcher]).
 *
 * Its title/icon shown in Recents is taken from the bridged window's own X11 WM_NAME,
 * via setTaskDescription, so each Recents card reads as the Linux app itself rather than
 * as "AiLauncher".
 */
class LinuxWindowActivity : Activity() {

    private var windowId: Long = -1
    private lateinit var surface: XWindowSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowId = intent.getLongExtra(EXTRA_WINDOW_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Linux app"

        setTaskDescription(android.app.ActivityManager.TaskDescription(title))

        surface = XWindowSurfaceView(this, windowId) { finish() }
        setContentView(surface)
    }

    override fun onDestroy() {
        surface.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WINDOW_ID = "window_id"
        const val EXTRA_TITLE = "title"
    }
}
