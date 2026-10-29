package dev.ailauncher.agents

import android.content.Context
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Thin wrapper around a proot-distro managed Arch Linux rootfs, extracted under this app's
 * private storage (`/data/data/dev.ailauncher/files/`). No root is required: proot uses
 * ptrace-based syscall interception to fake chroot/uid/gid inside the app's own sandbox.
 *
 * This mirrors the approach Termux's `proot-distro` plugin uses, trimmed to just what
 * AiLauncher needs: install the rootfs once, run bootstrap-arch.sh, then exec commands
 * (agent CLIs, X11 apps) inside it as the unprivileged `ailauncher` user.
 */
class ProotRuntime(private val context: Context) {

    private val filesDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(filesDir, "archlinux")
    private val binDir: File get() = File(filesDir, "usr/bin") // bundled static proot + talloc

    val isInstalled: Boolean get() = File(rootfsDir, "etc/os-release").exists()

    /** Downloads + extracts the official Arch Linux ARM/x86_64 bootstrap tarball for the device's ABI. */
    fun installRootfs(onProgress: (String) -> Unit): Result<Unit> = runCatching {
        onProgress("Selecting Arch Linux bootstrap for ${deviceAbi()}")
        val tarballUrl = archBootstrapUrlFor(deviceAbi())
        val tarball = File(context.cacheDir, "archlinux-bootstrap.tar.gz")

        onProgress("Downloading $tarballUrl")
        downloadTo(tarballUrl, tarball)

        onProgress("Extracting rootfs (proot, no root needed)")
        rootfsDir.mkdirs()
        runProot(
            args = listOf("tar", "-xzf", tarball.absolutePath, "-C", rootfsDir.absolutePath),
            asLoginUser = false,
        )

        onProgress("Running bootstrap-arch.sh")
        val bootstrap = File(context.filesDir, "bootstrap-arch.sh").apply {
            writeText(context.assets.open("bootstrap-arch.sh").bufferedReader().readText())
            setExecutable(true)
        }
        runInRootfs(listOf("bash", bootstrap.absolutePath), asUser = "root")

        tarball.delete()
    }

    fun installAgent(spec: AgentSpec, onOutput: (String) -> Unit): Result<Unit> = runCatching {
        onOutput("Installing ${spec.displayName}…")
        runInRootfs(listOf("bash", "-lc", spec.installCmd), asUser = "ailauncher", onOutput = onOutput)
        onOutput("${spec.displayName} installed.")
    }

    /** Launches an agent CLI session; stdout/stderr are streamed to [onOutput] for the chat UI. */
    fun launchAgent(spec: AgentSpec, cwd: String, onOutput: (String) -> Unit): Process {
        return execInRootfsAsync(listOf("bash", "-lc", "cd '$cwd' && ${spec.launchCmd}"), asUser = "ailauncher", onOutput = onOutput)
    }

    /**
     * Starts a virtual X server + a minimal window manager (icewm) inside the rootfs.
     * icewm is what gives `xdotool`/`x11vnc` sane top-level window geometry to key off of;
     * without a WM, apps' windows have no decorations/stacking and window discovery via
     * `xdotool search` is unreliable.
     */
    fun startXServer(display: Int = 0): Process {
        return execInRootfsAsync(
            listOf(
                "bash", "-lc",
                "Xvfb :$display -screen 0 1280x800x24 & sleep 1 && " +
                    "dbus-launch --exit-with-session icewm-session",
            ),
            asUser = "ailauncher",
            onOutput = { Log.d("ProotRuntime", it) },
        )
    }

    /** Lists currently open top-level window ids + titles via `xdotool`, on the given [display]. */
    fun listTopLevelWindows(display: Int): List<Pair<Long, String>> {
        val out = execInRootfsSync(
            listOf(
                "bash", "-lc",
                "DISPLAY=:$display xdotool search --onlyvisible --name '' 2>/dev/null",
            ),
            asUser = "ailauncher",
        )
        return out.lines().mapNotNull { line ->
            val id = line.trim().toLongOrNull() ?: return@mapNotNull null
            val title = execInRootfsSync(
                listOf("bash", "-lc", "DISPLAY=:$display xdotool getwindowname $id 2>/dev/null"),
                asUser = "ailauncher",
            ).trim()
            id to title
        }
    }

    /**
     * Serves exactly one X11 window (by [windowId]) as its own tiny VNC server on
     * 127.0.0.1:[port], via `x11vnc -id`. This — not a whole-screen VNC/RDP session — is
     * what lets each Linux GUI window map onto its own Android task: the Kotlin-side RFB
     * client in [dev.ailauncher.window.XWindowSurfaceView] connects to exactly that port and
     * sees exactly that one window's framebuffer.
     */
    fun exportWindowAsVnc(display: Int, windowId: Long, port: Int): Process {
        return execInRootfsAsync(
            listOf(
                "bash", "-lc",
                "x11vnc -display :$display -id 0x${windowId.toString(16)} " +
                    "-rfbport $port -localhost -forever -shared -noxdamage -nopw -quiet",
            ),
            asUser = "ailauncher",
            onOutput = { Log.d("ProotRuntime", "x11vnc[$windowId]: $it") },
        )
    }

    private fun execInRootfsSync(cmd: List<String>, asUser: String): String {
        val process = ProcessBuilder(buildProotCommand(asUser, cmd)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    private fun runInRootfs(cmd: List<String>, asUser: String, onOutput: (String) -> Unit = {}) {
        val p = execInRootfsAsync(cmd, asUser, onOutput)
        val code = p.waitFor()
        if (code != 0) error("command $cmd exited with $code")
    }

    private fun execInRootfsAsync(cmd: List<String>, asUser: String, onOutput: (String) -> Unit): Process {
        val process = ProcessBuilder(buildProotCommand(asUser, cmd)).redirectErrorStream(true).start()
        Thread {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                lines.forEach(onOutput)
            }
        }.start()
        return process
    }

    private fun buildProotCommand(asUser: String, cmd: List<String>): List<String> {
        val prootBin = File(binDir, "proot").absolutePath
        return buildList {
            add(prootBin)
            add("-0") // fake root inside the sandbox, still unprivileged on the host
            add("-r"); add(rootfsDir.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-w"); add("/")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/home/$asUser")
            add("USER=$asUser")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin")
            add("DISPLAY=:0")
            add("/bin/su"); add("-"); add(asUser); add("-c")
            add(cmd.joinToString(" ") { it })
        }
    }

    private fun runProot(args: List<String>, asLoginUser: Boolean) {
        val prootBin = File(binDir, "proot").absolutePath
        ProcessBuilder(listOf(prootBin, "-0") + args).redirectErrorStream(true).start().waitFor()
    }

    private fun deviceAbi(): String = when (System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        else -> "aarch64"
    }

    private fun archBootstrapUrlFor(abi: String): String =
        "https://archlinuxarm.org/os/ArchLinuxARM-$abi-latest.tar.gz".takeIf { abi == "aarch64" }
            ?: "https://geo.mirror.pkgbuild.com/iso/latest/archlinux-bootstrap-x86_64.tar.zst"

    private fun downloadTo(url: String, dest: File) {
        java.net.URL(url).openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
