"""Wire protocol shared with the Android client.

Every message is framed as:  u8 type | u32 big-endian length | payload

  type 1 (JSON)  - UTF-8 JSON object with a "t" field naming the message
  type 2 (VIDEO) - u8 flags (bit0 = keyframe) | u64 capture time (Unix epoch, microseconds) | H.264 Annex-B access unit

The whole stream runs inside TLS 1.3.
"""

import asyncio
import json
import struct

MSG_JSON = 1
MSG_VIDEO = 2

FLAG_KEYFRAME = 0x01

MAX_JSON = 64 * 1024
MAX_FRAME = 32 * 1024 * 1024

_HEADER = struct.Struct(">BI")
_VIDEO_HEADER = struct.Struct(">BQ")


class ProtocolError(Exception):
    pass


def encode_json(obj) -> bytes:
    payload = json.dumps(obj, separators=(",", ":")).encode()
    return _HEADER.pack(MSG_JSON, len(payload)) + payload


def encode_video_header(data_len: int, keyframe: bool, pts_us: int) -> bytes:
    flags = FLAG_KEYFRAME if keyframe else 0
    return _HEADER.pack(MSG_VIDEO, _VIDEO_HEADER.size + data_len) + _VIDEO_HEADER.pack(flags, pts_us)


async def read_message(reader: asyncio.StreamReader):
    """Returns (type, payload) where payload is a dict for JSON messages."""
    header = await reader.readexactly(_HEADER.size)
    mtype, length = _HEADER.unpack(header)
    if mtype == MSG_JSON:
        if length > MAX_JSON:
            raise ProtocolError("JSON message too large")
        data = await reader.readexactly(length)
        try:
            obj = json.loads(data)
        except ValueError as e:
            raise ProtocolError(f"bad JSON: {e}") from e
        if not isinstance(obj, dict) or not isinstance(obj.get("t"), str):
            raise ProtocolError("JSON message without type")
        return mtype, obj
    if length > MAX_FRAME:
        raise ProtocolError("frame too large")
    return mtype, await reader.readexactly(length)
