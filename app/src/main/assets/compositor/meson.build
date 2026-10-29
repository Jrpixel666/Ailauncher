project('ailauncher-compositor', 'c',
  default_options: ['c_std=c11', 'warning_level=2'],
)

# Written against wlroots 0.18's API; pacman on the device may carry a newer/older
# soname. Try the pinned pkg-config name first, then fall back to whatever
# `wlroots` resolves to so this still builds against a nearby version.
wlroots = dependency('wlroots-0.18', required: false)
if not wlroots.found()
  wlroots = dependency('wlroots', required: true)
endif

wayland_server = dependency('wayland-server')
xkbcommon = dependency('xkbcommon')
pixman = dependency('pixman-1')

executable('ailauncher-compositor', 'ailauncher-compositor.c',
  dependencies: [wlroots, wayland_server, xkbcommon, pixman],
  c_args: ['-DWLR_USE_UNSTABLE', '-D_POSIX_C_SOURCE=200809L'],
  install: false,
)
