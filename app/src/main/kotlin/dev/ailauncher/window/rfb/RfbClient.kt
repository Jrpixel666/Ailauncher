package dev.ailauncher.window.rfb

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Minimal RFB (VNC, RFC 6143) client, just enough to drive one `wayvnc -o <output>`
 * server per bridged Linux window: version handshake, "None"/"VNC Authentication"
 * security, Raw-encoding framebuffer updates into a [Bitmap], and pointer/key input.
 *
 * Deliberately not general-purpose (no Tight/Hextile/ZRLE, no clipboard, no resize):
 * `wayvnc -o <output>` serves a single fixed-size compositor output and AiLauncher only
 * ever talks to servers it spawned itself on 127.0.0.1, so the simplest correct subset of
 * the protocol is enough and keeps this dependency-free (no NDK, no third-party VNC lib).
 *
 * Risk to validate on a real device: this assumes the installed wayvnc/neatvnc build
 * supports the Raw encoding as a fallback when the client only advertises Raw (it isn't
 * mandated by RFC 6143 that a server support Raw specifically if it prefers something
 * else). If a given wayvnc build only speaks Tight, this client needs a Tight decoder
 * added — see README's "Known trade-offs".
 */
class RfbClient(private val host: String, private val port: Int) {

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    @Volatile private var running = false
    private var reconnecting = false

    fun connect(onFrame: (Bitmap) -> Unit, onDisconnected: () -> Unit) {
        socket = Socket(host, port).apply { tcpNoDelay = true }
        input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        output = DataOutputStream(socket.getOutputStream())

        handshakeVersion()
        handshakeSecurity()
        readServerInit()
        sendSetPixelFormat32Bpp()
        sendSetEncodings(intArrayOf(ENCODING_RAW))
        requestFullFrame()

        running = true
        Thread({ readLoop(onFrame, onDisconnected) }, "rfb-read-${port}").start()
    }

    fun requestIncrementalFrame() = sendFramebufferUpdateRequest(incremental = true)

    private fun requestFullFrame() = sendFramebufferUpdateRequest(incremental = false)

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        if (!running) return
        synchronized(output) {
            output.writeByte(5)
            output.writeByte(buttonMask)
            output.writeShort(x)
            output.writeShort(y)
            output.flush()
        }
    }

    fun sendKey(keysym: Long, down: Boolean) {
        if (!running) return
        synchronized(output) {
            output.writeByte(4)
            output.writeByte(if (down) 1 else 0)
            output.writeShort(0)
            output.writeInt(keysym.toInt())
            output.flush()
        }
    }

    fun close() {
        running = false
        runCatching { socket.close() }
    }

    // --- handshake -----------------------------------------------------------------

    private fun handshakeVersion() {
        val serverVersion = ByteArray(12).also { input.readFully(it) }
        require(String(serverVersion).startsWith("RFB 003.")) { "not an RFB server: ${String(serverVersion)}" }
        output.write("RFB 003.008\n".toByteArray())
        output.flush()
    }

    private fun handshakeSecurity() {
        val count = input.readUnsignedByte()
        require(count > 0) { "server rejected connection before security negotiation" }
        val types = ByteArray(count).also { input.readFully(it) }
        val chosen = if (types.contains(SECURITY_NONE.toByte())) SECURITY_NONE else types[0].toInt()
        output.writeByte(chosen)
        output.flush()
        if (chosen == SECURITY_VNC_AUTH) {
            // AiLauncher's wayvnc instances are started with no password/TLS config and only
            // ever reached from this process over 127.0.0.1, so this path isn't expected to be
            // exercised in practice; left correct-by-construction rather than unreachable.
            // (Validate on-device: if the installed wayvnc defaults to requiring a config
            // file/TLS cert rather than falling back to "None" security, exportOutputAsVnc's
            // wayvnc invocation needs an explicit --disable-auth-equivalent flag added.)
            ByteArray(16).also { input.readFully(it) } // challenge, ignored: no password configured
            output.write(ByteArray(16))
            output.flush()
        }
        val securityResult = input.readInt()
        if (securityResult != 0) error("RFB security handshake failed (code $securityResult)")
    }

    private fun readServerInit() {
        width = input.readUnsignedShort()
        height = input.readUnsignedShort()
        skip(16) // server's native pixel format, we override it with SetPixelFormat
        val nameLength = input.readInt()
        skip(nameLength)
    }

    private fun sendSetPixelFormat32Bpp() {
        output.writeByte(0) // SetPixelFormat
        output.write(ByteArray(3)) // padding
        output.writeByte(32) // bits-per-pixel
        output.writeByte(24) // depth
        output.writeByte(0) // big-endian flag
        output.writeByte(1) // true-colour flag
        output.writeShort(255); output.writeShort(255); output.writeShort(255) // max r/g/b
        output.writeByte(16); output.writeByte(8); output.writeByte(0) // r/g/b shift
        output.write(ByteArray(3)) // padding
        output.flush()
    }

    private fun sendSetEncodings(encodings: IntArray) {
        output.writeByte(2)
        output.writeByte(0)
        output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
    }

    private fun sendFramebufferUpdateRequest(incremental: Boolean) {
        synchronized(output) {
            output.writeByte(3)
            output.writeByte(if (incremental) 1 else 0)
            output.writeShort(0); output.writeShort(0)
            output.writeShort(width); output.writeShort(height)
            output.flush()
        }
    }

    // --- server -> client loop -------------------------------------------------------

    private fun readLoop(onFrame: (Bitmap) -> Unit, onDisconnected: () -> Unit) {
        try {
            while (running) {
                when (input.readUnsignedByte()) {
                    0 -> handleFramebufferUpdate(onFrame)
                    1 -> skipColourMapEntries()
                    2 -> Unit // Bell
                    3 -> skipServerCutText()
                }
                requestIncrementalFrame()
            }
        } catch (_: Exception) {
            // socket closed / server went away with the bridged window
        } finally {
            running = false
            onDisconnected()
        }
    }

    private fun handleFramebufferUpdate(onFrame: (Bitmap) -> Unit) {
        skip(1) // padding
        val rectCount = input.readUnsignedShort()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        repeat(rectCount) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            require(encoding == ENCODING_RAW) { "unsupported encoding $encoding" }
            val pixels = IntArray(w * h)
            val raw = ByteArray(w * h * 4)
            input.readFully(raw)
            for (i in pixels.indices) {
                val base = i * 4
                val b = raw[base].toInt() and 0xFF
                val g = raw[base + 1].toInt() and 0xFF
                val r = raw[base + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixels, 0, w, x, y, w, h)
        }
        onFrame(bitmap)
    }

    private fun skipColourMapEntries() {
        skip(1)
        skip(2)
        val count = input.readUnsignedShort()
        skip(count * 6)
    }

    private fun skipServerCutText() {
        skip(3)
        val len = input.readInt()
        skip(len)
    }

    private fun skip(n: Int) {
        var remaining = n
        while (remaining > 0) remaining -= input.skipBytes(remaining)
    }

    companion object {
        private const val SECURITY_NONE = 1
        private const val SECURITY_VNC_AUTH = 2
        private const val ENCODING_RAW = 0
    }
}
