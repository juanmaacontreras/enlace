"""Wire format shared with the Android app. The contract is docs/contract.md.

A frame is: 4 bytes big-endian header length, the header as UTF-8 JSON, then
`header["size"]` bytes of body when that key is present. Everything travels
inside TLS, so the frame itself carries no integrity or secrecy of its own.
"""

from __future__ import annotations

import asyncio
import json
import struct

VERSION = 1
PORT = 47800
MAX_HEADER = 64 * 1024
MAX_BODY = 256 * 1024 * 1024


class ProtocolError(Exception):
    """The peer sent something the contract does not allow."""


def encode(header: dict, body: bytes = b"") -> bytes:
    head = dict(header)
    head.pop("size", None)
    if body:
        head["size"] = len(body)
    raw = json.dumps(head, separators=(",", ":"), ensure_ascii=False).encode()
    if len(raw) > MAX_HEADER:
        raise ProtocolError("header too large")
    return struct.pack(">I", len(raw)) + raw + body


def decode_header(raw: bytes) -> dict:
    try:
        header = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("header is not JSON") from exc
    if not isinstance(header, dict):
        raise ProtocolError("header is not an object")
    size = header.get("size", 0)
    if not isinstance(size, int) or isinstance(size, bool) or size < 0:
        raise ProtocolError("bad size")
    return header


def decode(data: bytes) -> tuple[dict, bytes]:
    """Decode exactly one frame held in memory (tests and fixtures)."""
    if len(data) < 4:
        raise ProtocolError("truncated frame")
    (length,) = struct.unpack(">I", data[:4])
    if length > MAX_HEADER:
        raise ProtocolError("header too large")
    if len(data) < 4 + length:
        raise ProtocolError("truncated frame")
    header = decode_header(data[4 : 4 + length])
    body = data[4 + length :]
    if len(body) != header.get("size", 0):
        raise ProtocolError("body does not match size")
    return header, body


async def read_frame(
    reader: asyncio.StreamReader, max_body: int = MAX_BODY
) -> tuple[dict, bytes]:
    """Read one frame. Raises IncompleteReadError when the peer hangs up."""
    (length,) = struct.unpack(">I", await reader.readexactly(4))
    if length > MAX_HEADER:
        raise ProtocolError("header too large")
    header = decode_header(await reader.readexactly(length))
    size = header.get("size", 0)
    if size > max_body:
        raise ProtocolError("body too large")
    body = await reader.readexactly(size) if size else b""
    return header, body


# --- messages the PC sends -------------------------------------------------
# One builder per message so the contract tests can compare them against
# contract/fixtures.json, which the app parses in its own tests.


def welcome(pc_id: str, pc_name: str, modules: list[str], token: str | None = None) -> dict:
    msg = {
        "type": "welcome",
        "v": VERSION,
        "pc": {"id": pc_id, "name": pc_name},
        "modules": modules,
    }
    if token is not None:
        msg["token"] = token
    return msg


def error(code: str, module: str | None = None) -> dict:
    msg = {"type": "error", "code": code}
    if module is not None:
        msg["module"] = module
    return msg


def discovery_reply(pc_id: str, port: int) -> dict:
    return {"enlace": VERSION, "pc": pc_id, "port": port}
