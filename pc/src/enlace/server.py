"""The core: accept paired devices over TLS and route frames to modules.

It knows nothing about what a module does. A connection is: hello (auth or
pairing) -> welcome -> any number of frames, each addressed to a module.
"""

from __future__ import annotations

import asyncio
import json
import logging
import ssl

from . import modules as module_registry
from . import protocol
from .protocol import ProtocolError
from .store import Store

log = logging.getLogger("enlace")
HELLO_TIMEOUT = 10


class Session:
    """One connected device, as modules see it."""

    def __init__(self, device_id: str, name: str, writer: asyncio.StreamWriter):
        self.device_id = device_id
        self.name = name
        self._writer = writer
        self._lock = asyncio.Lock()

    async def send(self, header: dict, body: bytes = b"") -> None:
        async with self._lock:
            self._writer.write(protocol.encode(header, body))
            await self._writer.drain()


class Server:
    def __init__(self, store: Store | None = None, port: int = protocol.PORT,
                 modules: dict | None = None):
        self.store = store or Store()
        self.port = port
        self.modules = module_registry.load() if modules is None else modules
        self.identity = self.store.identity()

    # --- hello -------------------------------------------------------------

    def _admit(self, hello: dict) -> tuple[dict | None, str | None, str]:
        """Returns (device fields, token issued by pairing, error code)."""
        if hello.get("type") != "hello":
            return None, None, "bad_request"
        if hello.get("v") != protocol.VERSION:
            return None, None, "unsupported_version"
        device = hello.get("device")
        if not isinstance(device, dict):
            return None, None, "bad_request"
        device_id, name = device.get("id"), device.get("name")
        if not (isinstance(device_id, str) and device_id and isinstance(name, str)):
            return None, None, "bad_request"
        name = name[:60] or "device"

        if "pair" in hello:
            token = self.store.finish_pairing(hello["pair"], device_id, name)
            if token is None:
                return None, None, "bad_pair_code"
            return {"id": device_id, "name": name}, token, ""

        if self.store.authenticate(device_id, hello.get("token")) is None:
            return None, None, "unauthorized"
        return {"id": device_id, "name": name}, None, ""

    # --- one connection ----------------------------------------------------

    async def _connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        session = None
        try:
            # Nothing is trusted before the hello, so it may not carry a body.
            hello, _ = await asyncio.wait_for(
                protocol.read_frame(reader, max_body=0), HELLO_TIMEOUT
            )
            device, token, code = self._admit(hello)
            if device is None:
                writer.write(protocol.encode(protocol.error(code)))
                await writer.drain()
                return

            session = Session(device["id"], device["name"], writer)
            theirs = hello.get("modules")
            shared = sorted(
                name for name in self.modules
                if not isinstance(theirs, list) or name in theirs
            )
            await session.send(
                protocol.welcome(self.identity["id"], self.identity["name"], shared, token)
            )
            if token:
                log.info("paired %s (%s)", device["name"], device["id"])

            while True:
                header, body = await protocol.read_frame(reader)
                module = self.modules.get(header.get("module"))
                if module is None:
                    await session.send(protocol.error("unknown_module"))
                    continue
                try:
                    await module.handle(session, header, body)
                except ProtocolError:
                    raise
                except Exception:
                    log.exception("module %s failed", module.name)
                    await session.send(protocol.error("failed", module.name))
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError, ssl.SSLError):
            pass
        except ProtocolError as exc:
            log.warning("protocol error: %s", exc)
        finally:
            if session is not None:
                for module in self.modules.values():
                    try:
                        await module.closed(session)
                    except Exception:
                        log.exception("module %s failed closing", module.name)
            writer.close()

    # --- discovery ---------------------------------------------------------

    def _discovery(self):
        server = self

        class Discovery(asyncio.DatagramProtocol):
            def connection_made(self, transport):
                self.transport = transport

            def datagram_received(self, data, addr):
                # Answers only a device that already knows this PC's id, so
                # the PC does not announce itself to the whole network.
                try:
                    ask = json.loads(data.decode())
                except (UnicodeDecodeError, json.JSONDecodeError):
                    return
                if not isinstance(ask, dict) or ask.get("find") != server.identity["id"]:
                    return
                reply = protocol.discovery_reply(server.identity["id"], server.port)
                self.transport.sendto(json.dumps(reply).encode(), addr)

        return Discovery

    # --- run ---------------------------------------------------------------

    async def start(self):
        self.tcp = await asyncio.start_server(
            self._connection, "0.0.0.0", self.port, ssl=self.store.tls_context()
        )
        loop = asyncio.get_running_loop()
        self.udp, _ = await loop.create_datagram_endpoint(
            self._discovery(), local_addr=("0.0.0.0", self.port)
        )
        log.info("listening on %d, modules: %s", self.port, ", ".join(sorted(self.modules)))

    async def stop(self):
        self.tcp.close()
        self.tcp.close_clients()  # wait_closed would otherwise wait for them
        self.udp.close()
        await self.tcp.wait_closed()

    async def serve_forever(self):
        await self.start()
        async with self.tcp:
            await self.tcp.serve_forever()
