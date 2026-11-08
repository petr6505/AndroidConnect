"""GNOME Mutter integration: virtual monitors, input injection and display layout.

Everything here runs on the GLib main thread.
"""

import logging

import gi

gi.require_version("Gst", "1.0")
from gi.repository import Gio, GLib, Gst  # noqa: E402

log = logging.getLogger(__name__)

SC_NAME = "org.gnome.Mutter.ScreenCast"
SC_PATH = "/org/gnome/Mutter/ScreenCast"
SC_IFACE = "org.gnome.Mutter.ScreenCast"
SC_SESSION = "org.gnome.Mutter.ScreenCast.Session"
SC_STREAM = "org.gnome.Mutter.ScreenCast.Stream"
RD_NAME = "org.gnome.Mutter.RemoteDesktop"
RD_PATH = "/org/gnome/Mutter/RemoteDesktop"
RD_IFACE = "org.gnome.Mutter.RemoteDesktop"
RD_SESSION = "org.gnome.Mutter.RemoteDesktop.Session"
DC_NAME = "org.gnome.Mutter.DisplayConfig"
DC_PATH = "/org/gnome/Mutter/DisplayConfig"
DC_IFACE = "org.gnome.Mutter.DisplayConfig"

VIRTUAL_PRODUCT = "Virtual remote monitor"

CURSOR_HIDDEN = 0
CURSOR_EMBEDDED = 1

BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112


def _call(bus, name, path, iface, method, args=None, reply=None):
    return bus.call_sync(name, path, iface, method, args,
                         GLib.VariantType(reply) if reply else None,
                         Gio.DBusCallFlags.NONE, 5000, None).unpack()


class VirtualDisplay:
    """A Mutter virtual monitor backed by a PipeWire stream, plus a linked
    remote-desktop session used to inject touch/pointer input on it."""

    def __init__(self, bus: Gio.DBusConnection, on_ready, on_closed, cursor_mode=CURSOR_EMBEDDED):
        self.bus = bus
        self.cursor_mode = cursor_mode
        self.on_ready = on_ready      # (pipewire_node_id) -> None
        self.on_closed = on_closed    # () -> None
        self.rd_session = None
        self.sc_session = None
        self.stream = None
        self._subs = []
        self._closed = False

    def start(self) -> None:
        self.rd_session, = _call(self.bus, RD_NAME, RD_PATH, RD_IFACE, "CreateSession", None, "(o)")
        session_id = self.bus.call_sync(
            RD_NAME, self.rd_session, "org.freedesktop.DBus.Properties", "Get",
            GLib.Variant("(ss)", (RD_SESSION, "SessionId")), GLib.VariantType("(v)"),
            Gio.DBusCallFlags.NONE, 5000, None).unpack()[0]
        self.sc_session, = _call(self.bus, SC_NAME, SC_PATH, SC_IFACE, "CreateSession",
                                 GLib.Variant("(a{sv})", ({
                                     "remote-desktop-session-id": GLib.Variant("s", session_id),
                                 },)), "(o)")
        self.stream, = _call(self.bus, SC_NAME, self.sc_session, SC_SESSION, "RecordVirtual",
                             GLib.Variant("(a{sv})", ({
                                 "cursor-mode": GLib.Variant("u", self.cursor_mode),
                                 "is-platform": GLib.Variant("b", True),
                             },)), "(o)")
        self._subs.append(self.bus.signal_subscribe(
            SC_NAME, SC_STREAM, "PipeWireStreamAdded", self.stream, None,
            Gio.DBusSignalFlags.NONE, self._on_stream_added))
        self._subs.append(self.bus.signal_subscribe(
            RD_NAME, RD_SESSION, "Closed", self.rd_session, None,
            Gio.DBusSignalFlags.NONE, self._on_closed))
        _call(self.bus, RD_NAME, self.rd_session, RD_SESSION, "Start")

    def _on_stream_added(self, conn, sender, path, iface, signal, params):
        node_id = params.unpack()[0]
        log.info("virtual monitor stream ready (pipewire node %d)", node_id)
        self.on_ready(node_id)

    def _on_closed(self, *args):
        if self._closed:
            return
        log.warning("mutter closed the remote desktop session")
        self._cleanup()
        self.on_closed()

    def _cleanup(self):
        self._closed = True
        for sub in self._subs:
            self.bus.signal_unsubscribe(sub)
        self._subs = []

    def stop(self) -> None:
        if self._closed:
            return
        self._cleanup()
        if self.rd_session:
            try:
                _call(self.bus, RD_NAME, self.rd_session, RD_SESSION, "Stop")
            except GLib.Error as e:
                log.debug("stopping session: %s", e.message)

    def _notify(self, method, args):
        if self._closed or not self.rd_session:
            return
        # Fire-and-forget; calls on one connection are delivered in order.
        self.bus.call(RD_NAME, self.rd_session, RD_SESSION, method, args, None,
                      Gio.DBusCallFlags.NONE, -1, None, None)

    def touch_down(self, slot, x, y):
        self._notify("NotifyTouchDown", GLib.Variant("(sudd)", (self.stream, slot, x, y)))

    def touch_motion(self, slot, x, y):
        self._notify("NotifyTouchMotion", GLib.Variant("(sudd)", (self.stream, slot, x, y)))

    def touch_up(self, slot):
        self._notify("NotifyTouchUp", GLib.Variant("(u)", (slot,)))

    def pointer_motion(self, x, y):
        self._notify("NotifyPointerMotionAbsolute", GLib.Variant("(sdd)", (self.stream, x, y)))

    def pointer_button(self, button, pressed):
        self._notify("NotifyPointerButton", GLib.Variant("(ib)", (button, pressed)))

    def pointer_scroll(self, dx, dy):
        # Smooth scrolling in "finger" units: roughly 10 per wheel notch.
        self._notify("NotifyPointerAxis", GLib.Variant("(ddu)", (dx, dy, 0)))


class SoftwareCursor:
    """Hack to get the pointer drawn on a virtual monitor.

    With a hardware cursor plane active, Mutter never paints the pointer
    into virtual monitors (and pointer motion there doesn't even produce
    frames). An embedded-cursor RecordMonitor screencast inhibits the hw
    cursor while the pointer is on that monitor, so we run one at 1 fps
    and throw the frames away.
    """

    def __init__(self, bus: Gio.DBusConnection, connector: str):
        self.bus = bus
        self.connector = connector
        self.session = None
        self.pipeline = None
        self._sub = 0

    def start(self) -> None:
        self.session, = _call(self.bus, SC_NAME, SC_PATH, SC_IFACE, "CreateSession",
                              GLib.Variant("(a{sv})", ({},)), "(o)")
        stream, = _call(self.bus, SC_NAME, self.session, SC_SESSION, "RecordMonitor",
                        GLib.Variant("(sa{sv})", (self.connector, {
                            "cursor-mode": GLib.Variant("u", CURSOR_EMBEDDED),
                        })), "(o)")
        self._sub = self.bus.signal_subscribe(
            SC_NAME, SC_STREAM, "PipeWireStreamAdded", stream, None,
            Gio.DBusSignalFlags.NONE, self._on_stream_added)
        _call(self.bus, SC_NAME, self.session, SC_SESSION, "Start")

    def _on_stream_added(self, conn, sender, path, iface, signal, params):
        node_id = params.unpack()[0]
        self.pipeline = Gst.parse_launch(
            f"pipewiresrc path={node_id} ! video/x-raw,max-framerate=1/1 ! fakesink sync=false")
        self.pipeline.set_state(Gst.State.PLAYING)
        log.debug("software cursor helper streaming %s (pipewire node %d)", self.connector, node_id)

    def stop(self) -> None:
        if self._sub:
            self.bus.signal_unsubscribe(self._sub)
            self._sub = 0
        if self.pipeline is not None:
            self.pipeline.set_state(Gst.State.NULL)
            self.pipeline = None
        if self.session:
            try:
                _call(self.bus, SC_NAME, self.session, SC_SESSION, "Stop")
            except GLib.Error as e:
                log.debug("stopping cursor helper: %s", e.message)
            self.session = None


class DisplayLayout:
    """Monitor layout through org.gnome.Mutter.DisplayConfig.

    Virtual monitors get a new serial every time, so GNOME forgets where
    they were. We store side/offset/scale per device and re-apply it.
    """

    def __init__(self, bus: Gio.DBusConnection, on_changed):
        self.bus = bus
        self._applying = 0
        bus.signal_subscribe(DC_NAME, DC_IFACE, "MonitorsChanged", DC_PATH, None,
                             Gio.DBusSignalFlags.NONE, lambda *a: on_changed())

    def state(self):
        serial, monitors, logical, props = _call(self.bus, DC_NAME, DC_PATH, DC_IFACE, "GetCurrentState")
        return serial, monitors, logical, props

    def virtual_connectors(self) -> set:
        try:
            _, monitors, _, _ = self.state()
        except GLib.Error:
            return set()
        return {spec[0] for spec, modes, props in monitors if spec[2] == VIRTUAL_PRODUCT}

    @staticmethod
    def _current_mode(monitor):
        spec, modes, props = monitor
        for mode in modes:
            if mode[6].get("is-current"):
                return mode
        return None

    def _logical_size(self, lm, monitors_by_conn, layout_mode):
        x, y, scale, transform, primary, specs, props = lm
        mode = self._current_mode(monitors_by_conn[specs[0][0]])
        w, h = mode[1], mode[2]
        if transform % 2 == 1:
            w, h = h, w
        if layout_mode == 1:  # logical layout: sizes are divided by scale
            w, h = round(w / scale), round(h / scale)
        return w, h

    def input_scale(self, connector: str) -> float:
        """Mutter wants input in layout units (logical px in logical layout
        mode), not stream pixels. Divide by this before injecting."""
        try:
            _, _, logical, props = self.state()
        except GLib.Error:
            return 1.0
        if props.get("layout-mode", 1) != 1:
            return 1.0
        for lm in logical:
            if any(spec[0] == connector for spec in lm[5]):
                return lm[2]
        return 1.0

    def read_placement(self, connector: str):
        """Returns {"side", "offset", "scale"} for the virtual monitor, or None."""
        if self._applying:
            return None
        try:
            _, monitors, logical, props = self.state()
        except GLib.Error:
            return None
        layout_mode = props.get("layout-mode", 1)
        by_conn = {m[0][0]: m for m in monitors}
        ours, others = None, []
        for lm in logical:
            rect = (lm[0], lm[1]) + self._logical_size(lm, by_conn, layout_mode)
            if any(spec[0] == connector for spec in lm[5]):
                ours = (lm, rect)
            else:
                others.append(rect)
        if not ours or not others:
            return None
        (lm, (x, y, w, h)) = ours
        bx0 = min(r[0] for r in others)
        by0 = min(r[1] for r in others)
        bx1 = max(r[0] + r[2] for r in others)
        by1 = max(r[1] + r[3] for r in others)
        if x >= bx1:
            side, offset = "right", y - by0
        elif x + w <= bx0:
            side, offset = "left", y - by0
        elif y >= by1:
            side, offset = "below", x - bx0
        else:
            side, offset = "above", x - bx0
        return {"side": side, "offset": offset, "scale": round(lm[2], 4)}

    def apply_placement(self, connector: str, placement: dict) -> bool:
        try:
            serial, monitors, logical, props = self.state()
        except GLib.Error as e:
            log.warning("cannot read display state: %s", e.message)
            return False
        layout_mode = props.get("layout-mode", 1)
        by_conn = {m[0][0]: m for m in monitors}
        if connector not in by_conn:
            return False
        mode = self._current_mode(by_conn[connector])
        if mode is None:
            return False

        # Pick the supported scale closest to the requested one.
        supported = list(mode[5]) or [1.0]
        scale = min(supported, key=lambda s: abs(s - placement.get("scale", 1.0)))

        entries, others = [], []
        for lm in logical:
            if any(spec[0] == connector for spec in lm[5]):
                continue
            x, y, lscale, transform, primary, specs, _ = lm
            w, h = self._logical_size(lm, by_conn, layout_mode)
            others.append((x, y, w, h))
            mons = []
            for spec in specs:
                cur = self._current_mode(by_conn[spec[0]])
                if cur is None:
                    break
                mons.append((spec[0], cur[0], {}))
            else:
                entries.append([x, y, lscale, transform, primary, mons])
                continue
            return False
        if not others:
            return False

        w, h = mode[1], mode[2]
        if layout_mode == 1:
            w, h = round(w / scale), round(h / scale)
        bx0 = min(r[0] for r in others)
        by0 = min(r[1] for r in others)
        bx1 = max(r[0] + r[2] for r in others)
        by1 = max(r[1] + r[3] for r in others)
        side = placement.get("side", "right")
        offset = int(placement.get("offset", 0))
        if side in ("left", "right"):
            offset = max(-(h - 1), min(offset, (by1 - by0) - 1))
            vx = bx1 if side == "right" else bx0 - w
            vy = by0 + offset
        else:
            offset = max(-(w - 1), min(offset, (bx1 - bx0) - 1))
            vx = bx0 + offset
            vy = by1 if side == "below" else by0 - h
        entries.append([vx, vy, float(scale), 0, False, [(connector, mode[0], {})]])

        min_x = min(e[0] for e in entries)
        min_y = min(e[1] for e in entries)
        config = [(e[0] - min_x, e[1] - min_y, e[2], e[3], e[4], e[5]) for e in entries]
        apply_props = {}
        if props.get("supports-changing-layout-mode"):
            apply_props["layout-mode"] = GLib.Variant("u", layout_mode)
        try:
            self._applying += 1
            _call(self.bus, DC_NAME, DC_PATH, DC_IFACE, "ApplyMonitorsConfig",
                  GLib.Variant("(uua(iiduba(ssa{sv}))a{sv})", (serial, 1, config, apply_props)))
            log.info("placed %s %s of the laptop at scale %s", connector, side, scale)
            return True
        except GLib.Error as e:
            log.warning("could not apply display layout: %s", e.message)
            return False
        finally:
            # MonitorsChanged for our own change arrives shortly; ignore it.
            GLib.timeout_add(500, self._done_applying)

    def _done_applying(self):
        self._applying -= 1
        return False
