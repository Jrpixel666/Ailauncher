// ailauncher-compositor: a minimal wlroots-based Wayland compositor + XWayland host.
//
// Written against the wlroots 0.18 API (scene-graph + wlr_output_state commit path,
// split xdg_shell new_toplevel/new_popup signals). It is built from source inside the
// proot Arch rootfs at first-launch (see bootstrap-arch.sh) against whatever `wlroots`
// package Arch's own repos currently ship, so a point-release API rename upstream may
// need a small follow-up patch here — this has not been compiled or run anywhere in
// the environment this file was authored in (no Android device, no wlroots toolchain),
// unlike nothing else in this repo either, since that needs real hardware too.
//
// What this does, in one pass:
//   - Runs wlroots' "headless" backend (no DRM/KMS, no libinput, no GPU needed) with
//     the software/pixman renderer (WLR_RENDERER=pixman, set by the launcher before
//     exec'ing this binary) so it works under proot with no device-node access.
//   - Hosts XWayland (lazily started) so unmodified X11 apps run through it exactly
//     like native Wayland apps.
//   - For every *new top-level window* (native Wayland xdg_toplevel, or an XWayland
//     surface that isn't override-redirect), it does NOT add that window into one
//     shared desktop. Instead it allocates that window a dedicated headless
//     wlr_output sized to the window, and places the window's scene subtree at a
//     reserved, non-overlapping slot in the scene's coordinate space so that output's
//     viewport shows *exactly and only* that one window. This is the Wayland-side
//     equivalent of `x11vnc -id <window>`: one compositor, one process, but each
//     window gets an independently exportable "screen".
//   - `wlr_screencopy_manager_v1` lets `wayvnc -o <output-name>` (started per-window by
//     the Kotlin side, see ProotRuntime.exportOutputAsVnc) capture exactly that output.
//   - `wlr_virtual_pointer_manager_v1` / `wlr_virtual_keyboard_manager_v1` let that same
//     wayvnc process inject the touch/keyboard input it receives over RFB from the
//     Android side back into this compositor, targeted at the right window via the
//     virtual-pointer protocol's per-output "suggested_output" field.
//   - Window lifecycle is reported on stdout as single-line events the launcher parses
//     (CompositorEventWatcher.kt): one of
//       AILAUNCHER_EVENT WAYLAND <socket-name>
//       AILAUNCHER_EVENT XWAYLAND <display-name>      (e.g. ":1")
//       AILAUNCHER_EVENT CREATE <id> <output-name> <title>
//       AILAUNCHER_EVENT DESTROY <id>
//     This keeps the Android side a dumb line-oriented watcher instead of needing any
//     Wayland client library of its own (no NDK/JNI on the Android side at all).
//
// Known simplifications (acceptable for a "minimal" compositor; follow-ups, not bugs):
//   - No damage tracking tuning, no multi-GPU/DRM output, no visible software cursor
//     (wayvnc draws its own cursor from the RFB side).
//   - Pointer focus is re-asserted on every motion event rather than only on change;
//     correct, just not the most efficient possible.
//   - Each window's scene slot is `id * SLOT_STRIDE` along the X axis — plenty of
//     headroom for any realistic window size/count, but not literally unbounded.

#define _POSIX_C_SOURCE 200809L
#define WLR_USE_UNSTABLE

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <wayland-server-core.h>

#include <wlr/backend.h>
#include <wlr/backend/headless.h>
#include <wlr/render/allocator.h>
#include <wlr/render/wlr_renderer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_output.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_screencopy_v1.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_subcompositor.h>
#include <wlr/types/wlr_virtual_keyboard_v1.h>
#include <wlr/types/wlr_virtual_pointer_v1.h>
#include <wlr/types/wlr_xdg_shell.h>
#include <wlr/util/log.h>
#include <wlr/xwayland/xwayland.h>

// Non-overlapping scene-space slot width reserved per bridged window. wlr_scene uses
// plain `int` coordinates; this leaves room for ~2 million windows before overflow,
// which is not a real constraint for this app's lifetime.
#define SLOT_STRIDE 1048576
#define DEFAULT_WIDTH 1280
#define DEFAULT_HEIGHT 800

struct ailauncher_server {
	struct wl_display *wl_display;
	struct wlr_backend *backend;
	struct wlr_backend *headless_backend;
	struct wlr_renderer *renderer;
	struct wlr_allocator *allocator;
	struct wlr_compositor *compositor;
	struct wlr_output_layout *output_layout;
	struct wlr_scene *scene;
	struct wlr_seat *seat;
	struct wlr_xdg_shell *xdg_shell;
	struct wlr_xwayland *xwayland;
	struct wlr_virtual_pointer_manager_v1 *virtual_pointer_mgr;
	struct wlr_virtual_keyboard_manager_v1 *virtual_keyboard_mgr;

	struct wl_list views; // ailauncher_view::link
	uint32_t next_view_id;

	struct wl_listener new_xdg_toplevel;
	struct wl_listener new_xwayland_surface;
	struct wl_listener xwayland_ready;
	struct wl_listener new_virtual_pointer;
	struct wl_listener new_virtual_keyboard;
};

enum view_kind { VIEW_XDG, VIEW_XWAYLAND };

struct ailauncher_view {
	struct wl_list link;
	struct ailauncher_server *server;
	enum view_kind kind;
	union {
		struct wlr_xdg_toplevel *xdg_toplevel;
		struct wlr_xwayland_surface *xwayland_surface;
	};

	uint32_t id;
	int slot_x;
	struct wlr_output *output;
	struct wlr_scene_output *scene_output;
	struct wlr_scene_tree *scene_tree;

	struct wl_listener map;
	struct wl_listener unmap;
	struct wl_listener destroy;
	struct wl_listener frame;
};

struct vp_handler {
	struct ailauncher_server *server;
	struct wlr_virtual_pointer_v1 *pointer;
	struct wlr_output *suggested_output;
	struct wl_listener motion_absolute;
	struct wl_listener button;
	struct wl_listener axis;
	struct wl_listener frame;
	struct wl_listener destroy;
};

struct kb_handler {
	struct ailauncher_server *server;
	struct wl_listener key;
	struct wl_listener destroy;
};

static struct wlr_surface *view_surface(struct ailauncher_view *view) {
	return view->kind == VIEW_XDG
		? view->xdg_toplevel->base->surface
		: view->xwayland_surface->surface;
}

static struct ailauncher_view *find_view_by_output(struct ailauncher_server *server,
		struct wlr_output *output) {
	struct ailauncher_view *view;
	wl_list_for_each(view, &server->views, link) {
		if (view->output == output) {
			return view;
		}
	}
	return NULL;
}

// --- window lifecycle: shared between the xdg_shell and XWayland paths --------------

static void view_map_common(struct ailauncher_view *view, int width, int height,
		const char *title) {
	struct ailauncher_server *server = view->server;
	if (width <= 0) width = DEFAULT_WIDTH;
	if (height <= 0) height = DEFAULT_HEIGHT;

	view->id = server->next_view_id++;
	view->slot_x = (int) view->id * SLOT_STRIDE;

	struct wlr_output *output = wlr_headless_add_output(server->headless_backend,
		(unsigned) width, (unsigned) height);
	view->output = output;

	struct wlr_output_state state;
	wlr_output_state_init(&state);
	wlr_output_state_set_enabled(&state, true);
	wlr_output_state_set_custom_mode(&state, width, height, 0);
	wlr_output_commit_state(output, &state);
	wlr_output_state_finish(&state);

	wlr_output_layout_add(server->output_layout, output, view->slot_x, 0);

	view->scene_tree = wlr_scene_tree_create(&server->scene->tree);
	wlr_scene_node_set_position(&view->scene_tree->node, view->slot_x, 0);
	if (view->kind == VIEW_XDG) {
		wlr_scene_xdg_surface_create(view->scene_tree, view->xdg_toplevel->base);
	} else {
		wlr_scene_surface_create(view->scene_tree, view->xwayland_surface->surface);
	}

	view->scene_output = wlr_scene_output_create(server->scene, output);
	wlr_scene_output_set_position(view->scene_output, view->slot_x, 0);

	// view->frame.notify was already set to output_frame when the view was created
	// (server_new_xdg_toplevel / server_new_xwayland_surface), before this output existed.
	wl_signal_add(&output->events.frame, &view->frame);

	printf("AILAUNCHER_EVENT CREATE %u %s %s\n", view->id, output->name,
		(title && title[0]) ? title : "Linux app");
	fflush(stdout);
}

static void view_unmap_common(struct ailauncher_view *view) {
	printf("AILAUNCHER_EVENT DESTROY %u\n", view->id);
	fflush(stdout);

	wl_list_remove(&view->frame.link);
	if (view->scene_output) {
		wlr_scene_output_destroy(view->scene_output);
		view->scene_output = NULL;
	}
	if (view->scene_tree) {
		wlr_scene_node_destroy(&view->scene_tree->node);
		view->scene_tree = NULL;
	}
	if (view->output) {
		wlr_output_layout_remove(view->server->output_layout, view->output);
		wlr_output_destroy(view->output);
		view->output = NULL;
	}
}

static void output_frame(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, frame);
	if (!view->scene_output) {
		return;
	}
	wlr_scene_output_commit(view->scene_output, NULL);
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	wlr_scene_output_send_frame_done(view->scene_output, &now);
}

// --- xdg_shell toplevels --------------------------------------------------------------

static void xdg_toplevel_map(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, map);
	struct wlr_xdg_toplevel *toplevel = view->xdg_toplevel;
	view_map_common(view, toplevel->base->current.width, toplevel->base->current.height,
		toplevel->title);
}

static void xdg_toplevel_unmap(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, unmap);
	view_unmap_common(view);
}

static void xdg_toplevel_destroy(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, destroy);
	wl_list_remove(&view->map.link);
	wl_list_remove(&view->unmap.link);
	wl_list_remove(&view->destroy.link);
	wl_list_remove(&view->link);
	free(view);
}

static void server_new_xdg_toplevel(struct wl_listener *listener, void *data) {
	struct ailauncher_server *server = wl_container_of(listener, server, new_xdg_toplevel);
	struct wlr_xdg_toplevel *toplevel = data;

	struct ailauncher_view *view = calloc(1, sizeof(*view));
	view->server = server;
	view->kind = VIEW_XDG;
	view->xdg_toplevel = toplevel;
	view->frame.notify = output_frame;
	wl_list_insert(&server->views, &view->link);

	view->map.notify = xdg_toplevel_map;
	wl_signal_add(&toplevel->base->surface->events.map, &view->map);
	view->unmap.notify = xdg_toplevel_unmap;
	wl_signal_add(&toplevel->base->surface->events.unmap, &view->unmap);
	view->destroy.notify = xdg_toplevel_destroy;
	wl_signal_add(&toplevel->events.destroy, &view->destroy);
}

// --- XWayland surfaces -----------------------------------------------------------------

static void xwayland_surface_map(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, map);
	struct wlr_xwayland_surface *xs = view->xwayland_surface;
	view_map_common(view, xs->width, xs->height, xs->title);
}

static void xwayland_surface_unmap(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, unmap);
	view_unmap_common(view);
}

static void xwayland_surface_destroy(struct wl_listener *listener, void *data) {
	struct ailauncher_view *view = wl_container_of(listener, view, destroy);
	wl_list_remove(&view->map.link);
	wl_list_remove(&view->unmap.link);
	wl_list_remove(&view->destroy.link);
	wl_list_remove(&view->link);
	free(view);
}

static void server_new_xwayland_surface(struct wl_listener *listener, void *data) {
	struct ailauncher_server *server = wl_container_of(listener, server, new_xwayland_surface);
	struct wlr_xwayland_surface *xs = data;
	if (xs->override_redirect) {
		// Tooltips/menus/popups: not a top-level window, no Android task for these.
		return;
	}

	struct ailauncher_view *view = calloc(1, sizeof(*view));
	view->server = server;
	view->kind = VIEW_XWAYLAND;
	view->xwayland_surface = xs;
	view->frame.notify = output_frame;
	wl_list_insert(&server->views, &view->link);

	view->map.notify = xwayland_surface_map;
	wl_signal_add(&xs->events.map, &view->map);
	view->unmap.notify = xwayland_surface_unmap;
	wl_signal_add(&xs->events.unmap, &view->unmap);
	view->destroy.notify = xwayland_surface_destroy;
	wl_signal_add(&xs->events.destroy, &view->destroy);
}

static void server_xwayland_ready(struct wl_listener *listener, void *data) {
	struct ailauncher_server *server = wl_container_of(listener, server, xwayland_ready);
	setenv("DISPLAY", server->xwayland->display_name, 1);
	wlr_xwayland_set_seat(server->xwayland, server->seat);
	printf("AILAUNCHER_EVENT XWAYLAND %s\n", server->xwayland->display_name);
	fflush(stdout);
}

// --- wayvnc's input path: virtual pointer / virtual keyboard protocols --------------
//
// wayvnc is itself a Wayland client of this compositor (one instance per bridged
// window, see WindowBridgeService/ProotRuntime.exportOutputAsVnc). It turns the RFB
// pointer/key events RfbClient sends it back into these virtual-input protocol
// requests, tagged with the specific output it was told to watch (`-o <name>`) — that
// `suggested_output` is exactly how we route the event back to the one window sitting
// in that output's scene slot, with no need to track "focus" ourselves.

static void vp_motion_absolute(struct wl_listener *listener, void *data) {
	struct vp_handler *vp = wl_container_of(listener, vp, motion_absolute);
	struct wlr_pointer_motion_absolute_event *event = data;
	struct wlr_output *output = vp->suggested_output;
	if (!output) {
		return;
	}
	struct ailauncher_view *view = find_view_by_output(vp->server, output);
	if (!view) {
		return;
	}
	double sx = event->x * (double) output->width;
	double sy = event->y * (double) output->height;
	struct wlr_surface *surface = view_surface(view);
	wlr_seat_pointer_notify_enter(vp->server->seat, surface, sx, sy);
	wlr_seat_pointer_notify_motion(vp->server->seat, event->time_msec, sx, sy);
}

static void vp_button(struct wl_listener *listener, void *data) {
	struct vp_handler *vp = wl_container_of(listener, vp, button);
	struct wlr_pointer_button_event *event = data;
	wlr_seat_pointer_notify_button(vp->server->seat, event->time_msec, event->button, event->state);
}

static void vp_axis(struct wl_listener *listener, void *data) {
	struct vp_handler *vp = wl_container_of(listener, vp, axis);
	struct wlr_pointer_axis_event *event = data;
	wlr_seat_pointer_notify_axis(vp->server->seat, event->time_msec, event->orientation,
		event->delta, event->delta_discrete, event->source);
}

static void vp_frame(struct wl_listener *listener, void *data) {
	struct vp_handler *vp = wl_container_of(listener, vp, frame);
	wlr_seat_pointer_notify_frame(vp->server->seat);
}

static void vp_destroy(struct wl_listener *listener, void *data) {
	struct vp_handler *vp = wl_container_of(listener, vp, destroy);
	wl_list_remove(&vp->motion_absolute.link);
	wl_list_remove(&vp->button.link);
	wl_list_remove(&vp->axis.link);
	wl_list_remove(&vp->frame.link);
	wl_list_remove(&vp->destroy.link);
	free(vp);
}

static void server_new_virtual_pointer(struct wl_listener *listener, void *data) {
	struct ailauncher_server *server = wl_container_of(listener, server, new_virtual_pointer);
	struct wlr_virtual_pointer_v1_new_pointer_event *event = data;
	struct wlr_virtual_pointer_v1 *pointer = event->new_pointer;

	struct vp_handler *vp = calloc(1, sizeof(*vp));
	vp->server = server;
	vp->pointer = pointer;
	vp->suggested_output = event->suggested_output;

	vp->motion_absolute.notify = vp_motion_absolute;
	wl_signal_add(&pointer->pointer.events.motion_absolute, &vp->motion_absolute);
	vp->button.notify = vp_button;
	wl_signal_add(&pointer->pointer.events.button, &vp->button);
	vp->axis.notify = vp_axis;
	wl_signal_add(&pointer->pointer.events.axis, &vp->axis);
	vp->frame.notify = vp_frame;
	wl_signal_add(&pointer->pointer.events.frame, &vp->frame);
	vp->destroy.notify = vp_destroy;
	wl_signal_add(&pointer->pointer.base.events.destroy, &vp->destroy);
}

static void kb_key(struct wl_listener *listener, void *data) {
	struct kb_handler *kb = wl_container_of(listener, kb, key);
	struct wlr_keyboard_key_event *event = data;
	wlr_seat_keyboard_notify_key(kb->server->seat, event->time_msec, event->keycode, event->state);
}

static void kb_destroy(struct wl_listener *listener, void *data) {
	struct kb_handler *kb = wl_container_of(listener, kb, destroy);
	wl_list_remove(&kb->key.link);
	wl_list_remove(&kb->destroy.link);
	free(kb);
}

static void server_new_virtual_keyboard(struct wl_listener *listener, void *data) {
	struct ailauncher_server *server = wl_container_of(listener, server, new_virtual_keyboard);
	struct wlr_virtual_keyboard_v1 *keyboard = data;
	wlr_seat_set_keyboard(server->seat, &keyboard->keyboard);

	struct kb_handler *kb = calloc(1, sizeof(*kb));
	kb->server = server;
	kb->key.notify = kb_key;
	wl_signal_add(&keyboard->keyboard.base.events.destroy, &kb->destroy);
	wl_signal_add(&keyboard->keyboard.events.key, &kb->key);
	kb->destroy.notify = kb_destroy;
}

// --- entry point -----------------------------------------------------------------------

int main(int argc, char *argv[]) {
	wlr_log_init(WLR_INFO, NULL);

	struct ailauncher_server server = {0};
	wl_list_init(&server.views);
	server.next_view_id = 1;

	server.wl_display = wl_display_create();

	server.headless_backend = wlr_headless_backend_create(server.wl_display);
	if (!server.headless_backend) {
		fprintf(stderr, "ailauncher-compositor: failed to create headless backend\n");
		return 1;
	}
	server.backend = server.headless_backend;

	server.renderer = wlr_renderer_autocreate(server.backend);
	if (!server.renderer) {
		fprintf(stderr, "ailauncher-compositor: failed to create renderer "
			"(set WLR_RENDERER=pixman before running this binary)\n");
		return 1;
	}
	wlr_renderer_init_wl_display(server.renderer, server.wl_display);

	server.allocator = wlr_allocator_autocreate(server.backend, server.renderer);
	if (!server.allocator) {
		fprintf(stderr, "ailauncher-compositor: failed to create allocator\n");
		return 1;
	}

	server.compositor = wlr_compositor_create(server.wl_display, 5, server.renderer);
	wlr_subcompositor_create(server.wl_display);
	wlr_data_device_manager_create(server.wl_display);

	server.output_layout = wlr_output_layout_create(server.wl_display);
	server.scene = wlr_scene_create();
	server.seat = wlr_seat_create(server.wl_display, "seat0");

	server.xdg_shell = wlr_xdg_shell_create(server.wl_display, 3);
	server.new_xdg_toplevel.notify = server_new_xdg_toplevel;
	wl_signal_add(&server.xdg_shell->events.new_toplevel, &server.new_xdg_toplevel);

	wlr_screencopy_manager_v1_create(server.wl_display);

	server.virtual_pointer_mgr = wlr_virtual_pointer_manager_v1_create(server.wl_display);
	server.new_virtual_pointer.notify = server_new_virtual_pointer;
	wl_signal_add(&server.virtual_pointer_mgr->events.new_virtual_pointer,
		&server.new_virtual_pointer);

	server.virtual_keyboard_mgr = wlr_virtual_keyboard_manager_v1_create(server.wl_display);
	server.new_virtual_keyboard.notify = server_new_virtual_keyboard;
	wl_signal_add(&server.virtual_keyboard_mgr->events.new_virtual_keyboard,
		&server.new_virtual_keyboard);

	// Lazy: XWayland only actually spawns once the first X11 client connects, to keep
	// the idle (no X apps running) footprint to just the Wayland side.
	server.xwayland = wlr_xwayland_create(server.wl_display, server.compositor, true);
	if (server.xwayland) {
		server.xwayland_ready.notify = server_xwayland_ready;
		wl_signal_add(&server.xwayland->events.ready, &server.xwayland_ready);
		server.new_xwayland_surface.notify = server_new_xwayland_surface;
		wl_signal_add(&server.xwayland->events.new_surface, &server.new_xwayland_surface);
	} else {
		fprintf(stderr, "ailauncher-compositor: XWayland unavailable "
			"(xorg-xwayland not installed?) — Wayland-native apps still work\n");
	}

	const char *socket = wl_display_add_socket_auto(server.wl_display);
	if (!socket) {
		fprintf(stderr, "ailauncher-compositor: failed to create Wayland socket\n");
		return 1;
	}

	if (!wlr_backend_start(server.backend)) {
		fprintf(stderr, "ailauncher-compositor: failed to start headless backend\n");
		return 1;
	}

	setenv("WAYLAND_DISPLAY", socket, 1);
	printf("AILAUNCHER_EVENT WAYLAND %s\n", socket);
	fflush(stdout);

	wl_display_run(server.wl_display);

	wl_display_destroy_clients(server.wl_display);
	wl_display_destroy(server.wl_display);
	return 0;
}
