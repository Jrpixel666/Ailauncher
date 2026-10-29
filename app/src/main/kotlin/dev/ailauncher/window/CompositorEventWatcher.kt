package dev.ailauncher.window

import android.content.Context
import android.content.Intent
import dev.ailauncher.agents.ProotRuntime

data class BridgedWindow(
    val id: Long,
    val outputName: String,
    val title: String,
    val vncPort: Int,
    val vncProcess: Process,
)

/**
 * Event-driven replacement for the old polling `xdotool`-based watcher: `ailauncher-compositor`
 * (see assets/compositor/ailauncher-compositor.c) itself prints one `AILAUNCHER_EVENT ...` line
 * per window/display lifecycle event on its stdout, which [WindowBridgeService] feeds line-by-line
 * into [onCompositorLine] as it streams the compositor process's output — no latency window
 * between a surface mapping and it getting bridged, unlike the previous ~1.5s poll interval.
 *
 * Recognised lines:
 *   AILAUNCHER_EVENT WAYLAND <socket-name>        compositor's own Wayland socket is up
 *   AILAUNCHER_EVENT XWAYLAND <display-name>      XWayland started lazily, e.g. ":1"
 *   AILAUNCHER_EVENT CREATE <id> <output> <title> a new top-level window got its own output
 *   AILAUNCHER_EVENT DESTROY <id>                 that window closed
 *
 * For every CREATE, a dedicated `wayvnc -o <output>` is started on its own loopback port
 * ([ProotRuntime.exportOutputAsVnc]) and one [LinuxWindowActivity] is launched with
 * FLAG_ACTIVITY_NEW_DOCUMENT, so it gets its own independent Recents entry.
 */
class CompositorEventWatcher(private val context: Context, private val runtime: ProotRuntime) {

    private val windows = mutableMapOf<Long, BridgedWindow>()
    private var nextPort = BASE_PORT

    var waylandSocket: String? = null
        private set
    var xwaylandDisplay: String? = null
        private set

    fun onCompositorLine(line: String) {
        val parts = line.trim().split(' ')
        if (parts.size < 2 || parts[0] != "AILAUNCHER_EVENT") return
        when (parts[1]) {
            "WAYLAND" -> waylandSocket = parts.getOrNull(2)
            "XWAYLAND" -> xwaylandDisplay = parts.getOrNull(2)
            "CREATE" -> {
                val id = parts.getOrNull(2)?.toLongOrNull() ?: return
                val output = parts.getOrNull(3) ?: return
                val title = parts.drop(4).joinToString(" ").ifBlank { "Linux app" }
                onWindowCreated(id, output, title)
            }
            "DESTROY" -> {
                val id = parts.getOrNull(2)?.toLongOrNull() ?: return
                onWindowDestroyed(id)
            }
        }
    }

    fun stop() {
        windows.values.forEach { it.vncProcess.destroy() }
        windows.clear()
    }

    /** Env prefix GUI app launches should use so they land on this compositor's displays. */
    fun envPrefix(): String {
        val wayland = waylandSocket?.let { "WAYLAND_DISPLAY=$it " } ?: ""
        val x11 = xwaylandDisplay?.let { "DISPLAY=$it " } ?: ""
        return "$wayland$x11"
    }

    private fun onWindowCreated(id: Long, outputName: String, title: String) {
        val port = nextPort++
        val vncProcess = runtime.exportOutputAsVnc(outputName, port)
        windows[id] = BridgedWindow(id, outputName, title, port, vncProcess)

        val intent = Intent(context, LinuxWindowActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LinuxWindowActivity.EXTRA_WINDOW_ID, id)
            putExtra(LinuxWindowActivity.EXTRA_VNC_PORT, port)
            putExtra(LinuxWindowActivity.EXTRA_TITLE, title)
        }
        context.startActivity(intent)
    }

    private fun onWindowDestroyed(id: Long) {
        windows.remove(id)?.vncProcess?.destroy()
        // LinuxWindowActivity's XWindowSurfaceView notices its RFB socket close (the wayvnc
        // process we just killed) and finishes itself, which removes its Recents entry
        // (android:autoRemoveFromRecents="true" on the Activity).
    }

    companion object {
        private const val BASE_PORT = 5950 // clear of VNC's conventional 5900 range
    }
}
