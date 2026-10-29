# AiLauncher

A Kotlin/Jetpack Compose Android home launcher with a built-in agentic AI experience:
a rootless Arch Linux userspace (via `proot`, no root required), a picker to install any
of several AI coding-agent CLIs into it on first launch, widget hosting like a normal
launcher, and a bridge that surfaces Linux GUI apps running in that Arch environment as
their own native-looking entries in Android's Recents.

## Module layout

```
app/src/main/kotlin/dev/ailauncher/
  launcher/      Home screen (Compose): widgets pane, app drawer, bottom nav
  setup/         First-launch wizard: rootfs install + agent CLI picker
  agents/        proot runtime, agent registry/specs, AgentManagerService, chat panel
  window/        Linux-GUI-window -> Android-task bridge
  widgets/       AppWidgetHost wiring (bind/pick/host third-party widgets)
  data/          Lightweight prefs (setup state, installed agents)
scripts/bootstrap-arch.sh   Runs once inside the extracted Arch rootfs
```

## How the pieces fit together

**Rootless Arch Linux.** `ProotRuntime` downloads the official Arch Linux (ARM or x86_64,
matched to the device ABI) bootstrap tarball into app-private storage and extracts it with
a statically linked `proot` (ptrace-based syscall faking — same technique Termux's
`proot-distro` uses), so no root, no custom kernel, and no system modification is ever
needed. `scripts/bootstrap-arch.sh` then runs once as the fake-root user to install the
base userland (`base-devel`, `nodejs`/`npm`, `python`/`pipx`, `Xvfb`, a minimal WM, etc.)
and create the unprivileged `ailauncher` user that every agent CLI and GUI app actually
runs as.

**Kernel/module updates are disabled, not just skipped.** `bootstrap-arch.sh` adds
`IgnoreGroup = kernel-modules` and `IgnorePkg = linux linux-lts linux-hardened linux-zen
linux-headers linux-lts-headers mkinitcpio` to `pacman.conf` before the first sync. A
`pacman -Syu` run later — by the user or by an agent acting autonomously — can update
every userland package but will never attempt to touch the kernel or its modules, which
would be meaningless (and unsafe) inside a proot sandbox sitting on the host's real
Android kernel anyway.

**First-launch install flow.** `SetupWizardActivity`/`SetupViewModel` walk through:
rootfs install (if not already present) → user-selected subset of `AgentRegistry.all` →
each agent's own published install command (npm/pipx/curl-install-script — AiLauncher
doesn't vendor agent binaries, so it stays correct as they ship updates) run inside the
rootfs as `ailauncher`. Supported out of the box: **Claude Code, OpenCode, Codex CLI,
Grok CLI, GitHub Copilot CLI, Crush, Cursor Agent, Agy, Pi, OMP, Ori, Muse, Kimi CLI,
Antigravity, and OpenRouter** (as a model gateway the others can route through). More can
be added later via Settings — the picker list is just `AgentRegistry.all`.

**Agentic extras.** `AgentManagerService` is a long-running foreground service that owns
the proot runtime and the live CLI subprocesses, independent of whatever screen is in
front (home, app drawer, or a bridged Linux window). `AgentChatPanel` is the chat surface
in the launcher's third tab; it binds to that service and streams agent stdout/stdin.

**Widgets.** Standard `AppWidgetHost`/`AppWidgetManager` flow (`WidgetHostController`,
`WidgetPickerActivity`, `WidgetSurface`) — the same mechanism AOSP's Launcher3 uses to
host arbitrary third-party widgets, since only the default launcher is allowed to bind
them.

**Linux GUI apps as native Android tasks.** `WindowBridgeService` starts `Xvfb` + `icewm`
inside the rootfs. `XWindowWatcher` polls for top-level windows via `xdotool` every 1.5s
and, per *new* window, starts a dedicated `x11vnc -id <window>` server that exports just
that one window's framebuffer on its own loopback port (`ProotRuntime.exportWindowAsVnc`)
— this is the key trick that lets one window map onto one Android task, rather than
mirroring the whole virtual screen. It then launches one `LinuxWindowActivity` with
`FLAG_ACTIVITY_NEW_DOCUMENT` against an Activity declared with `android:taskAffinity=""`
and `documentLaunchMode="always"` — that combination is what gives each bridged window
its own independent card in Recents instead of stacking onto AiLauncher's own task, the
same effect Termux:X11 and Winlator rely on (achieved here without any NDK/JNI). That
Activity's `XWindowSurfaceView` speaks RFB to that one port via `RfbClient` — a small,
dependency-free Kotlin implementation of the VNC wire protocol (version/security
handshake, Raw-encoding framebuffer updates, pointer/key events) — to draw the window
into a `SurfaceView` and forward touch input back to it. When the window closes, its
`x11vnc` process and RFB socket go away together, which the Activity detects and uses to
finish itself (`android:autoRemoveFromRecents="true"` then drops its Recents card).

### Known trade-offs

- **Polling, not event-driven.** `XWindowWatcher` polls rather than hooking the window
  manager's own IPC, so there's up to ~1.5s latency before a freshly opened window gets
  its own task. Swapping in an event-driven watcher is a drop-in replacement behind the
  same `onWindowCreated`/`onWindowDestroyed` calls.
- **`RfbClient` only speaks Raw encoding.** Correct and simple, but not bandwidth-efficient
  for high-motion content; fine for the kind of GUI tools (terminals, editors, file
  managers) agent workflows mostly spawn. Hextile/ZRLE support would be the natural next
  upgrade if video-heavy apps become a priority.
- **Input is pointer-only today.** `RfbClient.sendKey` exists and is wired for a future
  on-screen/physical keyboard bridge, but `XWindowSurfaceView` doesn't yet forward Android
  key events to it.

## Building

Standard Gradle Android project; open in Android Studio or run:

```
./gradlew assembleDebug
```

(min SDK 29, target/compile SDK 34, Kotlin 1.9, Compose BOM 2024.06).
