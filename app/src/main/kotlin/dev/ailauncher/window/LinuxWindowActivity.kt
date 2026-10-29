package dev.ailauncher.window

import android.app.Activity
import android.os.Bundle

/**
 * One instance of this Activity == one bridged Linux top-level window (native Wayland or an
 * XWayland-hosted X11 app) == one entry in Android Recents, independent of AiLauncher's own
 * task (see android:taskAffinity="" and documentLaunchMode="always" on this Activity in
 * AndroidManifest.xml, plus FLAG_ACTIVITY_NEW_DOCUMENT used when it's started from
 * [CompositorEventWatcher]).
 *
 * It renders the window by speaking RFB/VNC to the dedicated `wayvnc -o <output>` server
 * [CompositorEventWatcher] started for just this one window's own compositor output, on
 * 127.0.0.1:[EXTRA_VNC_PORT]. Its title/icon shown in Recents is taken from that window's own
 * title, via setTaskDescription, so each Recents card reads as the Linux app itself rather
 * than as "AiLauncher".
 */
class LinuxWindowActivity : Activity() {

    private lateinit var surface: XWindowSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra(EXTRA_VNC_PORT, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Linux app"

        setTaskDescription(android.app.ActivityManager.TaskDescription(title))

        surface = XWindowSurfaceView(this, port) { finish() }
        setContentView(surface)
    }

    override fun onDestroy() {
        surface.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WINDOW_ID = "window_id"
        const val EXTRA_VNC_PORT = "vnc_port"
        const val EXTRA_TITLE = "title"
    }
}
