"""Features. Each file in this package is one module of the link.

To add a feature: drop a file here that defines a `Module` subclass and
`MODULE = ThatClass`. Nothing else on the PC needs to change: `load()` finds
it, the server announces its name in `welcome`, and frames whose "module" is
that name are routed to it. See docs/modules.md.
"""

from __future__ import annotations

import importlib
import pkgutil


class Module:
    name = ""

    async def handle(self, session, header: dict, body: bytes) -> None:
        """One frame addressed to this module."""
        raise NotImplementedError

    async def closed(self, session) -> None:
        """The device's connection ended. Release whatever it was using."""


def load() -> dict[str, Module]:
    found: dict[str, Module] = {}
    for info in pkgutil.iter_modules(__path__):
        cls = getattr(importlib.import_module(f"{__name__}.{info.name}"), "MODULE", None)
        if cls is not None:
            instance = cls()
            found[instance.name] = instance
    return found
