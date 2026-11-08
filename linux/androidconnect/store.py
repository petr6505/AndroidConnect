"""Persistent state in ~/.config/androidconnect."""

import json
import os
import socket
import threading
import uuid

CONFIG_DIR = os.path.join(os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "androidconnect")

# Streaming settings changed from the app window; they apply to the next connection.
STREAM_DEFAULTS = {
    "encoder": "auto",       # auto | vaapi | vaapi-lp | x264
    "max_fps": 60,           # upper limit for what tablets may ask for
    "bitrate_mbps": 0,       # 0 = derived from resolution, frame rate and the tablet's quality setting
    "show_cursor": True,     # draw the mouse pointer into the video
}


class Store:
    def __init__(self, directory: str = CONFIG_DIR):
        self.dir = directory
        os.makedirs(self.dir, mode=0o700, exist_ok=True)
        self.cert_path = os.path.join(self.dir, "cert.pem")
        self.key_path = os.path.join(self.dir, "key.pem")
        self._path = os.path.join(self.dir, "state.json")
        self._lock = threading.Lock()
        self._data = self._load()
        changed = False
        if "server_id" not in self._data:
            self._data["server_id"] = str(uuid.uuid4())
            changed = True
        self._data.setdefault("devices", {})
        self._data.setdefault("settings", {})
        if changed:
            self._save()

    def _load(self) -> dict:
        try:
            with open(self._path) as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def _save(self) -> None:
        tmp = self._path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(self._data, f, indent=2)
        os.replace(tmp, self._path)

    @property
    def server_id(self) -> str:
        return self._data["server_id"]

    @property
    def server_name(self) -> str:
        return self._data["settings"].get("name") or socket.gethostname()

    @property
    def port(self) -> int:
        from . import DEFAULT_PORT
        return int(self._data["settings"].get("port", DEFAULT_PORT))

    def stream_settings(self) -> dict:
        with self._lock:
            saved = self._data["settings"].get("stream", {})
            return {k: saved.get(k, v) for k, v in STREAM_DEFAULTS.items()}

    def update_stream_settings(self, changes: dict) -> dict:
        """Merges known keys (with the default's type) and returns the result."""
        with self._lock:
            stream = self._data["settings"].setdefault("stream", {})
            for key, default in STREAM_DEFAULTS.items():
                if key in changes:
                    stream[key] = type(default)(changes[key])
            self._save()
        return self.stream_settings()

    def devices(self) -> dict:
        with self._lock:
            return json.loads(json.dumps(self._data["devices"]))

    def device(self, device_id: str):
        with self._lock:
            d = self._data["devices"].get(device_id)
            return dict(d) if d else None

    def add_device(self, device_id: str, name: str, token_hash: str) -> None:
        with self._lock:
            prev = self._data["devices"].get(device_id, {})
            self._data["devices"][device_id] = {
                "name": name,
                "token_hash": token_hash,
                "layout": prev.get("layout"),
            }
            self._save()

    def remove_device(self, device_id: str) -> bool:
        with self._lock:
            if self._data["devices"].pop(device_id, None) is None:
                return False
            self._save()
            return True

    def set_layout(self, device_id: str, layout: dict) -> None:
        with self._lock:
            dev = self._data["devices"].get(device_id)
            if dev is None or dev.get("layout") == layout:
                return
            dev["layout"] = layout
            self._save()
