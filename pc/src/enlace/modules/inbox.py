"""inbox: what the PC queued for the devices (`enlace send`), fetched when the app opens.

Items wait in ~/.local/state/enlace/outbox, one folder each. A device asks for
the list, downloads each item and confirms it; the confirmation is what removes
it, so a transfer cut halfway is simply fetched again. Whichever paired device
opens the app first gets it. Unclaimed items are dropped after a week.

Does not: push anything (the device has no connection open while the app is
closed), or target one device among several.
"""

from __future__ import annotations

import asyncio
import json
import mimetypes
import secrets
import shutil
import time
from pathlib import Path

from .. import store
from . import Module

MAX_BYTES = 64 * 1024 * 1024  # the app holds an item in memory while saving it
KEEP_SECONDS = 7 * 24 * 3600


class TooBig(Exception):
    pass


def outbox() -> Path:
    return store.state_dir() / "outbox"


def queue(data: bytes, name: str, mime: str = "", text: bool = False) -> dict:
    """Add an item. `text` items land in the device's clipboard, the rest in its files."""
    if len(data) > MAX_BYTES:
        raise TooBig(f"{name}: larger than {MAX_BYTES // (1024 * 1024)} MB")
    ident = f"{int(time.time())}-{secrets.token_hex(4)}"
    folder = outbox() / ident
    folder.mkdir(parents=True, mode=0o700)
    meta = {
        "id": ident,
        "name": Path(name).name or "enlace",
        "mime": mime or mimetypes.guess_type(name)[0] or "application/octet-stream",
        "size": len(data),
        "text": text,
    }
    (folder / "data").write_bytes(data)
    (folder / "meta.json").write_text(json.dumps(meta))  # last: an item without it is not listed
    return meta


def items() -> list[dict]:
    """Waiting items, oldest first. Expired and half-written ones are removed."""
    found = []
    if not outbox().is_dir():
        return found
    for folder in sorted(outbox().iterdir()):
        try:
            meta = json.loads((folder / "meta.json").read_text())
            fresh = time.time() - folder.stat().st_mtime < KEEP_SECONDS
        except (OSError, ValueError):
            meta, fresh = None, time.time() - folder.stat().st_mtime < 60
        if meta and fresh and meta.get("id") == folder.name:
            found.append(meta)
        elif not fresh:
            shutil.rmtree(folder, ignore_errors=True)
    return found


def _folder(ident: object) -> Path | None:
    """The item's folder, only for an id that is listed: never a path from the device."""
    return next((outbox() / m["id"] for m in items() if m["id"] == ident), None)


class Inbox(Module):
    name = "inbox"

    async def handle(self, session, header, body):
        kind, ident = header.get("type"), header.get("id")
        if kind == "list":
            await session.send({"module": self.name, "type": "list", "items": items()})
        elif kind == "get":
            folder = _folder(ident)
            if folder is None:
                await session.send({"type": "error", "code": "not_found", "module": self.name})
                return
            meta = json.loads((folder / "meta.json").read_text())
            data = await asyncio.to_thread((folder / "data").read_bytes)
            await session.send({"module": self.name, "type": "item", "id": meta["id"],
                                "name": meta["name"], "mime": meta["mime"], "text": meta["text"]}, data)
        elif kind == "done":
            folder = _folder(ident)
            if folder is not None:
                shutil.rmtree(folder, ignore_errors=True)
            await session.send({"module": self.name, "type": "removed", "id": ident})
        else:
            await session.send({"type": "error", "code": "bad_request", "module": self.name})


MODULE = Inbox
