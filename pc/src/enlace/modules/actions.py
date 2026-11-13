"""actions: buttons on the device that run a command on the PC.

The list lives in ~/.config/enlace/actions.toml and is read on every request,
so adding a button is adding four lines there: no app update, no restart.
The device only ever sends an id; it cannot make the PC run anything that is
not written in that file.

Does not: return the command's output, or take arguments from the device.
"""

from __future__ import annotations

import asyncio
import tomllib

from .. import store
from . import Module

EXAMPLE = '''\
# Buttons the Enlace app shows. Edit freely: the app re-reads this list every
# time it opens. `icon` is one of: lock, play, next, previous, volume-up,
# volume-down, mute, power, screen, moon, bolt (anything else shows a dot).

[[action]]
id = "lock"
label = "Bloquear"
icon = "lock"
run = "hyprlock"

[[action]]
id = "play-pause"
label = "Play / Pausa"
icon = "play"
run = "playerctl play-pause"

[[action]]
id = "next"
label = "Siguiente"
icon = "next"
run = "playerctl next"

[[action]]
id = "volume-down"
label = "Volumen −"
icon = "volume-down"
run = "swayosd-client --output-volume lower"

[[action]]
id = "volume-up"
label = "Volumen +"
icon = "volume-up"
run = "swayosd-client --output-volume raise"

[[action]]
id = "mute"
label = "Silenciar"
icon = "mute"
run = "swayosd-client --output-volume mute-toggle"
'''


def path():
    return store.config_dir() / "actions.toml"


def read() -> list[dict]:
    """Valid entries only; a broken file yields no buttons rather than a crash."""
    try:
        data = tomllib.loads(path().read_text())
    except (FileNotFoundError, tomllib.TOMLDecodeError):
        return []
    actions, seen = [], set()
    for entry in data.get("action", []):
        ident, run = entry.get("id"), entry.get("run")
        if not (isinstance(ident, str) and ident and isinstance(run, str) and run):
            continue
        if ident in seen:
            continue
        seen.add(ident)
        actions.append({
            "id": ident,
            "label": str(entry.get("label") or ident),
            "icon": str(entry.get("icon") or ""),
            "run": run,
        })
    return actions


def write_example() -> bool:
    if path().exists():
        return False
    path().parent.mkdir(parents=True, exist_ok=True)
    path().write_text(EXAMPLE)
    return True


class Actions(Module):
    name = "actions"

    async def handle(self, session, header, body):
        kind = header.get("type")
        if kind == "list":
            public = [{k: a[k] for k in ("id", "label", "icon")} for a in read()]
            await session.send({"module": self.name, "type": "list", "actions": public})
        elif kind == "run":
            ident = header.get("id")
            action = next((a for a in read() if a["id"] == ident), None)
            ok = action is not None and await self._run(action["run"])
            await session.send({"module": self.name, "type": "ran", "id": ident, "ok": ok})
        else:
            await session.send({"type": "error", "code": "bad_request", "module": self.name})

    async def _run(self, command: str) -> bool:
        try:
            proc = await asyncio.create_subprocess_shell(
                command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
                start_new_session=True,
            )
        except OSError:
            return False
        try:  # a command that is still running after a moment counts as started
            return await asyncio.wait_for(proc.wait(), 3) == 0
        except asyncio.TimeoutError:
            return True


MODULE = Actions
