package dev.ailauncher.window

import android.content.Context
import android.content.Intent

data class XWindowInfo(val windowId: Long, val title: String, val pid: Int)

/**
 * Watches the in-rootfs window manager's event stream and mirrors each top-level X11
 * window as its own Android task via [LinuxWindowActivity].
 *
 * The real implementation shells out to `xdotool`/the WM's IPC (e.g. herbstclient's event
 * stream) for window `create`/`destroy`/`title` notifications; wiring that pipe up is a
 * device-side integration step (it depends on exactly which X server + WM AiLauncher ships
 * in `bootstrap-arch.sh`), tracked in README.md's "Linux window bridge" section. The
 * public surface below — [start] and [onWindowCreated]/[onWindowDestroyed] — is what the
 * rest of the app depends on, so that plumbing can land independently.
 */
class XWindowWatcher(private val context: Context) {

    private val openWindows = mutableMapOf<Long, XWindowInfo>()

    fun start(display: Int) {
        // TODO(window-bridge): subscribe to the WM event socket at
        // /data/data/dev.ailauncher/files/archlinux/tmp/.X11-unix/ wm-events and call
        // onWindowCreated/onWindowDestroyed as events arrive. Left unimplemented here —
        // see class doc.
    }

    fun onWindowCreated(info: XWindowInfo) {
        openWindows[info.windowId] = info
        val intent = Intent(context, LinuxWindowActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(LinuxWindowActivity.EXTRA_WINDOW_ID, info.windowId)
            putExtra(LinuxWindowActivity.EXTRA_TITLE, info.title)
        }
        context.startActivity(intent)
    }

    fun onWindowDestroyed(windowId: Long) {
        openWindows.remove(windowId)
        // The corresponding LinuxWindowActivity observes the same destroy event (polled via
        // its bound XWindowSurfaceView) and finishes itself, which auto-removes its Recents
        // entry (android:autoRemoveFromRecents="true" on LinuxWindowActivity).
    }
}
