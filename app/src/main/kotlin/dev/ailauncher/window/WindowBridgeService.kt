package dev.ailauncher.window

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.ailauncher.agents.ProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges the Xvfb display running inside the proot Arch rootfs onto the Android side.
 *
 * Mechanism (same family of trick as Termux:X11 / Winlator):
 *  1. A lightweight X11 window manager (herbstluftwm) runs inside the rootfs and reports
 *     window create/destroy/title/geometry events over its IPC socket.
 *  2. [XWindowWatcher] listens for those events and, for every new top-level window,
 *     starts one [dev.ailauncher.window.LinuxWindowActivity] instance carrying that
 *     window's id, using FLAG_ACTIVITY_NEW_DOCUMENT + android:taskAffinity="" so the
 *     system gives it its own independent entry in Recents instead of stacking it onto
 *     AiLauncher's own task.
 *  3. Each LinuxWindowActivity renders that one X window's pixels via a local X11 client
 *     (embedded, not shelled out) into a SurfaceView, and forwards touch/key input back
 *     to the same window over the X protocol — so from the user's point of view the Linux
 *     GUI app behaves like any other native Android app/task.
 *
 * This service just owns the lifecycle of the X server + window manager + watcher; it does
 * not render anything itself.
 */
class WindowBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: ProotRuntime
    private lateinit var watcher: XWindowWatcher

    override fun onCreate() {
        super.onCreate()
        runtime = ProotRuntime(applicationContext)
        watcher = XWindowWatcher(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (runtime.isInstalled) {
            scope.launch {
                runtime.startXServer(display = 0)
                watcher.start(display = 0)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Launches a Linux GUI app inside the rootfs; its window(s) surface via [XWindowWatcher]. */
    fun launchLinuxApp(command: String) {
        scope.launch {
            runtime.launchAgent(
                spec = dev.ailauncher.agents.AgentSpec(
                    id = "__raw__",
                    displayName = command,
                    description = "",
                    installCmd = "",
                    launchCmd = "DISPLAY=:0 $command",
                ),
                cwd = "/home/ailauncher",
                onOutput = {},
            )
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "ailauncher_x11_bridge"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Linux desktop bridge", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AiLauncher")
            .setContentText("Linux desktop bridge active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
    }
}
