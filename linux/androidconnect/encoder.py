"""GStreamer capture + H.264 encoding of a Mutter virtual monitor."""

import collections
import logging
import time

import gi

gi.require_version("Gst", "1.0")
gi.require_version("GstVideo", "1.0")
from gi.repository import GLib, Gst, GstVideo  # noqa: E402

log = logging.getLogger(__name__)

Gst.init(None)

# (name, pipeline fragment) - placeholders get filled in by Encoder.
#
# VA-API takes Mutter's DMA-BUFs directly. Mutter only sends frames on damage
# and vah264enc buffers 2 frames at variable fps, so a single keystroke could
# get stuck. vacompositor turns it into a constant-rate stream on the GPU.
# min-qp keeps it from burning bitrate on a static image (repeats ~100 B).
_VA_INPUT = ("vapostproc ! video/x-raw(memory:VAMemory),format=NV12 ! vacompositor latency=0 ! "
             "video/x-raw(memory:VAMemory),format=NV12,width={w},height={h},framerate={fps}/1 ! ")
_BACKENDS = [
    ("vaapi", _VA_INPUT + "vah264enc name=enc rate-control=vbr bitrate={kbps} target-percentage=70 min-qp={qp} "
                          "key-int-max={gop} b-frames=0 ref-frames=1 target-usage=6 "),
    ("vaapi-lp", _VA_INPUT + "vah264lpenc name=enc rate-control=vbr bitrate={kbps} target-percentage=70 min-qp={qp} "
                             "key-int-max={gop} b-frames=0 ref-frames=1 target-usage=6 "),
    ("x264", "videoconvert n-threads=4 ! video/x-raw,format=I420 ! "
             "x264enc name=enc tune=zerolatency speed-preset=superfast bitrate={kbps} qp-min={qp} "
             "key-int-max={gop} bframes=0 threads=0 "),
]

BACKEND_NAMES = [name for name, _ in _BACKENDS]
_ZERO_COPY = {"vaapi", "vaapi-lp"}

_working = {}


def _test_backend(fragment: str) -> bool:
    desc = ("videotestsrc num-buffers=3 ! video/x-raw,width=1280,height=720,format=BGRx ! "
            + fragment.format(kbps=4000, gop=30, qp=20, w=1280, h=720, fps=30) + "! fakesink")
    try:
        pipeline = Gst.parse_launch(desc)
    except GLib.Error:
        return False
    pipeline.set_state(Gst.State.PLAYING)
    msg = pipeline.get_bus().timed_pop_filtered(
        5 * Gst.SECOND, Gst.MessageType.EOS | Gst.MessageType.ERROR)
    pipeline.set_state(Gst.State.NULL)
    return msg is not None and msg.type == Gst.MessageType.EOS


def _works(name, fragment) -> bool:
    if name not in _working:
        _working[name] = _test_backend(fragment)
    return _working[name]


def detect_backend(preferred="auto"):
    """The preferred H.264 encoder if it works, else the best working one.
    Test results are cached."""
    candidates = sorted(_BACKENDS, key=lambda b: b[0] != preferred)
    for name, fragment in candidates:
        if _works(name, fragment):
            if preferred not in ("auto", name):
                log.warning("%s encoder is not available, using %s", preferred, name)
            return name, fragment
    raise RuntimeError("no working H.264 encoder found (install gstreamer1.0-plugins-bad or -ugly)")


def available_backends():
    return [name for name, fragment in _BACKENDS if _works(name, fragment)]


def min_qp(quality: str) -> int:
    """Lowest quantizer the encoder may use: the sharpest it gets once the image is still."""
    return {"low": 24, "balanced": 20, "high": 16}.get(quality, 20)


def bitrate_kbps(width: int, height: int, fps: int, quality: str) -> int:
    bits_per_pixel = {"low": 0.05, "balanced": 0.09, "high": 0.16}.get(quality, 0.09)
    kbps = int(width * height * fps * bits_per_pixel / 1000)
    return max(4000, min(kbps, 100000))


class Encoder:
    """Captures a PipeWire node and emits H.264 access units via on_frame
    (called from a GStreamer streaming thread)."""

    def __init__(self, node_id, width, height, fps, kbps, qp, on_frame, on_error, backend="auto"):
        self.on_frame = on_frame
        self.on_error = on_error
        name, fragment = detect_backend(backend)
        self.backend = name
        raw = f"width={width},height={height},max-framerate={fps}/1"
        caps = f"video/x-raw(memory:DMABuf),{raw};video/x-raw,{raw}" if name in _ZERO_COPY else f"video/x-raw,{raw}"
        # Without a compositor (x264), have pipewiresrc repeat the last frame
        # instead so the encoder's rate control sees a steady stream.
        keepalive = 0 if name in _ZERO_COPY else max(1, 1000 // fps)
        desc = (
            f"pipewiresrc name=src path={node_id} always-copy=false do-timestamp=true keepalive-time={keepalive} ! "
            f"{caps} ! "
            f"queue max-size-buffers=2 leaky=downstream ! "
            + fragment.format(kbps=kbps, gop=fps * 10, qp=qp, w=width, h=height, fps=fps)
            + "! video/x-h264,profile=high,stream-format=byte-stream,alignment=au ! "
            "appsink name=sink emit-signals=true sync=false max-buffers=8 drop=false"
        )
        log.debug("pipeline: %s", desc)
        self.pipeline = Gst.parse_launch(desc)
        self.sink = self.pipeline.get_by_name("sink")
        self.sink.connect("new-sample", self._on_sample)
        # Frames Mutter actually delivered (the encoder output includes repeats).
        self.captured = 0
        self.pipeline.get_by_name("src").get_static_pad("src").add_probe(
            Gst.PadProbeType.BUFFER, self._on_capture)
        # Wall-clock time each frame entered the encoder. The encoder emits
        # frames in order, one per input, so a FIFO pairs them up. The tablet
        # uses this to show the real capture-to-screen latency.
        self._enc_times = collections.deque(maxlen=64)
        self.pipeline.get_by_name("enc").get_static_pad("sink").add_probe(
            Gst.PadProbeType.BUFFER, self._on_encoder_input)
        bus = self.pipeline.get_bus()
        bus.add_signal_watch()
        self._bus_handler = bus.connect("message", self._on_message)

    def start(self):
        if self.pipeline.set_state(Gst.State.PLAYING) == Gst.StateChangeReturn.FAILURE:
            raise RuntimeError("could not start the encoding pipeline")

    def stop(self):
        if self.pipeline is None:
            return
        bus = self.pipeline.get_bus()
        bus.disconnect(self._bus_handler)
        bus.remove_signal_watch()
        self.pipeline.set_state(Gst.State.NULL)
        self.pipeline = None

    def request_keyframe(self):
        if self.pipeline is None:
            return
        event = GstVideo.video_event_new_upstream_force_key_unit(Gst.CLOCK_TIME_NONE, True, 0)
        self.sink.get_static_pad("sink").push_event(event)

    def _on_capture(self, pad, info):
        self.captured += 1
        return Gst.PadProbeReturn.OK

    def _on_encoder_input(self, pad, info):
        self._enc_times.append(time.time())
        return Gst.PadProbeReturn.OK

    def _on_sample(self, sink):
        sample = sink.emit("pull-sample")
        if sample is None:
            return Gst.FlowReturn.EOS
        buf = sample.get_buffer()
        data = buf.extract_dup(0, buf.get_size())
        keyframe = not buf.has_flags(Gst.BufferFlags.DELTA_UNIT)
        captured = self._enc_times.popleft() if self._enc_times else time.time()
        self.on_frame(data, keyframe, int(captured * 1_000_000))
        return Gst.FlowReturn.OK

    def _on_message(self, bus, msg):
        if msg.type == Gst.MessageType.ERROR:
            err, debug = msg.parse_error()
            log.error("encoder error: %s (%s)", err.message, debug)
            self.on_error(err.message)
        elif msg.type == Gst.MessageType.WARNING:
            err, debug = msg.parse_warning()
            log.warning("encoder warning: %s", err.message)
