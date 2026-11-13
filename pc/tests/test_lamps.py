import pytest

from enlace import lamps
from enlace.lamps import BRIGHT, COLOUR, MODE, SWITCH, TEMP, Lamp, LampError

WHITE = {SWITCH: True, MODE: "white", BRIGHT: 1000, TEMP: 400, COLOUR: "000003e003e8"}
BLUE = {SWITCH: True, MODE: "colour", BRIGHT: 1000, TEMP: 400, COLOUR: "00f003e80190"}
OFF = {**WHITE, SWITCH: False}


def test_describe_reads_brightness_from_the_mode_in_use():
    assert lamps.describe(WHITE) == {
        "on": True, "mode": "white", "brightness": 100, "temp": 40, "tint": "#ebd2ae"}
    assert lamps.describe(BLUE) == {
        "on": True, "mode": "colour", "brightness": 40, "temp": 40, "hue": 240, "tint": "#4040ff"}


def test_toggle_and_switch_only_write_the_switch():
    assert lamps.changes(WHITE, toggle=True) == {SWITCH: False}
    assert lamps.changes(OFF, toggle=True) == {SWITCH: True}
    assert lamps.changes(WHITE, on=True) == {}
    assert lamps.changes(WHITE, on=False) == {SWITCH: False}


def test_brightness_is_absolute_or_relative_and_never_zero():
    assert lamps.changes(WHITE, brightness="30") == {BRIGHT: 300}
    assert lamps.changes(WHITE, brightness="-25") == {BRIGHT: 750}
    assert lamps.changes(WHITE, brightness="+50") == {BRIGHT: 1000}
    assert lamps.changes(WHITE, brightness="-200") == {BRIGHT: 10}


def test_setting_a_value_turns_the_lamp_on():
    assert lamps.changes(OFF, brightness="50") == {BRIGHT: 500, SWITCH: True}


def test_brightness_in_colour_mode_keeps_the_colour():
    assert lamps.changes(BLUE, brightness="100") == {COLOUR: "00f003e803e8"}


def test_temperature_goes_back_to_white():
    assert lamps.changes(BLUE, temp="warm") == {MODE: "white", TEMP: 0}
    assert lamps.changes(WHITE, temp="cold", brightness="20") == {MODE: "white", TEMP: 1000, BRIGHT: 200}


def test_colour_by_name_and_hex():
    assert lamps.changes(WHITE, colour="blue") == {MODE: "colour", COLOUR: "00f003e803e8"}
    assert lamps.colour_value("#ff0000", 50) == "000003e801f4"
    assert lamps.colour_value("#ffffff")[4:8] == "0000"
    with pytest.raises(LampError):
        lamps.colour_value("mauve-ish")


def test_bad_percentage_is_an_error_not_a_crash():
    with pytest.raises(LampError):
        lamps.changes(WHITE, brightness="mucho")


def test_file_round_trip_is_private_and_keeps_the_default(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert lamps.read() == []
    lamps.write([Lamp("Luz", "a1", "k1"), Lamp('Pieza "grande"', "b2", "k2", default=True)])
    assert lamps.path().stat().st_mode & 0o777 == 0o600
    back = lamps.read()
    assert [lamp.name for lamp in back] == ["Luz", 'Pieza "grande"']
    assert lamps.pick(back, None).id == "b2"
    relinked = lamps.merge(back, [Lamp("Pieza", "b2", "new"), Lamp("Luz", "a1", "new")])
    assert [lamp.default for lamp in relinked] == [True, False]


def test_pick_by_part_of_the_name():
    known = [Lamp("Luz", "a", "k"), Lamp("Lámpara de la habitación", "b", "k")]
    assert lamps.pick(known, "habit").id == "b"
    assert lamps.pick(known, "luz").id == "a"
    assert lamps.pick(known, None).id == "a"
    with pytest.raises(LampError):
        lamps.pick(known, "l")
    with pytest.raises(LampError):
        lamps.pick(known, "cocina")
    with pytest.raises(LampError):
        lamps.pick([], None)


def test_moved_bulb_is_found_again(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path))
    lamp = Lamp("Luz", "a1", "k1")
    lamps._remember("a1", "10.0.0.5", 3.5)

    class Fake:
        def __init__(self, ip): self.ip = ip
        def status(self): return {"dps": WHITE} if self.ip == "10.0.0.9" else {"Error": "unreachable"}
        def close(self): pass

    monkeypatch.setattr(lamps, "_open", lambda lamp, ip, version: Fake(ip))
    monkeypatch.setattr(lamps, "_find", lambda lamp: ("10.0.0.9", 3.5))
    assert lamps.state(lamp)["on"] is True
    assert lamps._cache()["a1"]["ip"] == "10.0.0.9"


def test_quick_does_not_search_again_until_five_minutes_pass(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path))
    lamp = Lamp("Luz", "a1", "k1")
    lamps._remember("a1", "10.0.0.5", 3.5)
    searches = []

    class Dead:
        def status(self): return {"Error": "unreachable"}
        def close(self): pass

    def find(lamp):
        searches.append(1)
        raise LampError("not on the network")

    monkeypatch.setattr(lamps, "_open", lambda lamp, ip, version: Dead())
    monkeypatch.setattr(lamps, "_find", find)
    for _ in range(3):
        with pytest.raises(LampError):
            lamps.state(lamp, quick=True)
    assert len(searches) == 1
    assert lamps._cache()["a1"]["ip"] == "10.0.0.5"
    with pytest.raises(LampError):
        lamps.state(lamp)  # a person asking always searches
    assert len(searches) == 2
