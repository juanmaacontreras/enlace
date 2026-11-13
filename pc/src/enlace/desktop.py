"""The few things modules do to the desktop: notify, and run a program."""

from __future__ import annotations

import asyncio


async def run(
    *argv: str, stdin: bytes | None = None, timeout: float = 10, capture: bool = True
) -> tuple[int, str]:
    """Run to completion. Returns (exit code, stdout); -1 if it could not run.

    capture=False is for programs that fork and stay (wl-copy): a child that
    inherits a stdout pipe would keep it open and this would wait forever.
    """
    try:
        proc = await asyncio.create_subprocess_exec(
            *argv,
            stdin=asyncio.subprocess.PIPE if stdin is not None else asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE if capture else asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        out, _ = await asyncio.wait_for(proc.communicate(stdin), timeout)
        return proc.returncode or 0, (out or b"").decode(errors="replace")
    except (OSError, asyncio.TimeoutError):
        return -1, ""


async def notify(title: str, text: str, critical: bool = False) -> None:
    args = ["notify-send", "-a", "enlace"]
    if critical:
        args += ["-u", "critical"]
    await run(*args, title, text, timeout=3)
