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
# GUI stack: Xvfb (virtual display) + icewm (lightweight WM, needed so xdotool/x11vnc see
# proper top-level window geometry) + xdotool (window discovery) + x11vnc (serves a SINGLE
# window, by X11 window id, as its own tiny VNC server — this is what lets AiLauncher give
# each Linux GUI window its own Android task instead of mirroring the whole virtual screen).
pacman -S --noconfirm --needed \
  base-devel git curl wget sudo nano vim tmux \
  nodejs npm python python-pip python-pipx \
  xorg-server-xvfb xorg-xinit xorg-xrandr dbus mesa ttf-dejavu \
  icewm xdotool x11vnc

# Unprivileged user that the launcher runs agents and GUI apps as.
if ! id ailauncher >/dev/null 2>&1; then
  useradd -m -G wheel -s /bin/bash ailauncher
  echo 'ailauncher ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/ailauncher
fi

echo "bootstrap-arch.sh: base system ready, kernel/module updates disabled."
