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
 * Bridges `ailauncher-compositor` (a minimal wlroots-based Wayland compositor + XWayland host,
 * see assets/compositor/ailauncher-compositor.c) running inside the proot Arch rootfs onto the
 * Android side.
 *
 * Mechanism (same family of trick as Termux:X11 / Winlator, adapted to avoid any NDK/JNI):
 *  1. [ProotRuntime.startCompositor] runs the compositor on wlroots' headless backend (no
 *     DRM/GPU access needed under proot) with XWayland enabled for X11 app compatibility.
 *  2. The compositor itself — not a poller on this side — reports every top-level window's
 *     lifecycle as a plain stdout line (`AILAUNCHER_EVENT ...`), because it gives each bridged
 *     window a dedicated headless output of its own rather than one shared desktop. This
 *     service forwards those lines into [CompositorEventWatcher.onCompositorLine].
 *  3. Per new window, the watcher starts a dedicated `wayvnc -o <output>` server that exports
 *     just that one output's framebuffer on its own loopback port
 *     ([ProotRuntime.exportOutputAsVnc]) — the Wayland-side equivalent of `x11vnc -id`.
 *  4. It then starts one [LinuxWindowActivity] for that window, using FLAG_ACTIVITY_NEW_DOCUMENT
 *     + android:taskAffinity="" so the system gives it its own independent entry in Recents
 *     instead of stacking it onto AiLauncher's own task.
 *  5. That Activity's [XWindowSurfaceView] speaks RFB (a small pure-Kotlin client,
 *     [dev.ailauncher.window.rfb.RfbClient]) to that one port to render the window and forward
 *     touch input — so from the user's point of view the Linux GUI app behaves like any other
 *     native Android app/task.
 *
 * This service just owns the lifecycle of the compositor + watcher; it does not render
 * anything itself.
 */
class WindowBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: ProotRuntime
    private lateinit var watcher: CompositorEventWatcher
    private var compositorProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        runtime = ProotRuntime(applicationContext)
        watcher = CompositorEventWatcher(applicationContext, runtime)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (runtime.isInstalled) {
            scope.launch {
                compositorProcess = runtime.startCompositor(onLine = watcher::onCompositorLine)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher.stop()
        compositorProcess?.destroy()
        super.onDestroy()
    }

    /** Launches a Linux GUI app inside the rootfs; its window(s) surface via [CompositorEventWatcher]. */
    fun launchLinuxApp(command: String) {
        scope.launch {
            runtime.launchAgent(
                spec = dev.ailauncher.agents.AgentSpec(
                    id = "__raw__",
                    displayName = command,
                    description = "",
                    installCmd = "",
                    launchCmd = "${watcher.envPrefix()}$command",
                ),
                cwd = "/home/ailauncher",
                onOutput = {},
            )
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "ailauncher_wayland_bridge"
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
