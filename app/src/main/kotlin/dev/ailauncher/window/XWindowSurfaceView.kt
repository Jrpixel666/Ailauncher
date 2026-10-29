package dev.ailauncher.window

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.ailauncher.window.rfb.RfbClient
import kotlin.concurrent.thread

/**
 * Renders a single bridged X11 window's framebuffer into this Android Surface, and
 * forwards touch input back to it, by speaking RFB (VNC) to the per-window `x11vnc`
 * server [WindowBridgeService]/[XWindowWatcher] started for it on 127.0.0.1:[port].
 *
 * One [RfbClient] per view == one window's pixels, scaled to fit the Activity's current
 * size; touch coordinates are mapped back into the remote window's own coordinate space
 * before being sent as pointer events, and a single-finger drag/tap is treated as a
 * left-button press for the duration of the gesture (right-click/scroll gestures are a
 * follow-up, not required for the core "window shows up and is usable" behavior).
 */
class XWindowSurfaceView(
    context: Context,
    private val port: Int,
    private val onWindowClosed: () -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

    private var client: RfbClient? = null
    private var latestFrame: Bitmap? = null

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        thread(name = "rfb-connect-$port") {
            runCatching {
                val c = RfbClient("127.0.0.1", port)
                client = c
                c.connect(
                    onFrame = { bitmap ->
                        latestFrame = bitmap
                        post { drawFrame() }
                    },
                    onDisconnected = { post { onWindowClosed() } },
                )
            }.onFailure { post { onWindowClosed() } }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        drawFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        release()
    }

    private fun drawFrame() {
        val bitmap = latestFrame ?: return
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height))
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return true
        val frame = latestFrame ?: return true
        if (width == 0 || height == 0) return true

        val remoteX = (event.x / width * frame.width).toInt().coerceIn(0, frame.width - 1)
        val remoteY = (event.y / height * frame.height).toInt().coerceIn(0, frame.height - 1)
        val buttonMask = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 1
            else -> 0
        }
        c.sendPointer(remoteX, remoteY, buttonMask)
        return true
    }

    fun release() {
        client?.close()
        client = null
        latestFrame = null
    }
}
