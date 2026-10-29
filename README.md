# AiLauncher

A Kotlin/Jetpack Compose Android home launcher with a built-in agentic AI experience:
a rootless Arch Linux userspace (via `proot`, no root required), a picker to install any
of several AI coding-agent CLIs into it on first launch, widget hosting like a normal
launcher, and a minimal wlroots-based Wayland compositor (with XWayland for X11 app
compatibility) that surfaces Linux GUI apps running in that Arch environment as their
own native-looking entries in Android's Recents.

## Module layout

```
app/src/main/kotlin/dev/ailauncher/
  launcher/      Home screen (Compose): widgets pane, app drawer, bottom nav
  setup/         First-launch wizard: rootfs install + agent CLI picker
  agents/        proot runtime, agent registry/specs, AgentManagerService, chat panel
  window/        Linux-GUI-window -> Android-task bridge
  widgets/       AppWidgetHost wiring (bind/pick/host third-party widgets)
  data/          Lightweight prefs (setup state, installed agents)
app/src/main/assets/compositor/   ailauncher-compositor.c + meson.build — built from
                                   source inside the rootfs at first launch (see below)
scripts/bootstrap-arch.sh   Runs once inside the extracted Arch rootfs
```

## How the pieces fit together

**Rootless Arch Linux.** `ProotRuntime` downloads the official Arch Linux (ARM or x86_64,
matched to the device ABI) bootstrap tarball into app-private storage and extracts it with
a statically linked `proot` (ptrace-based syscall faking — same technique Termux's
`proot-distro` uses), so no root, no custom kernel, and no system modification is ever
needed. It then copies the bundled `ailauncher-compositor` C source into the rootfs, and
`scripts/bootstrap-arch.sh` runs once as the fake-root user to install the base userland
(`base-devel`, `nodejs`/`npm`, `python`/`pipx`, `wlroots`, `xorg-xwayland`, `wayvnc`,
`meson`/`ninja`, etc.), build that compositor against the rootfs's own `wlroots` package,
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

**Linux GUI apps as native Android tasks, via a real minimal Wayland compositor.**
`WindowBridgeService` starts `ailauncher-compositor` inside the rootfs
(`ProotRuntime.startCompositor`) — a small (~500-line) wlroots-based compositor, structurally
a trimmed-down `tinywl` (wlroots' own minimal reference compositor), running on wlroots'
**headless** backend so it never needs a DRM/GPU device node under proot, with the
**pixman** (software) renderer, and with **XWayland** enabled (lazily — it only actually
spawns once the first X11 client connects) so unmodified X11 apps run through it exactly
like native Wayland apps. Full source: `app/src/main/assets/compositor/ailauncher-compositor.c`.

The key trick, and the Wayland-side analogue of `x11vnc -id <window>`: rather than putting
every window on one shared desktop output, the compositor gives **each new top-level
window its own dedicated headless `wlr_output`**, sized to that window, with that window's
scene subtree placed at a reserved, non-overlapping slot in the compositor's scene-graph
coordinate space so that output's viewport shows *exactly and only* that one window
(`view_map_common` in the C source). It reports every window's lifecycle on its own stdout
as a one-line `AILAUNCHER_EVENT CREATE/DESTROY ...` event, which `WindowBridgeService` feeds
to `CompositorEventWatcher` — event-driven, not polled, unlike the window discovery in an
X11/`xdotool` design.

Per `CREATE` event, the watcher starts a dedicated `wayvnc -o <output>` server
(`ProotRuntime.exportOutputAsVnc`) that exports just that one compositor output's
framebuffer on its own loopback port — `wlr_screencopy_manager_v1` is what lets wayvnc pull
frames from exactly that output, and `wlr_virtual_pointer_manager_v1` /
`wlr_virtual_keyboard_manager_v1` are what let that same wayvnc process inject the
touch/key input it receives back over RFB into exactly that window (routed via the virtual-
pointer protocol's own per-output `suggested_output` field — no separate focus-tracking
needed since each output maps to exactly one window). The watcher then launches one
`LinuxWindowActivity` with `FLAG_ACTIVITY_NEW_DOCUMENT` against an Activity declared with
`android:taskAffinity=""` and `documentLaunchMode="always"` — that combination is what
gives each bridged window its own independent card in Recents instead of stacking onto
AiLauncher's own task, the same effect Termux:X11 and Winlator rely on (achieved here
without any NDK/JNI on the *Android* side — the compositor is plain Linux userspace C code
that happens to live inside the proot rootfs, compiled by the rootfs's own toolchain, not
by Android's Gradle/NDK build). That Activity's `XWindowSurfaceView` speaks RFB to that
one port via `RfbClient` — a small, dependency-free Kotlin implementation of the VNC wire
protocol (version/security handshake, Raw-encoding framebuffer updates, pointer/key
events; wayvnc is a standard RFB server, so this client needed no changes from its
original x11vnc-targeting version) — to draw the window into a `SurfaceView` and forward
touch input back to it. When the window closes, its `wayvnc` process and RFB socket go
away together, which the Activity detects and uses to finish itself
(`android:autoRemoveFromRecents="true"` then drops its Recents card).

### Known trade-offs and on-device risks to validate

This compositor has not been built or run anywhere in the environment it was written in
— there's no Android device, no proot/Arch rootfs, and no wlroots toolchain available
there, so unlike the rest of this repo (ordinary Kotlin/Gradle, checkable with a normal
build) this piece's correctness rests on the wlroots 0.18 API as documented/used upstream,
not on a local compile. Specific things most likely to need a small follow-up patch on a
real device:

- **wlroots API drift.** Arch's `wlroots` package can be newer than 0.18 by the time this
  runs; function/field renames across wlroots releases are common (the `meson.build`
  fallback dependency lookup handles the package-name side of this, not API signatures).
- **wayvnc's encoding/auth defaults.** `RfbClient` only speaks Raw encoding and expects
  no password; if the installed wayvnc build doesn't offer Raw as a fallback, or requires
  an explicit auth/TLS config to start, `ProotRuntime.exportOutputAsVnc`'s invocation and/or
  `RfbClient` need a small adjustment (see comments at both call sites).
- **`RfbClient` only speaks Raw encoding.** Correct and simple, but not bandwidth-efficient
  for high-motion content; fine for the kind of GUI tools (terminals, editors, file
  managers) agent workflows mostly spawn. Hextile/ZRLE support would be the natural next
  upgrade if video-heavy apps become a priority.
- **Input is pointer-only in the Android UI today.** The compositor's virtual-keyboard path
  is wired end-to-end on the Linux side, but `XWindowSurfaceView` doesn't yet forward
  Android key events into `RfbClient.sendKey`/wayvnc.
- **One compositor process, many outputs.** All bridged windows share one
  `ailauncher-compositor` process; a crash there takes down every currently-bridged window
  at once (each `wayvnc` is its own process, so a `wayvnc` crash only affects its one
  window). Acceptable for "minimal"; a supervisor/restart policy would be the natural
  hardening step.

## Building

Standard Gradle Android project; open in Android Studio or run:

```
./gradlew assembleDebug
```

(min SDK 29, target/compile SDK 34, Kotlin 1.9, Compose BOM 2024.06).

`app/src/main/assets/compositor/ailauncher-compositor.c` is **not** part of this Gradle/NDK
build — it's plain Linux userspace C, bundled only as a packaged asset and compiled by
`scripts/bootstrap-arch.sh` *inside the proot rootfs on-device*, against that rootfs's own
`wlroots`/`meson`/`ninja`. There is no way to build or exercise it from this repo's own
Gradle build or from a typical dev workstation (it needs an Arch Linux userspace with
`wlroots`+`wayland-protocols`+`xorg-xwayland` headers); it was written against documented
wlroots 0.18 APIs but has only been reviewed for internal consistency, not compiled — see
"Known trade-offs and on-device risks to validate" above before relying on it.
