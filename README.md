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

**Linux GUI apps as native Android tasks.** `WindowBridgeService` starts an `Xvfb` +
window manager inside the rootfs. `XWindowWatcher` is meant to listen to that WM's window
create/destroy/title event stream and, per top-level window, launch one
`LinuxWindowActivity` with `FLAG_ACTIVITY_NEW_DOCUMENT` against an Activity declared with
`android:taskAffinity=""` and `documentLaunchMode="always"` — that combination is what
gives each bridged window its own independent card in Recents instead of stacking onto
AiLauncher's own task, the same trick Termux:X11 and Winlator rely on.
`XWindowSurfaceView` is where that window's pixels get composited onto an Android
`Surface` and where touch input gets translated back into X11 events.

### What's stubbed vs. wired up

The Activity/task plumbing for the Recents behavior (manifest flags, `taskAffinity`,
`FLAG_ACTIVITY_NEW_DOCUMENT`, `setTaskDescription` from the X window's own title) is
fully wired. The actual pixel bridge — an embedded XCB/X11 client compositing one window
onto a `SurfaceView` and forwarding input, plus hooking a specific WM's IPC for
create/destroy events — is marked `TODO(window-bridge)` in `XWindowWatcher` and
`XWindowSurfaceView`. That layer is inherently native/NDK work tied to whichever X server
and WM `bootstrap-arch.sh` ends up shipping, so it's left as the next concrete step rather
than guessed at here; everything above it (service lifecycle, per-window task creation,
input/surface callback shape) is ready for that code to plug into.

## Building

Standard Gradle Android project; open in Android Studio or run:

```
./gradlew assembleDebug
```

(min SDK 29, target/compile SDK 34, Kotlin 1.9, Compose BOM 2024.06).
