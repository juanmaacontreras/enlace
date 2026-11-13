"""share: something sent from the device lands in the PC's clipboard.

Text and images go to the clipboard, ready to paste. Anything a clipboard
cannot hold usefully, or that the device marks `"to": "downloads"`, is saved
in ~/Downloads instead of being dropped.

Does not: send anything from the PC to the device, or open what it receives.
"""

from __future__ import annotations

import asyncio
import io
import os
import re
from pathlib import Path

from .. import desktop
from . import Module


def downloads() -> Path:
    return Path(os.environ.get("ENLACE_DOWNLOADS") or os.path.expanduser("~/Downloads"))


def safe_name(name: object, mime: str) -> str:
    """A file name with no path in it, whatever the device sent."""
    base = os.path.basename(str(name or "")).strip().lstrip(".")
    base = re.sub(r"[\x00-\x1f/\\]", "_", base)[:120]
    if not base:
        ext = {"image/png": ".png", "image/jpeg": ".jpg", "text/plain": ".txt"}.get(mime, "")
        base = "enlace" + ext
    return base


def free_path(folder: Path, name: str) -> Path:
    path = folder / name
    stem, suffix = path.stem, path.suffix
    n = 1
    while path.exists():
        path = folder / f"{stem}-{n}{suffix}"
        n += 1
    return path


def to_png(data: bytes) -> bytes | None:
    """The Wayland clipboard never converts and Chromium only asks for
    image/png, so every image is offered as PNG."""
    try:
        from PIL import Image

        out = io.BytesIO()
        Image.open(io.BytesIO(data)).save(out, "PNG")
        return out.getvalue()
    except Exception:
        return None


async def copy(data: bytes, mime: str) -> bool:
    # --type always: wl-copy reading stdin otherwise assumes text/plain.
    code, _ = await desktop.run("wl-copy", "--type", mime, stdin=data, capture=False)
    return code == 0


class Share(Module):
    name = "share"

    async def handle(self, session, header, body):
        if header.get("type") != "item":
            await session.send({"type": "error", "code": "bad_request", "module": self.name})
            return
        mime = str(header.get("mime") or "application/octet-stream").lower()
        keep = header.get("to") == "downloads"
        result, name = "saved", ""

        if not keep and mime.startswith("text/"):
            if await copy(body, "text/plain;charset=utf-8"):
                result = "clipboard"
                await desktop.notify("Clipboard", f"Text from {session.name}  ·  ready to paste")
        elif not keep and mime.startswith("image/"):
            png = body if mime == "image/png" else await asyncio.to_thread(to_png, body)
            if png and await copy(png, "image/png"):
                result = "clipboard"
                await desktop.notify("Clipboard", f"Image from {session.name}  ·  ready to paste")

        if result == "saved":
            folder = downloads()
            folder.mkdir(parents=True, exist_ok=True)
            path = free_path(folder, safe_name(header.get("name"), mime))
            await asyncio.to_thread(path.write_bytes, body)
            name = path.name
            await desktop.notify("Enlace", f"{name}  ·  saved to Downloads")

        reply = {"module": self.name, "type": "done", "result": result}
        if name:
            reply["name"] = name
        await session.send(reply)


MODULE = Share
