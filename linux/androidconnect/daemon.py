"""androidconnectd - background service that turns paired tablets into extra monitors.

Threads:
  * GLib main loop (main thread): D-Bus, Mutter, GStreamer control, notifications.
  * asyncio loop (network thread): TLS server and per-client protocol handling.
  * GStreamer streaming threads: deliver encoded frames to the network loop.
"""

import asyncio
import json
import logging
import re
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid

import gi

gi.require_version("GLibUnix", "2.0")
from gi.repository import Gio, GLib, GLibUnix  # noqa: E402

from . import APP_ID, DBUS_IFACE, DBUS_NAME, DBUS_PATH, PROTOCOL_VERSION, SERVICE_TYPE
from . import security
from .desktop import AvahiPublisher, Notifier
from .encoder import Encoder, available_backends, bitrate_kbps, detect_backend, min_qp
from .mutter import (BTN_LEFT, BTN_MIDDLE, BTN_RIGHT, CURSOR_EMBEDDED, CURSOR_HIDDEN, DisplayLayout, SoftwareCursor,
                     VirtualDisplay)
from .protocol import MSG_JSON, ProtocolError, encode_json, encode_video_header, read_message
from .store import Store

log = logging.getLogger("androidconnectd")

GRACE_SECONDS = 30          # keep the monitor alive this long after an unexpected drop
STABLE_SECONDS = 15         # a reconnection must last this long before the grace period resets
BYE_TIMEOUT = 2             # seconds to let a "bye" reach the tablet before cutting the connection
HEARTBEAT_TIMEOUT = 8       # seconds without any message from the tablet
PAIRING_TIMEOUT = 120
DROP_LATENCY = 0.15         # seconds of video queued before we skip to the next keyframe
MIN_DROP_LIMIT = 256 * 1024
KEYFRAME_LIMIT = 2 * 1024 * 1024
STATS_INTERVAL = 2
RESTART_DELAY_MS = 700      # wait for a burst of display changes to settle
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")

DBUS_XML = f"""
<node>
  <interface name="{DBUS_IFACE}">
    <method name="GetStatus"><arg type="s" name="status" direction="out"/></method>
    <method name="GetSettings"><arg type="s" name="settings" direction="out"/></method>
    <method name="SetSettings"><arg type="s" name="settings" direction="in"/></method>
    <method name="AcceptPairing"><arg type="s" name="request_id" direction="in"/></method>
    <method name="RejectPairing"><arg type="s" name="request_id" direction="in"/></method>
    <method name="ForgetDevice"><arg type="s" name="device_id" direction="in"/></method>
    <method name="DisconnectDevice"><arg type="s" name="device_id" direction="in"/></method>
    <signal name="StatusChanged"/>
  </interface>
</node>
"""


def run_on_main(fn, *args) -> asyncio.Future:
    """Run fn on the GLib main thread; returns an asyncio future for its result."""
    loop = asyncio.get_running_loop()
    fut = loop.create_future()

    def deliver(setter, value):
        if not fut.done():
            setter(value)

    def run():
        try:
            result = fn(*args)
        except Exception as e:  # noqa: BLE001
            loop.call_soon_threadsafe(deliver, fut.set_exception, e)
        else:
            loop.call_soon_threadsafe(deliver, fut.set_result, result)
        return GLib.SOURCE_REMOVE

    GLib.idle_add(run)
    return fut


def local_addresses() -> list:
    try:
        out = subprocess.run(["ip", "-j", "-4", "addr", "show", "scope", "global"],
                             capture_output=True, text=True, timeout=2).stdout
        addrs = []
        for iface in json.loads(out or "[]"):
            if iface.get("ifname", "").startswith(("docker", "virbr", "br-", "veth", "tun", "wg")):
                continue
            addrs += [a["local"] for a in iface.get("addr_info", []) if a.get("family") == "inet"]
        return addrs
    except (OSError, ValueError, subprocess.SubprocessError):
        return []


class DisplaySession:
    """One tablet's virtual monitor and encoder. Lives on the main thread and
    may outlive a single network connection (see GRACE_SECONDS)."""

    def __init__(self, daemon, device_id, name, width, height, fps, quality, scale, settings):
        self.daemon = daemon
        self.device_id = device_id
        self.name = name
        self.width, self.height, self.fps, self.quality = width, height, fps, quality
        self.default_scale = scale
        self.settings = settings
        if settings["bitrate_mbps"] > 0:
            self.kbps = int(settings["bitrate_mbps"] * 1000)
        else:
            self.kbps = bitrate_kbps(width, height, fps, quality)
        self.client = None
        self.connector = None
        self.encoder = None
        self.software_cursor = None
        self.input_scale = 1.0      # the virtual monitor's scale; see DisplayLayout.input_scale
        self.node_id = None
        self.restart_timer = 0
        self.grace_timer = 0
        self.lost_since = None      # when the tablet first dropped without coming back for good
        self.created = time.monotonic()
        self.vd = VirtualDisplay(daemon.bus, self._on_stream_ready, self._on_mutter_closed,
                                 CURSOR_EMBEDDED if settings["show_cursor"] else CURSOR_HIDDEN)

    def start(self):
        self.vd.start()

    def matches(self, width, height, fps, quality, settings):
        return ((self.width, self.height, self.fps, self.quality, self.settings)
                == (width, height, fps, quality, settings))

    def set_connector(self, connector):
        self.connector = connector
        if self.software_cursor:
            self.software_cursor.stop()
            self.software_cursor = None
        if connector and self.settings["show_cursor"]:
            self.software_cursor = SoftwareCursor(self.daemon.bus, connector)
            try:
                self.software_cursor.start()
            except GLib.Error as e:
                log.warning("[%s] the pointer may not show on the tablet: %s", self.name, e.message)
                self.software_cursor.stop()
                self.software_cursor = None

    def _on_stream_ready(self, node_id):
        self.node_id = node_id
        self._start_encoder()

    def schedule_encoder_restart(self):
        """After any display reconfiguration, Mutter keeps feeding the stream
        from the virtual monitor's old view, at only ~5 fps. Reconnecting the
        PipeWire consumer makes it pick up the new view; the monitor itself
        and the network connection stay as they are."""
        if self.encoder is None:
            return
        if self.restart_timer:
            GLib.source_remove(self.restart_timer)
        self.restart_timer = GLib.timeout_add(RESTART_DELAY_MS, self._restart_encoder)

    def _restart_encoder(self):
        self.restart_timer = 0
        if self.encoder is not None:
            log.info("[%s] display layout changed; reconnecting the capture", self.name)
            self.encoder.stop()
            self.encoder = None
            self._start_encoder()
            # The cursor helper's stream goes stale the same way.
            self.set_connector(self.connector)
        return GLib.SOURCE_REMOVE

    def _start_encoder(self):
        node_id = self.node_id
        try:
            self.encoder = Encoder(node_id, self.width, self.height, self.fps, self.kbps, min_qp(self.quality),
                                   self._on_frame, self._on_encoder_error, self.settings["encoder"])
            self.encoder.start()
            log.info("[%s] streaming %dx%d@%d with %s at %d kbit/s",
                     self.name, self.width, self.height, self.fps, self.encoder.backend, self.kbps)
        except (GLib.Error, RuntimeError) as e:
            log.error("[%s] could not start encoder: %s", self.name, e)
            self.daemon.end_session(self, "encoder failed")

    def _on_frame(self, data, keyframe, pts_us):
        client = self.client  # streaming thread: plain attribute read
        if client is not None:
            client.post_frame(data, keyframe, pts_us)

    def _on_encoder_error(self, message):
        self.daemon.end_session(self, "encoder error")

    def _on_mutter_closed(self):
        self.daemon.end_session(self, "display session closed")

    def attach(self, client):
        if self.grace_timer:
            GLib.source_remove(self.grace_timer)
            self.grace_timer = 0
        self.client = client
        self.request_keyframe()

    def request_keyframe(self):
        if self.encoder:
            self.encoder.request_keyframe()

    # Input; coordinates arrive normalized to 0..1.
    def handle_input(self, msg):
        t = msg["t"]
        w, h = self.width / self.input_scale, self.height / self.input_scale
        if t == "touch":
            for slot, x, y in msg.get("p", []):
                px, py = float(x) * w, float(y) * h
                if msg["a"] == "down":
                    self.vd.touch_down(int(slot), px, py)
                elif msg["a"] == "move":
                    self.vd.touch_motion(int(slot), px, py)
                else:
                    self.vd.touch_up(int(slot))
        elif t == "ptr":
            if "x" in msg:
                self.vd.pointer_motion(float(msg["x"]) * w, float(msg["y"]) * h)
            if "btn" in msg:
                button = {"left": BTN_LEFT, "right": BTN_RIGHT, "middle": BTN_MIDDLE}.get(msg["btn"])
                if button:
                    self.vd.pointer_button(button, bool(msg.get("down")))
            if "sx" in msg or "sy" in msg:
                self.vd.pointer_scroll(float(msg.get("sx", 0)), float(msg.get("sy", 0)))

    def stop(self):
        if self.restart_timer:
            GLib.source_remove(self.restart_timer)
            self.restart_timer = 0
        if self.grace_timer:
            GLib.source_remove(self.grace_timer)
            self.grace_timer = 0
        if self.encoder:
            self.encoder.stop()
            self.encoder = None
        self.set_connector(None)
        self.vd.stop()


class ClientConnection:
    """Protocol handler for one TLS connection (network thread)."""

    def __init__(self, daemon, reader, writer):
        self.daemon = daemon
        self.reader = reader
        self.writer = writer
        self.loop = asyncio.get_running_loop()
        self.peer = writer.get_extra_info("peername")
        self.device_id = None
        self.name = "tablet"
        self.session = None
        self.graceful = False
        self.closed = False
        self.paused = False
        self.waiting_key = True
        self.last_rx = time.monotonic()
        self._last_key_request = 0.0
        self.frames_sent = 0
        self.frames_dropped = 0
        self.frames_encoded = 0
        self.stats = None
        self.drop_limit = MIN_DROP_LIMIT
        self.bytes_sent = 0
        self.attached_at = 0.0

    def _tune_socket(self):
        sock = self.writer.get_extra_info("socket")
        if sock is None:
            return
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 5)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 2)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # A small kernel buffer keeps latency low; we drop frames ourselves.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
        except OSError as e:
            log.debug("socket tuning failed: %s", e)

    def send(self, obj):
        if not self.closed:
            self.writer.write(encode_json(obj))

    async def recv(self, timeout):
        while True:
            mtype, msg = await asyncio.wait_for(read_message(self.reader), timeout)
            self.last_rx = time.monotonic()
            if mtype == MSG_JSON:
                return msg

    async def expect(self, kind, timeout):
        msg = await self.recv(timeout)
        if msg["t"] != kind:
            raise ProtocolError(f"expected {kind}, got {msg['t']}")
        return msg

    async def run(self):
        self._tune_socket()
        log.info("connection from %s", self.peer[0] if self.peer else "?")
        try:
            await self._handshake()
            await self._stream()
        except asyncio.IncompleteReadError:
            log.info("[%s] connection closed by tablet", self.name)
        except (ConnectionError, ssl.SSLError, OSError) as e:
            log.info("[%s] connection lost: %s", self.name, e)
        except asyncio.TimeoutError:
            log.info("[%s] connection timed out", self.name)
        except (ProtocolError, KeyError, ValueError, TypeError) as e:
            log.warning("[%s] protocol error: %s", self.name, e)
        except PairingRefused as e:
            log.info("[%s] pairing not completed: %s", self.name, e)
        except Exception:  # noqa: BLE001
            log.exception("[%s] unexpected error", self.name)
        finally:
            self.closed = True
            if self.session is not None:
                await run_on_main(self.daemon.detach, self, self.graceful)
            self.writer.transport.abort()

    async def _handshake(self):
        hello = await self.expect("hello", 15)
        if int(hello.get("v", 0)) != PROTOCOL_VERSION:
            self.send({"t": "bye", "reason": "version"})
            raise ProtocolError("unsupported protocol version")
        device_id = str(hello.get("device_id", ""))
        if not DEVICE_ID_RE.match(device_id):
            raise ProtocolError("bad device id")
        self.device_id = device_id
        self.name = str(hello.get("name") or "Tablet")[:64]

        stored = self.daemon.store.device(device_id)
        token = hello.get("token")
        authenticated = False
        if stored and isinstance(token, str):
            try:
                authenticated = security.token_matches(bytes.fromhex(token), stored["token_hash"])
            except ValueError:
                authenticated = False
        if not authenticated:
            self.send({"t": "pair_required"})
            await self._pair()
        log.info("[%s] authenticated", self.name)
        self.send({"t": "welcome", "server_id": self.daemon.store.server_id,
                   "server_name": self.daemon.store.server_name})

    async def _pair(self):
        commit = bytes.fromhex((await self.expect("pair_commit", 60))["commit"])
        if len(commit) != 32:
            raise ProtocolError("bad commitment")
        nonce_s = security.new_nonce()
        self.send({"t": "pair_nonce", "nonce": nonce_s.hex()})
        nonce_c = bytes.fromhex((await self.expect("pair_reveal", 30))["nonce"])
        if not security.check_commit(commit, nonce_c):
            raise ProtocolError("pairing commitment mismatch")
        code = security.pairing_code(self.daemon.fingerprint, nonce_c, nonce_s)

        decision = self.loop.create_future()
        request_id = await run_on_main(self.daemon.request_pairing, self.name, code, self.loop, decision)
        if request_id is None:
            self.send({"t": "pair_denied", "reason": "busy"})
            raise PairingRefused("another pairing is in progress")

        # Watch for the tablet going away while the user decides.
        eof = asyncio.ensure_future(self.reader.read(1))
        done, _ = await asyncio.wait({decision, eof}, timeout=PAIRING_TIMEOUT,
                                     return_when=asyncio.FIRST_COMPLETED)
        eof.cancel()
        await asyncio.gather(eof, return_exceptions=True)
        if decision not in done:
            await run_on_main(self.daemon.resolve_pairing, request_id, False)
            self.send({"t": "pair_denied", "reason": "timeout"})
            raise PairingRefused("timed out or tablet disconnected")
        if not decision.result():
            self.send({"t": "pair_denied", "reason": "rejected"})
            raise PairingRefused("rejected on the laptop")
        token = security.new_token()
        self.daemon.store.add_device(self.device_id, self.name, security.hash_token(token))
        self.send({"t": "pair_ok", "token": token.hex()})
        GLib.idle_add(self.daemon.emit_status_changed)

    async def _stream(self):
        start = await self.expect("start", 30)
        width = int(start["width"]) & ~1
        height = int(start["height"]) & ~1
        if not (320 <= width <= 4096 and 320 <= height <= 4096):
            raise ProtocolError("unsupported resolution")
        max_fps = self.daemon.store.stream_settings()["max_fps"]
        fps = max(15, min(int(start.get("fps", 60)), max_fps, 60))
        quality = start.get("quality") if start.get("quality") in ("low", "balanced", "high") else "balanced"
        scale = max(1.0, min(float(start.get("scale", 2.0)), 4.0))

        resume = bool(start.get("resume"))
        self.session = await run_on_main(self.daemon.attach, self, width, height, fps, quality, scale, resume)
        if self.session is None:
            # An automatic reconnect, but the monitor is already gone (the tablet
            # was away too long, or it was disconnected here): don't bring it back.
            log.info("[%s] not resuming: its screen was already removed", self.name)
            self.send({"t": "bye", "reason": "disconnected"})
            self.writer.close()
            try:
                await asyncio.wait_for(self.writer.wait_closed(), BYE_TIMEOUT)
            except (asyncio.TimeoutError, ConnectionError, ssl.SSLError, OSError):
                pass
            return
        self.attached_at = time.monotonic()
        self.drop_limit = max(MIN_DROP_LIMIT, int(self.session.kbps * 125 * DROP_LATENCY))
        self.send({"t": "started", "width": width, "height": height, "fps": fps,
                   "kbps": self.session.kbps})

        watchdog = asyncio.ensure_future(self._watchdog())
        stats = asyncio.ensure_future(self._log_stats())
        try:
            while True:
                msg = await self.recv(HEARTBEAT_TIMEOUT + 5)
                session = self.session
                if session.lost_since is not None and time.monotonic() - self.attached_at > STABLE_SECONDS:
                    session.lost_since = None  # the link is healthy again
                t = msg["t"]
                if t == "ping":
                    self.send({"t": "pong", "ts": msg.get("ts", 0), "now_us": int(time.time() * 1_000_000)})
                elif t in ("touch", "ptr"):
                    GLib.idle_add(self._input, msg)
                elif t == "idr":
                    self.waiting_key = True
                    self._request_keyframe(force=True)
                elif t == "pause":
                    self.paused = True
                elif t == "resume":
                    self.paused = False
                    self.waiting_key = True
                    self._request_keyframe(force=True)
                elif t == "stop":
                    self.graceful = True
                    return
        finally:
            watchdog.cancel()
            stats.cancel()

    def _input(self, msg):
        session = self.session
        if session is not None and session.client is self:
            try:
                session.handle_input(msg)
            except (KeyError, ValueError, TypeError) as e:
                log.debug("bad input event: %s", e)
        return GLib.SOURCE_REMOVE

    async def _log_stats(self):
        """Keeps self.stats current for the app window and logs a summary now and then."""
        last = (0, 0, 0, 0, 0)
        ticks = 0
        while not self.closed:
            await asyncio.sleep(STATS_INTERVAL)
            encoder = self.session.encoder if self.session else None
            now = (self.frames_encoded, self.frames_sent, self.frames_dropped, self.bytes_sent,
                   encoder.captured if encoder else 0)
            enc, sent, dropped, sent_bytes, captured = (a - b for a, b in zip(now, last))
            last = now
            self.stats = {"fps": round(sent / STATS_INTERVAL, 1),
                          "captured_fps": round(captured / STATS_INTERVAL, 1),
                          "kbps": sent_bytes * 8 // 1000 // STATS_INTERVAL, "dropped": dropped}
            ticks += 1
            if dropped or ticks % 15 == 0:
                log.info("[%s] captured %.1f fps, encoded %.1f fps, sent %.1f fps, %d kbit/s, %d dropped",
                         self.name, self.stats["captured_fps"], enc / STATS_INTERVAL, self.stats["fps"],
                         self.stats["kbps"], dropped)

    async def _watchdog(self):
        while not self.closed:
            await asyncio.sleep(1)
            if time.monotonic() - self.last_rx > HEARTBEAT_TIMEOUT:
                log.info("[%s] heartbeat lost", self.name)
                self.writer.transport.abort()
                return

    def post_frame(self, data, keyframe, pts_us):
        """Called from a GStreamer thread."""
        self.frames_encoded += 1
        if not self.closed:
            self.loop.call_soon_threadsafe(self._send_frame, data, keyframe, pts_us)

    def _send_frame(self, data, keyframe, pts_us):
        if self.closed or self.paused:
            return
        queued = self.writer.transport.get_write_buffer_size()
        if self.waiting_key:
            if not keyframe or queued > KEYFRAME_LIMIT:
                self._request_keyframe()
                return
            self.waiting_key = False
        elif queued > self.drop_limit:
            # The network can't keep up: skip to the next keyframe so the
            # tablet never sees a broken reference chain.
            self.waiting_key = True
            self.frames_dropped += 1
            self._request_keyframe()
            return
        self.writer.write(encode_video_header(len(data), keyframe, pts_us))
        self.writer.write(data)
        self.frames_sent += 1
        self.bytes_sent += len(data)

    def _request_keyframe(self, force=False):
        now = time.monotonic()
        if not force and now - self._last_key_request < 0.3:
            return
        self._last_key_request = now
        session = self.session
        if session is not None:
            GLib.idle_add(lambda: session.request_keyframe() and False)

    def close_soon(self, reason=None):
        """Thread-safe: tell the tablet why (optional) and drop the connection."""
        def close():
            if self.closed:
                return
            if reason:
                # Stop the video so the bye isn't stuck behind it, then close
                # gracefully: abort() would throw away the queued bye.
                self.paused = True
                self.send({"t": "bye", "reason": reason})
                self.writer.close()
                self.loop.call_later(BYE_TIMEOUT, self.writer.transport.abort)
            else:
                self.writer.transport.abort()
        self.loop.call_soon_threadsafe(close)


class PairingRefused(Exception):
    pass


class Daemon:
    def __init__(self):
        self.store = Store()
        security.ensure_identity(self.store.cert_path, self.store.key_path, self.store.server_name)
        self.fingerprint = security.cert_fingerprint(self.store.cert_path)
        self.ssl_ctx = security.server_ssl_context(self.store.cert_path, self.store.key_path)
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.sessions = {}
        self.pairing = None
        detect_backend(self.store.stream_settings()["encoder"])  # test encoders once, at startup
        self.layout = DisplayLayout(self.bus, self._on_monitors_changed)
        self.known_virtual = self.layout.virtual_connectors()
        self.notifier = Notifier(self.bus, APP_ID)
        self.main_loop = GLib.MainLoop()
        self.net_loop = None

    def run(self):
        self._own_bus_name()
        self._start_network()
        self.avahi = AvahiPublisher(Gio.bus_get_sync(Gio.BusType.SYSTEM), self.store.server_name, SERVICE_TYPE, self.store.port, {
            "id": self.store.server_id,
            "fp": self.fingerprint.hex()[:16],
            "v": PROTOCOL_VERSION,
        })
        self._watch_sleep()
        for sig in (signal.SIGINT, signal.SIGTERM):
            GLibUnix.signal_add(GLib.PRIORITY_HIGH, sig, self._quit)
        log.info("ready; certificate fingerprint %s", self.fingerprint.hex()[:16])
        self.main_loop.run()
        for session in list(self.sessions.values()):
            session.stop()

    def _watch_sleep(self):
        """Disconnect tablets when the laptop suspends, so they don't wait for it."""
        def on_sleep(conn, sender, path, iface, signal_name, params, *args):
            if params.unpack()[0] and self.sessions:
                log.info("laptop is going to sleep; disconnecting tablets")
                for session in list(self.sessions.values()):
                    self.end_session(session, "disconnected")
        try:
            system = Gio.bus_get_sync(Gio.BusType.SYSTEM)
            system.signal_subscribe("org.freedesktop.login1", "org.freedesktop.login1.Manager", "PrepareForSleep",
                                    "/org/freedesktop/login1", None, Gio.DBusSignalFlags.NONE, on_sleep)
        except GLib.Error as e:
            log.warning("cannot watch for suspend: %s", e.message)

    def _quit(self):
        self.main_loop.quit()
        return GLib.SOURCE_REMOVE

    def _own_bus_name(self):
        node = Gio.DBusNodeInfo.new_for_xml(DBUS_XML)
        self.bus.register_object_with_closures2(DBUS_PATH, node.interfaces[0], self._on_dbus_call, None, None)

        def lost(*args):
            log.error("another androidconnectd is already running")
            sys.exit(1)
        Gio.bus_own_name_on_connection(self.bus, DBUS_NAME, Gio.BusNameOwnerFlags.DO_NOT_QUEUE, None, lost)

    def _start_network(self):
        ready = threading.Event()
        error = []

        def thread():
            self.net_loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.net_loop)

            async def handle(reader, writer):
                await ClientConnection(self, reader, writer).run()

            try:
                self.net_loop.run_until_complete(asyncio.start_server(
                    handle, host=None, port=self.store.port, ssl=self.ssl_ctx,
                    ssl_handshake_timeout=10, reuse_address=True))
            except OSError as e:
                error.append(e)
                ready.set()
                return
            ready.set()
            self.net_loop.run_forever()

        threading.Thread(target=thread, name="network", daemon=True).start()
        ready.wait()
        if error:
            log.error("cannot listen on port %d: %s", self.store.port, error[0])
            sys.exit(1)
        log.info("listening on port %d (TLS 1.3)", self.store.port)

    def attach(self, client, width, height, fps, quality, scale, resume=False):
        settings = self.store.stream_settings()
        session = self.sessions.get(client.device_id)
        if resume and session is None:
            return None
        if session and session.client is not None and session.client is not client:
            log.info("[%s] replacing previous connection", client.name)
            old = session.client
            session.client = None
            old.close_soon()
        if session and not session.matches(width, height, fps, quality, settings):
            self.end_session(session, None)
            session = None
        if session is None:
            session = DisplaySession(self, client.device_id, client.name, width, height, fps, quality, scale,
                                     settings)
            self.sessions[client.device_id] = session
            try:
                session.start()
            except GLib.Error:
                del self.sessions[client.device_id]
                raise
            log.info("[%s] created virtual monitor %dx%d", client.name, width, height)
        else:
            log.info("[%s] reattached to existing virtual monitor", client.name)
        session.attach(client)
        self.emit_status_changed()
        return session

    def detach(self, client, graceful):
        session = self.sessions.get(client.device_id)
        if session is None or session.client is not client:
            return
        session.client = None
        if graceful:
            log.info("[%s] disconnected", client.name)
            self.end_session(session, None)
            return
        now = time.monotonic()
        if session.lost_since is None:
            session.lost_since = now
        # Count from the first drop, so a tablet that keeps reconnecting and
        # dropping (e.g. asleep) can't hold the monitor open forever.
        remaining = GRACE_SECONDS - (now - session.lost_since)
        if remaining <= 0:
            log.info("[%s] connection keeps dropping; removing the monitor", client.name)
            self.end_session(session, None)
            return
        log.info("[%s] connection dropped; keeping the monitor for %ds", client.name, remaining)

        def expire():
            session.grace_timer = 0
            if session.client is None:
                log.info("[%s] tablet did not come back", session.name)
                self.end_session(session, None)
            return GLib.SOURCE_REMOVE
        session.grace_timer = GLib.timeout_add(int(remaining * 1000), expire)
        self.emit_status_changed()

    def end_session(self, session, reason):
        if self.sessions.get(session.device_id) is not session:
            return
        del self.sessions[session.device_id]
        log.info("[%s] removed the virtual monitor%s", session.name, f" ({reason})" if reason else "")
        client = session.client
        session.client = None
        session.stop()
        if client is not None:
            client.close_soon(reason)
        self.emit_status_changed()

    def _on_monitors_changed(self):
        connectors = self.layout.virtual_connectors()
        new = connectors - self.known_virtual
        self.known_virtual = connectors
        for session in self.sessions.values():
            if session.connector and session.connector not in connectors:
                session.set_connector(None)
        for session in self.sessions.values():
            session.schedule_encoder_restart()
        waiting = sorted((s for s in self.sessions.values() if s.connector is None), key=lambda s: s.created)
        for connector, session in zip(sorted(new), waiting):
            session.set_connector(connector)
            GLib.idle_add(self._place, session)
        for session in self.sessions.values():
            if session.connector:
                session.input_scale = self.layout.input_scale(session.connector)
        if not new:
            for session in self.sessions.values():
                if session.connector:
                    placement = self.layout.read_placement(session.connector)
                    if placement:
                        self.store.set_layout(session.device_id, placement)

    def _place(self, session):
        if session.connector:
            device = self.store.device(session.device_id) or {}
            placement = device.get("layout") or {"side": "right", "offset": 0, "scale": session.default_scale}
            self.layout.apply_placement(session.connector, placement)
        return GLib.SOURCE_REMOVE

    def request_pairing(self, name, code, loop, decision):
        if self.pairing is not None:
            return None
        request_id = str(uuid.uuid4())
        pretty = f"{code[:3]} {code[3:]}"

        def on_action(action):
            if action in ("accept", "reject"):
                self.resolve_pairing(request_id, action == "accept")
            elif action == "default":
                launch_gui()
        nid = self.notifier.notify(
            f"Pair with “{name}”?",
            f"Make sure the tablet shows the code <b>{pretty}</b>, then accept.",
            actions=[("default", "Open"), ("reject", "Reject"), ("accept", "Accept")],
            on_action=on_action, urgent=True)
        self.pairing = {"request_id": request_id, "name": name, "code": code,
                        "loop": loop, "decision": decision, "notification": nid}
        self.emit_status_changed()
        return request_id

    def resolve_pairing(self, request_id, accepted):
        p = self.pairing
        if p is None or p["request_id"] != request_id:
            return False
        self.pairing = None
        self.notifier.close(p["notification"])

        def deliver():
            if not p["decision"].done():
                p["decision"].set_result(accepted)
        p["loop"].call_soon_threadsafe(deliver)
        log.info("pairing with %s %s", p["name"], "accepted" if accepted else "rejected")
        self.emit_status_changed()
        return True

    def status(self):
        devices = self.store.devices()
        return {
            "server_name": self.store.server_name,
            "port": self.store.port,
            "addresses": local_addresses(),
            "fingerprint": self.fingerprint.hex()[:16],
            "encoder": detect_backend(self.store.stream_settings()["encoder"])[0],
            "sessions": [{
                "device_id": s.device_id, "name": s.name, "width": s.width, "height": s.height,
                "fps": s.fps, "connected": s.client is not None,
                "encoder": s.encoder.backend if s.encoder else None,
                "stats": s.client.stats if s.client else None,
            } for s in self.sessions.values()],
            "pairing": ({"request_id": self.pairing["request_id"], "name": self.pairing["name"],
                         "code": self.pairing["code"]} if self.pairing else None),
            "devices": [{"device_id": k, "name": v["name"]} for k, v in devices.items()],
        }

    def emit_status_changed(self):
        self.bus.emit_signal(None, DBUS_PATH, DBUS_IFACE, "StatusChanged", None)
        return GLib.SOURCE_REMOVE

    def _on_dbus_call(self, conn, sender, path, iface, method, params, invocation):
        args = params.unpack()
        if method == "GetStatus":
            invocation.return_value(GLib.Variant("(s)", (json.dumps(self.status()),)))
            return
        if method == "GetSettings":
            reply = {"settings": self.store.stream_settings(), "encoders": available_backends()}
            invocation.return_value(GLib.Variant("(s)", (json.dumps(reply),)))
            return
        if method == "SetSettings":
            try:
                self.store.update_stream_settings(json.loads(args[0]))
            except (ValueError, TypeError) as e:
                invocation.return_dbus_error(DBUS_IFACE + ".InvalidArgs", str(e))
                return
            log.info("settings changed: %s", self.store.stream_settings())
        if method in ("AcceptPairing", "RejectPairing"):
            self.resolve_pairing(args[0], method == "AcceptPairing")
        elif method == "ForgetDevice":
            session = self.sessions.get(args[0])
            if session:
                self.end_session(session, "forgotten")
            self.store.remove_device(args[0])
            self.emit_status_changed()
        elif method == "DisconnectDevice":
            session = self.sessions.get(args[0])
            if session:
                self.end_session(session, "disconnected")
        invocation.return_value(None)


def launch_gui():
    app = Gio.DesktopAppInfo.new(APP_ID + ".desktop")
    if app is None:
        log.warning("desktop file %s.desktop is not installed", APP_ID)
        return
    try:
        app.launch([], None)
    except GLib.Error as e:
        log.warning("could not open the app: %s", e.message)


def main():
    logging.basicConfig(level=logging.DEBUG if "-v" in sys.argv else logging.INFO,
                        format="%(levelname)s %(name)s: %(message)s")
    Daemon().run()


if __name__ == "__main__":
    main()
