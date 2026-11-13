"""The core end to end over real TLS on localhost, with a fake module."""

import asyncio
import hashlib
import json
import socket
import ssl

import pytest

from enlace import protocol
from enlace.modules import Module
from enlace.server import Server
from enlace.store import Store


class Echo(Module):
    name = "echo"

    def __init__(self):
        self.closed_for = []

    async def handle(self, session, header, body):
        if header.get("type") == "boom":
            raise RuntimeError("module bug")
        await session.send({"module": "echo", "type": "echo", "from": session.name}, body)

    async def closed(self, session):
        self.closed_for.append(session.device_id)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def env(tmp_path):
    store = Store(tmp_path)
    echo = Echo()
    server = Server(store, port=free_port(), modules={"echo": echo})
    return store, server, echo


async def connect(server):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return await asyncio.open_connection("127.0.0.1", server.port, ssl=ctx)


async def hello(server, **extra):
    reader, writer = await connect(server)
    msg = {"type": "hello", "v": 1, "device": {"id": "d1", "name": "Redmi"}, **extra}
    writer.write(protocol.encode(msg))
    reply, _ = await protocol.read_frame(reader)
    return reader, writer, reply


def run(coro):
    return asyncio.run(coro)


def test_pair_then_authenticate_and_route(env):
    store, server, echo = env

    async def scenario():
        await server.start()
        code = store.start_pairing()
        _, writer, reply = await hello(server, pair=code)
        assert reply["type"] == "welcome" and reply["modules"] == ["echo"]
        token = reply["token"]
        writer.close()

        reader, writer, reply = await hello(server, token=token)
        assert reply["type"] == "welcome" and "token" not in reply
        writer.write(protocol.encode({"module": "echo", "type": "x"}, b"abc"))
        header, body = await protocol.read_frame(reader)
        assert (header["from"], body) == ("Redmi", b"abc")

        writer.write(protocol.encode({"module": "nope", "type": "x"}))
        header, _ = await protocol.read_frame(reader)
        assert header == {"type": "error", "code": "unknown_module"}

        # A bug in a module answers with an error and keeps the link up.
        writer.write(protocol.encode({"module": "echo", "type": "boom"}))
        header, _ = await protocol.read_frame(reader)
        assert header == {"type": "error", "code": "failed", "module": "echo"}
        writer.write(protocol.encode({"module": "echo", "type": "x"}))
        header, _ = await protocol.read_frame(reader)
        assert header["type"] == "echo"

        writer.close()
        await asyncio.sleep(0.1)
        assert echo.closed_for.count("d1") == 2
        await server.stop()

    run(scenario())


@pytest.mark.parametrize("extra, code", [
    ({"token": "made-up"}, "unauthorized"),
    ({}, "unauthorized"),
    ({"pair": "made-up"}, "bad_pair_code"),
    ({"token": "x", "v": 99}, "unsupported_version"),
])
def test_strangers_are_refused(env, extra, code):
    _, server, echo = env

    async def scenario():
        await server.start()
        reader, writer, reply = await hello(server, **extra)
        assert reply == {"type": "error", "code": code}
        assert await reader.read() == b""  # and the PC hangs up
        assert echo.closed_for == []
        await server.stop()

    run(scenario())


def test_modules_offered_are_the_shared_ones(env):
    store, server, _ = env

    async def scenario():
        await server.start()
        _, _, reply = await hello(server, pair=store.start_pairing(), modules=["mic"])
        assert reply["modules"] == []
        await server.stop()

    run(scenario())


def test_certificate_matches_the_fingerprint_in_the_qr(env):
    store, server, _ = env

    async def scenario():
        await server.start()
        _, writer = await connect(server)
        der = writer.get_extra_info("ssl_object").getpeercert(binary_form=True)
        assert hashlib.sha256(der).hexdigest() == store.fingerprint()
        writer.close()
        await server.stop()

    run(scenario())


def test_discovery_answers_only_its_own_id(env):
    _, server, _ = env

    async def scenario():
        await server.start()
        loop = asyncio.get_running_loop()
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setblocking(False)
        target = ("127.0.0.1", server.port)

        await loop.sock_sendto(sock, json.dumps({"enlace": 1, "find": "someone-else"}).encode(), target)
        with pytest.raises(asyncio.TimeoutError):
            await asyncio.wait_for(loop.sock_recv(sock, 1024), 0.3)

        ask = {"enlace": 1, "find": server.identity["id"]}
        await loop.sock_sendto(sock, json.dumps(ask).encode(), target)
        reply = json.loads(await asyncio.wait_for(loop.sock_recv(sock, 1024), 1))
        assert reply == protocol.discovery_reply(server.identity["id"], server.port)
        sock.close()
        await server.stop()

    run(scenario())
