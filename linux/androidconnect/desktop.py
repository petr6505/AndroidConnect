"""Avahi (mDNS) service advertisement and desktop notifications."""

import logging

from gi.repository import Gio, GLib

log = logging.getLogger(__name__)

AVAHI = "org.freedesktop.Avahi"
AVAHI_IF_UNSPEC = -1
AVAHI_PROTO_UNSPEC = -1
AVAHI_COLLISION = 3
AVAHI_FAILURE = 4


class AvahiPublisher:
    """Advertises the daemon over mDNS; re-registers if avahi restarts."""

    def __init__(self, bus: Gio.DBusConnection, name: str, service_type: str, port: int, txt: dict):
        self.bus = bus
        self.name = name
        self.service_type = service_type
        self.port = port
        self.txt = [f"{k}={v}".encode() for k, v in txt.items()]
        self.group = None
        self._state_sub = None
        Gio.bus_watch_name_on_connection(bus, AVAHI, Gio.BusNameWatcherFlags.NONE,
                                         self._on_appeared, self._on_vanished)

    def _on_appeared(self, *args):
        self._register()

    def _on_vanished(self, *args):
        if self.group:
            log.warning("avahi went away; will re-advertise when it returns")
        self._drop_group()

    def _drop_group(self):
        if self._state_sub is not None:
            self.bus.signal_unsubscribe(self._state_sub)
            self._state_sub = None
        self.group = None

    def _register(self):
        self._drop_group()
        try:
            self.group, = self.bus.call_sync(AVAHI, "/", "org.freedesktop.Avahi.Server", "EntryGroupNew",
                                             None, GLib.VariantType("(o)"), 0, 5000, None).unpack()
            self._state_sub = self.bus.signal_subscribe(
                AVAHI, "org.freedesktop.Avahi.EntryGroup", "StateChanged", self.group, None,
                Gio.DBusSignalFlags.NONE, self._on_state)
            self.bus.call_sync(AVAHI, self.group, "org.freedesktop.Avahi.EntryGroup", "AddService",
                               GLib.Variant("(iiussssqaay)", (AVAHI_IF_UNSPEC, AVAHI_PROTO_UNSPEC, 0,
                                                              self.name, self.service_type, "", "",
                                                              self.port, self.txt)),
                               None, 0, 5000, None)
            self.bus.call_sync(AVAHI, self.group, "org.freedesktop.Avahi.EntryGroup", "Commit",
                               None, None, 0, 5000, None)
            log.info("advertising '%s' on port %d via mDNS", self.name, self.port)
        except GLib.Error as e:
            log.warning("mDNS advertisement failed (tablets can still connect by IP): %s", e.message)
            self._drop_group()

    def _on_state(self, conn, sender, path, iface, signal, params):
        state, error = params.unpack()
        if state == AVAHI_COLLISION:
            self.name, = self.bus.call_sync(AVAHI, "/", "org.freedesktop.Avahi.Server",
                                            "GetAlternativeServiceName", GLib.Variant("(s)", (self.name,)),
                                            GLib.VariantType("(s)"), 0, 5000, None).unpack()
            log.info("mDNS name collision, renaming to '%s'", self.name)
            GLib.idle_add(self._register)
        elif state == AVAHI_FAILURE:
            log.warning("mDNS registration failed: %s", error)


class Notifier:
    """Desktop notifications with action buttons."""

    def __init__(self, bus: Gio.DBusConnection, app_id: str):
        self.bus = bus
        self.app_id = app_id
        self._handlers = {}   # notification id -> callback(action)
        bus.signal_subscribe("org.freedesktop.Notifications", "org.freedesktop.Notifications",
                             "ActionInvoked", "/org/freedesktop/Notifications", None,
                             Gio.DBusSignalFlags.NONE, self._on_action)
        bus.signal_subscribe("org.freedesktop.Notifications", "org.freedesktop.Notifications",
                             "NotificationClosed", "/org/freedesktop/Notifications", None,
                             Gio.DBusSignalFlags.NONE, self._on_closed)

    def notify(self, summary, body, actions=None, on_action=None, urgent=False, replaces=0) -> int:
        action_list = []
        for key, label in (actions or []):
            action_list += [key, label]
        hints = {"desktop-entry": GLib.Variant("s", self.app_id),
                 "urgency": GLib.Variant("y", 2 if urgent else 1)}
        if urgent:
            hints["resident"] = GLib.Variant("b", True)
        try:
            nid, = self.bus.call_sync(
                "org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications", "Notify",
                GLib.Variant("(susssasa{sv}i)", ("Android Connect", replaces, "video-display",
                                                  summary, body, action_list, hints, 0 if urgent else -1)),
                GLib.VariantType("(u)"), 0, 5000, None).unpack()
        except GLib.Error as e:
            log.warning("notification failed: %s", e.message)
            return 0
        if on_action:
            self._handlers[nid] = on_action
        return nid

    def close(self, nid: int):
        if not nid:
            return
        self._handlers.pop(nid, None)
        self.bus.call("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
                      "org.freedesktop.Notifications", "CloseNotification",
                      GLib.Variant("(u)", (nid,)), None, 0, -1, None, None)

    def _on_action(self, conn, sender, path, iface, signal, params):
        nid, action = params.unpack()
        handler = self._handlers.get(nid)
        if handler:
            handler(action)

    def _on_closed(self, conn, sender, path, iface, signal, params):
        nid, reason = params.unpack()
        self._handlers.pop(nid, None)
