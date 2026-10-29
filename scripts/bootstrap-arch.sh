#!/data/data/dev.ailauncher/files/usr/bin/bash
# Runs once, as the first command inside the freshly extracted Arch Linux proot rootfs,
# to finish setup without root and without ever touching the kernel.
#
# AiLauncher never ships or updates the kernel/kernel modules: this is a userspace
# proot_distro rootfs sitting on top of the host Android kernel, and pacman is
# explicitly told to leave kernel packages alone so a `pacman -Syu` run from inside
# an agent session (or by the user) can never attempt a kernel/module swap that
# would be meaningless (and potentially broken) under proot anyway.
set -euo pipefail

PACMAN_CONF=/etc/pacman.conf

# Disable kernel + kernel module packages/updates entirely.
if ! grep -q '^IgnoreGroup' "$PACMAN_CONF"; then
  sed -i '/^\[options\]/a IgnoreGroup = kernel-modules\nIgnorePkg   = linux linux-lts linux-hardened linux-zen linux-headers linux-lts-headers mkinitcpio' "$PACMAN_CONF"
fi

pacman-key --init
pacman-key --populate archlinux
pacman -Sy --noconfirm archlinux-keyring

# Base userland only — no kernel, no bootloader, no initramfs tooling needed under proot.
#
# GUI stack: wlroots + wayland(-protocols) (compositor library), xorg-xwayland (X11 app
# compatibility hosted *inside* the Wayland compositor, not a separate X server), wayvnc
# (exports exactly one compositor output as its own tiny VNC server — the Wayland-side
# equivalent of `x11vnc -id <window>`, and this is what lets AiLauncher give each Linux
# GUI window its own Android task instead of mirroring one whole virtual screen), plus
# meson/ninja/pkgconf/pixman/libxkbcommon to build AiLauncher's own minimal compositor
# binary (bundled as C source, see compositor/) against whatever wlroots pacman ships.
pacman -S --noconfirm --needed \
  base-devel git curl wget sudo nano vim tmux meson ninja pkgconf \
  nodejs npm python python-pip python-pipx \
  wayland wayland-protocols wlroots xorg-xwayland wayvnc \
  pixman libxkbcommon mesa ttf-dejavu dbus

# Build AiLauncher's own compositor (bundled source, copied into the rootfs by
# ProotRuntime.installRootfs alongside this script) against the rootfs's own wlroots.
COMPOSITOR_SRC=/root/ailauncher-compositor-src
if [ -d "$COMPOSITOR_SRC" ]; then
  meson setup --reconfigure "$COMPOSITOR_SRC/build" "$COMPOSITOR_SRC" --buildtype=release
  ninja -C "$COMPOSITOR_SRC/build"
  install -Dm755 "$COMPOSITOR_SRC/build/ailauncher-compositor" /usr/local/bin/ailauncher-compositor
fi

# Unprivileged user that the launcher runs agents and GUI apps as.
if ! id ailauncher >/dev/null 2>&1; then
  useradd -m -G wheel -s /bin/bash ailauncher
  echo 'ailauncher ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/ailauncher
fi

echo "bootstrap-arch.sh: base system ready, kernel/module updates disabled."
