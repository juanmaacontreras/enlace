"""mic: the device's microphone becomes a capture device on the PC.

The device sends raw PCM frames. They are piped into `pw-cat` running as an
`Audio/Source` node, which PipeWire shows as an ordinary microphone (a real
source, not a monitor: Chromium hides monitors). The node exists exactly as
long as the process, so there is nothing to unload afterwards.

Does not: process the audio (no gain, no noise suppression; whatever records
does its own), or keep anything on disk.
"""

from __future__ import annotations

import asyncio
import fcntl

from .. import desktop
from . import Module

NODE = "enlace-mic"
RATES = (48000, 44100, 16000)
# Audio that cannot be written right away is dropped instead of queued: a
# queue would turn one network hiccup into permanent delay.
MAX_BACKLOG_SECONDS = 0.1
F_SETPIPE_SZ = 1031


class Mic(Module):
    name = "mic"

    def __init__(self):
        self.proc: asyncio.subprocess.Process | None = None
        self.owner = None
        self.backlog_limit = 0
        self.previous_default = ""

    async def handle(self, session, header, body):
        kind = header.get("type")
        if kind == "start":
            await self._start(session, header)
        elif kind == "audio":
            self._audio(session, body)
        elif kind == "stop":
            if session is self.owner:
                await self._stop()
            await session.send({"module": self.name, "type": "stopped"})
        else:
            await session.send({"type": "error", "code": "bad_request", "module": self.name})

    async def closed(self, session):
        if session is self.owner:
            await self._stop()

    async def _start(self, session, header):
        rate, channels = header.get("rate"), header.get("channels")
        if header.get("format") != "s16le" or rate not in RATES or channels not in (1, 2):
            await session.send({"type": "error", "code": "unsupported_format", "module": self.name})
            return
        if self.proc is not None:  # one microphone at a time; the newest wins
            await self._stop()

        description = f"{session.name} (Enlace)".replace('"', "'")
        self.proc = await asyncio.create_subprocess_exec(
            "pw-cat", "--playback", "--raw", "--format", "s16",
            "--rate", str(rate), "--channels", str(channels),
            "--target", "0", "--latency", "40ms",
            "-P", f'{{ media.class = "Audio/Source" node.name = "{NODE}" '
                  f'node.description = "{description}" }}',
            "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        self.owner = session
        self.backlog_limit = int(rate * channels * 2 * MAX_BACKLOG_SECONDS)
        try:  # the smallest pipe the kernel gives, for the same reason
            fd = self.proc.stdin.transport.get_extra_info("pipe").fileno()
            fcntl.fcntl(fd, F_SETPIPE_SZ, 4096)
        except (OSError, AttributeError):
            pass

        if await self._wait_for_source():
            # device-restore remembers a volume per device name; set it rather
            # than trust that a new node starts at 100%.
            await desktop.run("pactl", "set-source-volume", NODE, "100%")
            _, default = await desktop.run("pactl", "get-default-source")
            self.previous_default = default.strip()
            await self._use(NODE)
            await session.send({"module": self.name, "type": "started"})
            await desktop.notify("Microphone", f"{session.name} connected")
        else:
            await self._stop(quiet=True)
            await session.send({"type": "error", "code": "no_audio_server", "module": self.name})

    async def _wait_for_source(self) -> bool:
        for _ in range(25):
            _, out = await desktop.run("pactl", "list", "short", "sources")
            if any(line.split("\t")[1:2] == [NODE] for line in out.splitlines()):
                return True
            if self.proc is None or self.proc.returncode is not None:
                return False
            await asyncio.sleep(0.2)
        return False

    async def _use(self, source: str):
        """Make it the default and move what is already recording, otherwise
        only apps started afterwards would follow."""
        await desktop.run("pactl", "set-default-source", source)
        _, out = await desktop.run("pactl", "list", "short", "source-outputs")
        for line in out.splitlines():
            await desktop.run("pactl", "move-source-output", line.split("\t")[0], source)

    def _audio(self, session, body):
        if session is not self.owner or self.proc is None or not body:
            return
        stdin = self.proc.stdin
        if stdin.is_closing() or stdin.transport.get_write_buffer_size() > self.backlog_limit:
            return
        stdin.write(body)

    async def _stop(self, quiet: bool = False):
        proc, owner = self.proc, self.owner
        self.proc = self.owner = None
        if proc is None:
            return
        if proc.returncode is None:
            proc.terminate()
            try:
                await asyncio.wait_for(proc.wait(), 2)
            except asyncio.TimeoutError:
                proc.kill()
        if self.previous_default and self.previous_default != NODE:
            _, out = await desktop.run("pactl", "list", "short", "sources")
            if any(l.split("\t")[1:2] == [self.previous_default] for l in out.splitlines()):
                await self._use(self.previous_default)
        self.previous_default = ""
        if not quiet and owner is not None:
            await desktop.notify("Microphone", f"{owner.name} disconnected")


MODULE = Mic
