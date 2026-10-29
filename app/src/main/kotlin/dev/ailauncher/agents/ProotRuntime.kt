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
 * (agent CLIs, Linux GUI apps) inside it as the unprivileged `ailauncher` user.
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

        onProgress("Copying compositor source into rootfs")
        copyCompositorSourcesIntoRootfs()

        onProgress("Running bootstrap-arch.sh")
        val bootstrap = File(context.filesDir, "bootstrap-arch.sh").apply {
            writeText(context.assets.open("bootstrap-arch.sh").bufferedReader().readText())
            setExecutable(true)
        }
        runInRootfs(listOf("bash", bootstrap.absolutePath), asUser = "root")

        tarball.delete()
    }

    /**
     * Copies the bundled `ailauncher-compositor` C source + meson.build (assets/compositor/)
     * into the rootfs at /root/ailauncher-compositor-src, where bootstrap-arch.sh builds it
     * against the rootfs's own `wlroots` package. Plain host-side file copy: no proot needed
     * since this just places files, it doesn't execute anything inside the sandbox yet.
     */
    private fun copyCompositorSourcesIntoRootfs() {
        val destDir = File(rootfsDir, "root/ailauncher-compositor-src").apply { mkdirs() }
        context.assets.list("compositor")?.forEach { name ->
            context.assets.open("compositor/$name").use { input ->
                File(destDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
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
     * Starts `ailauncher-compositor` (built at bootstrap time from assets/compositor/, see
     * [copyCompositorSourcesIntoRootfs]) inside the rootfs: a minimal wlroots-based Wayland
     * compositor on wlroots' headless backend (no DRM/GPU needed under proot) with XWayland
     * enabled for X11 app compatibility. [onLine] receives its stdout, one `AILAUNCHER_EVENT
     * ...` line per window/display lifecycle event — [dev.ailauncher.window.CompositorEventWatcher]
     * is the parser for those.
     */
    fun startCompositor(onLine: (String) -> Unit): Process {
        return execInRootfsAsync(
            listOf(
                "bash", "-lc",
                "mkdir -p /tmp/ailauncher-xdg && chmod 700 /tmp/ailauncher-xdg && " +
                    "XDG_RUNTIME_DIR=/tmp/ailauncher-xdg WLR_BACKENDS=headless " +
                    "WLR_RENDERER=pixman WLR_NO_HARDWARE_CURSORS=1 ailauncher-compositor",
            ),
            asUser = "ailauncher",
            onOutput = { Log.d("ProotRuntime", "compositor: $it"); onLine(it) },
        )
    }

    /**
     * Serves exactly one compositor output (by [outputName], e.g. "HEADLESS-3") as its own
     * tiny VNC server on 127.0.0.1:[port], via `wayvnc -o <output>`. This — not a whole-screen
     * VNC/RDP session — is what lets each Linux GUI window map onto its own Android task: the
     * compositor gives every bridged window a dedicated headless output (see
     * ailauncher-compositor.c), and the Kotlin-side RFB client in
     * [dev.ailauncher.window.XWindowSurfaceView] connects to exactly this port and sees exactly
     * that one window's framebuffer.
     */
    fun exportOutputAsVnc(outputName: String, port: Int): Process {
        return execInRootfsAsync(
            listOf(
                "bash", "-lc",
                "XDG_RUNTIME_DIR=/tmp/ailauncher-xdg WAYLAND_DISPLAY=wayland-1 " +
                    "wayvnc -o $outputName 127.0.0.1 $port",
            ),
            asUser = "ailauncher",
            onOutput = { Log.d("ProotRuntime", "wayvnc[$outputName]: $it") },
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
            // No static DISPLAY/WAYLAND_DISPLAY here: ailauncher-compositor assigns its
            // Wayland socket name and XWayland display number at its own startup (reported
            // via its AILAUNCHER_EVENT WAYLAND/XWAYLAND lines), so callers that need either
            // — see WindowBridgeService.launchLinuxApp — prefix the command itself with
            // whichever one is current instead of relying on a value baked in here.
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
