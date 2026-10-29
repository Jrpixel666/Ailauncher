package dev.ailauncher.window

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Renders a single X11 window's framebuffer into an Android Surface and forwards touch
 * input back to it as synthetic X11 pointer/button events.
 *
 * For a device-usable build this is backed by an embedded minimal X11 client (XCB-based,
 * connecting to the Xvfb display the rootfs runs on), compositing just the rect for
 * [windowId] rather than the whole screen — intentionally not implemented inline here,
 * since it's a native (JNI/NDK) component. Stubbed to the surface lifecycle so the
 * Activity/task wiring above it (the actual "native Recents task" requirement) is
 * complete and testable independently of the X11 client.
 */
class XWindowSurfaceView(
    context: Context,
    private val windowId: Long,
    private val onWindowClosed: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // TODO(window-bridge): attach embedded X11 client to `windowId` on this surface.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // TODO(window-bridge): notify the X11 client of the new surface size so it can
        // resize the bridged window (XResizeWindow) to match.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onWindowClosed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // TODO(window-bridge): translate to XTestFakeMotionEvent / XTestFakeButtonEvent
        // against `windowId`.
        return true
    }

    fun release() {
        // TODO(window-bridge): detach the embedded X11 client.
    }
}
