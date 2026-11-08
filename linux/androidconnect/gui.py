"""androidconnect - small control window for the background service.

Closing this window never affects connected tablets; the service keeps running.
"""

import json
import os
import subprocess
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from . import APP_ID, DBUS_IFACE, DBUS_NAME, DBUS_PATH  # noqa: E402

UNIT = "androidconnectd.service"

ENCODER_LABELS = {
    "auto": "Automatic",
    "vaapi": "Hardware (VA-API)",
    "vaapi-lp": "Hardware, low power (VA-API)",
    "x264": "Software (x264)",
}
FPS_CHOICES = [30, 60]
BITRATE_CHOICES = [0, 5, 10, 15, 20, 30, 50]  # Mbit/s; 0 = automatic

CSS = b"""
.pair-code { font-size: 2.2em; font-weight: 800; letter-spacing: 0.12em; font-feature-settings: "tnum"; }
.status-dot { min-width: 10px; min-height: 10px; border-radius: 5px; background: @success_color; }
.status-dot.off { background: alpha(currentColor, 0.3); }
"""


def systemctl(*args) -> subprocess.CompletedProcess:
    return subprocess.run(["systemctl", "--user", *args], capture_output=True, text=True)


# The package starts the service at login via /etc/xdg/autostart; a user
# opts out with the standard per-user override (Hidden=true).
AUTOSTART_OVERRIDE = os.path.join(GLib.get_user_config_dir(), "autostart", "io.github.androidconnect.daemon.desktop")


def autostart_enabled() -> bool:
    try:
        with open(AUTOSTART_OVERRIDE) as f:
            return "Hidden=true" not in f.read()
    except OSError:
        return True


def set_autostart(enabled: bool) -> None:
    if enabled:
        if os.path.exists(AUTOSTART_OVERRIDE):
            os.remove(AUTOSTART_OVERRIDE)
        return
    os.makedirs(os.path.dirname(AUTOSTART_OVERRIDE), exist_ok=True)
    with open(AUTOSTART_OVERRIDE, "w") as f:
        f.write("[Desktop Entry]\nType=Application\nName=Android Connect service\nHidden=true\n")


class Window(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title="Android Connect", default_width=440, default_height=620)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.status = None
        self._dynamic_rows = []

        page = Adw.PreferencesPage()
        toolbar = Adw.ToolbarView()
        toolbar.add_top_bar(Adw.HeaderBar())
        toolbar.set_content(page)
        self.toasts = Adw.ToastOverlay(child=toolbar)
        self.set_content(self.toasts)

        # Pairing request (hidden unless a tablet is waiting)
        self.pair_group = Adw.PreferencesGroup(title="Pairing request")
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin_top=18, margin_bottom=18,
                      margin_start=18, margin_end=18)
        self.pair_title = Gtk.Label(wrap=True, justify=Gtk.Justification.CENTER)
        self.pair_title.add_css_class("title-4")
        self.pair_code = Gtk.Label()
        self.pair_code.add_css_class("pair-code")
        hint = Gtk.Label(label="Accept only if your tablet shows the same code.", wrap=True,
                         justify=Gtk.Justification.CENTER)
        hint.add_css_class("dim-label")
        buttons = Gtk.Box(spacing=12, halign=Gtk.Align.CENTER)
        reject = Gtk.Button(label="Reject")
        reject.add_css_class("pill")
        reject.connect("clicked", lambda *_: self._pair_decision(False))
        accept = Gtk.Button(label="Accept")
        accept.add_css_class("pill")
        accept.add_css_class("suggested-action")
        accept.connect("clicked", lambda *_: self._pair_decision(True))
        buttons.append(reject)
        buttons.append(accept)
        for w in (self.pair_title, self.pair_code, hint, buttons):
            box.append(w)
        frame = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        frame.add_css_class("boxed-list")
        frame.append(box)
        self.pair_group.add(frame)
        page.add(self.pair_group)

        # Service status
        service = Adw.PreferencesGroup()
        self.service_row = Adw.ActionRow(title="Android Connect")
        self.dot = Gtk.Box(valign=Gtk.Align.CENTER)
        self.dot.add_css_class("status-dot")
        self.service_row.add_prefix(self.dot)
        self.start_button = Gtk.Button(label="Start", valign=Gtk.Align.CENTER)
        self.start_button.add_css_class("suggested-action")
        self.start_button.connect("clicked", self._start_service)
        self.service_row.add_suffix(self.start_button)
        service.add(self.service_row)
        self.autostart = Adw.SwitchRow(title="Start automatically",
                                       subtitle="Run in the background after you log in")
        self.autostart.set_active(autostart_enabled())
        self.autostart.connect("notify::active", self._toggle_autostart)
        service.add(self.autostart)
        page.add(service)

        self.connected_group = Adw.PreferencesGroup(title="Second screens")
        page.add(self.connected_group)

        self.stream_group = Adw.PreferencesGroup(
            title="Streaming", description="Changes apply the next time a tablet connects.")
        self.encoder_row = Adw.ComboRow(title="Encoder")
        self.fps_row = Adw.ComboRow(title="Frame rate limit",
                                    model=Gtk.StringList.new([f"{f} fps" for f in FPS_CHOICES]))
        self.bitrate_row = Adw.ComboRow(
            title="Bitrate", subtitle="Higher is sharper but needs a faster Wi-Fi",
            model=Gtk.StringList.new(["Automatic"] + [f"{b} Mbit/s" for b in BITRATE_CHOICES[1:]]))
        self.cursor_row = Adw.SwitchRow(title="Show mouse pointer on the tablet")
        for row in (self.encoder_row, self.fps_row, self.bitrate_row, self.cursor_row):
            self.stream_group.add(row)
        page.add(self.stream_group)
        self._encoders = []
        self._loading_settings = False
        self.encoder_row.connect("notify::selected", self._settings_changed)
        self.fps_row.connect("notify::selected", self._settings_changed)
        self.bitrate_row.connect("notify::selected", self._settings_changed)
        self.cursor_row.connect("notify::active", self._settings_changed)
        self.devices_group = Adw.PreferencesGroup(
            title="Paired tablets", description="These tablets connect without asking again.")
        page.add(self.devices_group)

        displays = Adw.PreferencesGroup()
        row = Adw.ActionRow(title="Arrange displays", subtitle="Position, scale and orientation",
                            activatable=True)
        row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
        row.connect("activated", lambda *_: subprocess.Popen(["gnome-control-center", "display"]))
        displays.add(row)
        page.add(displays)

        self.bus.signal_subscribe(DBUS_NAME, DBUS_IFACE, "StatusChanged", DBUS_PATH, None,
                                  Gio.DBusSignalFlags.NONE, lambda *a: self.refresh())
        Gio.bus_watch_name_on_connection(self.bus, DBUS_NAME, Gio.BusNameWatcherFlags.NONE,
                                         lambda *a: (self.refresh(), self._load_settings()),
                                         lambda *a: self._set_status(None))
        # Live stats while a tablet is streaming; addresses can change too (Wi-Fi roaming).
        self._ticks = 0
        GLib.timeout_add_seconds(2, self._tick)

    def _call(self, method, args=None, callback=None):
        def done(bus, result):
            try:
                reply = bus.call_finish(result)
            except GLib.Error as e:
                if callback:
                    callback(None)
                elif not Gio.DBusError.is_remote_error(e) or "ServiceUnknown" not in e.message:
                    self.toasts.add_toast(Adw.Toast(title=e.message))
                return
            if callback:
                callback(reply.unpack())
        self.bus.call(DBUS_NAME, DBUS_PATH, DBUS_IFACE, method, args, None,
                      Gio.DBusCallFlags.NO_AUTO_START, 5000, None, done)

    def refresh(self):
        self._call("GetStatus", callback=lambda r: self._set_status(json.loads(r[0]) if r else None))

    def _tick(self):
        self._ticks += 1
        streaming = self.status and any(s["connected"] for s in self.status["sessions"])
        if streaming or self._ticks % 5 == 0:
            self.refresh()
        return GLib.SOURCE_CONTINUE

    def _load_settings(self):
        def loaded(reply):
            if reply is None:
                return
            data = json.loads(reply[0])
            s = data["settings"]
            self._loading_settings = True
            self._encoders = ["auto"] + data["encoders"]
            self.encoder_row.set_model(Gtk.StringList.new([ENCODER_LABELS.get(e, e) for e in self._encoders]))
            self.encoder_row.set_selected(self._encoders.index(s["encoder"]) if s["encoder"] in self._encoders else 0)
            self.fps_row.set_selected(FPS_CHOICES.index(s["max_fps"]) if s["max_fps"] in FPS_CHOICES else 1)
            mbps = s["bitrate_mbps"]
            self.bitrate_row.set_selected(min(range(len(BITRATE_CHOICES)),
                                              key=lambda i: abs(BITRATE_CHOICES[i] - mbps)))
            self.cursor_row.set_active(s["show_cursor"])
            self._loading_settings = False
        self._call("GetSettings", callback=loaded)

    def _settings_changed(self, *_):
        if self._loading_settings or not self._encoders:
            return
        settings = {
            "encoder": self._encoders[self.encoder_row.get_selected()],
            "max_fps": FPS_CHOICES[self.fps_row.get_selected()],
            "bitrate_mbps": BITRATE_CHOICES[self.bitrate_row.get_selected()],
            "show_cursor": self.cursor_row.get_active(),
        }
        self._call("SetSettings", GLib.Variant("(s)", (json.dumps(settings),)))

    def _pair_decision(self, accept):
        if self.status and self.status.get("pairing"):
            self._call("AcceptPairing" if accept else "RejectPairing",
                       GLib.Variant("(s)", (self.status["pairing"]["request_id"],)))

    def _start_service(self, *_):
        result = systemctl("start", UNIT)
        if result.returncode != 0:
            self.toasts.add_toast(Adw.Toast(title="Could not start the background service"))

    def _toggle_autostart(self, row, _):
        try:
            set_autostart(row.get_active())
        except OSError:
            self.toasts.add_toast(Adw.Toast(title="Could not change autostart"))

    def _set_status(self, status):
        self.status = status
        for group, row in self._dynamic_rows:
            group.remove(row)
        self._dynamic_rows = []

        running = status is not None
        self.dot.set_css_classes(["status-dot"] if running else ["status-dot", "off"])
        self.start_button.set_visible(not running)
        self.stream_group.set_sensitive(running)
        if running:
            addrs = ", ".join(status["addresses"]) or "no network"
            self.service_row.set_title(f"Visible as “{status['server_name']}”")
            self.service_row.set_subtitle(f"{addrs} · port {status['port']} · encrypted")
        else:
            self.service_row.set_title("Service is not running")
            self.service_row.set_subtitle("Tablets cannot connect until it is started")

        pairing = status.get("pairing") if running else None
        self.pair_group.set_visible(pairing is not None)
        if pairing:
            self.pair_title.set_label(f"“{pairing['name']}” wants to connect")
            self.pair_code.set_label(f"{pairing['code'][:3]} {pairing['code'][3:]}")
            self.present()

        sessions = status["sessions"] if running else []
        if sessions:
            for s in sessions:
                row = Adw.ActionRow(title=s["name"])
                if s["connected"]:
                    subtitle = f"{s['width']} × {s['height']} · {s['fps']} fps"
                    if s.get("encoder"):
                        subtitle += f" · {ENCODER_LABELS.get(s['encoder'], s['encoder'])}"
                    stats = s.get("stats")
                    if stats:
                        subtitle += (f"\nNow: {stats['fps']:.0f} fps ({stats.get('captured_fps', 0):.0f} new)"
                                     f" · {stats['kbps'] / 1000:.1f} Mbit/s")
                        if stats["dropped"]:
                            subtitle += " · Wi-Fi too slow, skipping frames"
                    row.set_subtitle(subtitle)
                    row.add_prefix(Gtk.Image.new_from_icon_name("video-display-symbolic"))
                else:
                    row.set_subtitle("Connection interrupted, waiting for the tablet…")
                    row.add_prefix(Gtk.Spinner(spinning=True))
                button = Gtk.Button(label="Disconnect", valign=Gtk.Align.CENTER)
                button.connect("clicked", lambda _b, d=s["device_id"]:
                               self._call("DisconnectDevice", GLib.Variant("(s)", (d,))))
                row.add_suffix(button)
                self._add(self.connected_group, row)
        else:
            row = Adw.ActionRow(title="No tablet connected")
            row.set_subtitle("Open Android Connect on your tablet and tap this laptop. "
                             "Both devices must be on the same Wi-Fi.")
            row.set_subtitle_lines(0)
            self._add(self.connected_group, row)

        devices = status["devices"] if running else []
        self.devices_group.set_visible(bool(devices))
        for d in devices:
            row = Adw.ActionRow(title=d["name"])
            row.add_prefix(Gtk.Image.new_from_icon_name("computer-symbolic"))
            button = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER,
                                tooltip_text="Forget")
            button.add_css_class("flat")
            button.connect("clicked", lambda _b, dev=d: self._confirm_forget(dev))
            row.add_suffix(button)
            self._add(self.devices_group, row)

    def _add(self, group, row):
        group.add(row)
        self._dynamic_rows.append((group, row))

    def _confirm_forget(self, device):
        dialog = Adw.AlertDialog(heading=f"Forget “{device['name']}”?",
                                 body="The tablet will have to be paired again before it can connect.")
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("forget", "Forget")
        dialog.set_response_appearance("forget", Adw.ResponseAppearance.DESTRUCTIVE)

        def on_response(d, response):
            if response == "forget":
                self._call("ForgetDevice", GLib.Variant("(s)", (device["device_id"],)))
        dialog.connect("response", on_response)
        dialog.present(self)


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID)
        self.window = None

    def do_startup(self):
        Adw.Application.do_startup(self)
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), provider,
                                                  Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    def do_activate(self):
        if self.window is None:
            self.window = Window(self)
            # Make sure the service is up whenever the user opens the app.
            if systemctl("is-active", UNIT).stdout.strip() != "active":
                systemctl("start", UNIT)
        self.window.present()


def main():
    sys.exit(App().run(sys.argv))


if __name__ == "__main__":
    main()
