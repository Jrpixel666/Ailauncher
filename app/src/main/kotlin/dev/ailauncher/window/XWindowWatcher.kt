package dev.ailauncher.window

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.ailauncher.agents.ProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class XWindowInfo(val windowId: Long, val title: String, val vncPort: Int, val process: Process)

/**
 * Polls `xdotool search --onlyvisible` inside the rootfs every [POLL_INTERVAL_MS] to
 * discover/retire top-level X11 windows, and for every new window:
 *  1. allocates a loopback port,
 *  2. starts a dedicated `x11vnc -id <window>` server bound to it (via [ProotRuntime]),
 *  3. starts one [LinuxWindowActivity] for it with FLAG_ACTIVITY_NEW_DOCUMENT, so it gets
 *     its own independent Recents entry.
 *
 * Polling (rather than subscribing to the WM's own event socket) is the simpler-but-coarser
 * choice: it adds up to [POLL_INTERVAL_MS] of latency before a freshly opened window surfaces,
 * but needs no WM-specific IPC integration and works the same regardless of which lightweight
 * WM `bootstrap-arch.sh` ships. Swapping this for an event-driven watcher (e.g. icewm's
 * `--icehelp`-style IPC, or an XCB `SubstructureNotify` listener) is a drop-in replacement
 * behind the same [onWindowCreated]/[onWindowDestroyed] calls.
 */
class XWindowWatcher(private val context: Context) {

    private val runtime = ProotRuntime(context)
    private val openWindows = mutableMapOf<Long, XWindowInfo>()
    private var pollJob: Job? = null
    private var nextPort = BASE_PORT

    fun start(scope: CoroutineScope, display: Int) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce(display) }
                    .onFailure { Log.w(TAG, "window poll failed: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        openWindows.values.forEach { it.process.destroy() }
        openWindows.clear()
    }

    private fun pollOnce(display: Int) {
        val current = runtime.listTopLevelWindows(display).toMap()

        val closedIds = openWindows.keys - current.keys
        closedIds.forEach { onWindowDestroyed(it) }

        val newIds = current.keys - openWindows.keys
        for (id in newIds) {
            val title = current[id].orEmpty().ifBlank { "Linux app" }
            onWindowCreated(display, id, title)
        }
    }

    private fun onWindowCreated(display: Int, windowId: Long, title: String) {
        val port = nextPort++
        val process = runtime.exportWindowAsVnc(display, windowId, port)
        openWindows[windowId] = XWindowInfo(windowId, title, port, process)

        val intent = Intent(context, LinuxWindowActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LinuxWindowActivity.EXTRA_WINDOW_ID, windowId)
            putExtra(LinuxWindowActivity.EXTRA_VNC_PORT, port)
            putExtra(LinuxWindowActivity.EXTRA_TITLE, title)
        }
        context.startActivity(intent)
    }

    private fun onWindowDestroyed(windowId: Long) {
        openWindows.remove(windowId)?.process?.destroy()
        // The matching LinuxWindowActivity's XWindowSurfaceView notices its RFB socket
        // close (the x11vnc process we just killed) and finishes itself, which removes
        // its Recents entry (android:autoRemoveFromRecents="true" on the Activity).
    }

    companion object {
        private const val TAG = "XWindowWatcher"
        private const val POLL_INTERVAL_MS = 1500L
        private const val BASE_PORT = 5950 // clear of x11vnc's default 5900 range
    }
}
