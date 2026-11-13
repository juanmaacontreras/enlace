"""The PC's half of the contract: contract/fixtures.json, also run by the app."""

import json
from pathlib import Path

import pytest

from enlace import cli, protocol
from enlace.store import Store

FIXTURES = json.loads((Path(__file__).parents[2] / "contract" / "fixtures.json").read_text())


def test_constants():
    assert protocol.VERSION == FIXTURES["version"]
    assert protocol.PORT == FIXTURES["port"]


@pytest.mark.parametrize("frame", FIXTURES["frames"], ids=lambda f: f["name"])
def test_frames_decode_and_round_trip(frame):
    header, body = protocol.decode(bytes.fromhex(frame["hex"]))
    assert header == frame["header"]
    assert body.hex() == frame["body_hex"]
    assert protocol.decode(protocol.encode(header, body)) == (header, body)


@pytest.mark.parametrize("frame", FIXTURES["bad_frames"], ids=lambda f: f["name"])
def test_bad_frames_are_refused(frame):
    with pytest.raises(protocol.ProtocolError):
        protocol.decode(bytes.fromhex(frame["hex"]))


def test_messages_the_pc_builds():
    sends = FIXTURES["pc_sends"]
    modules = ["actions", "mic", "share"]
    assert protocol.welcome("pc1", "arch", modules) == sends["welcome"]
    assert protocol.welcome("pc1", "arch", modules, "TOKEN") == sends["welcome_paired"]
    assert protocol.error("unauthorized") == sends["error"]
    assert protocol.error("bad_request", "mic") == sends["error_module"]
    assert protocol.discovery_reply("pc1", 47800) == sends["discovery_reply"]


def test_pair_uri(tmp_path):
    store = Store(tmp_path)
    store._write("identity.json", {"id": "pc1", "name": "arch"})
    store.fingerprint = lambda: "ab12"
    expected = FIXTURES["pair_uri"]
    assert cli.pair_uri(store, "CODE", expected["host"]) == expected["uri"]
