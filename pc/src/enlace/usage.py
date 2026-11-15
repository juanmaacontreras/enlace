"""usage: a local trace of what the devices ask the PC to do.

One JSON line per use in ~/.local/state/enlace/usage.jsonl: when, which
device, which module, what kind of thing. It exists so a review of Enlace can
start from what is really used instead of guesses.

Does not: record content (no text, no file names, no audio), leave this PC,
or ever make a module fail: a trace that cannot be written is skipped.
"""

from __future__ import annotations

import json
import time
from collections import Counter

from . import store


def path():
    return store.state_dir() / "usage.jsonl"


def record(session, module: str, event: str, **fields) -> None:
    line = {"at": int(time.time()), "device": getattr(session, "name", ""), "module": module,
            "event": event, **fields}
    try:
        path().parent.mkdir(parents=True, exist_ok=True)
        with path().open("a") as out:
            out.write(json.dumps(line, ensure_ascii=False) + "\n")
    except OSError:
        pass


def read(days: int = 30, now: float | None = None) -> list[dict]:
    since = (now or time.time()) - days * 86400
    found = []
    try:
        for raw in path().read_text().splitlines():
            try:
                line = json.loads(raw)
            except ValueError:
                continue
            if isinstance(line, dict) and line.get("at", 0) >= since:
                found.append(line)
    except OSError:
        pass
    return found


def summary(lines: list[dict]) -> list[tuple[str, int]]:
    """Counts per "module event detail", most used first."""
    counts = Counter()
    for line in lines:
        detail = line.get("id") or line.get("kind") or ""
        counts[" ".join(x for x in (line.get("module", ""), line.get("event", ""), detail) if x)] += 1
    return sorted(counts.items(), key=lambda pair: (-pair[1], pair[0]))
