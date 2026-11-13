"""lamps: Tuya Wi-Fi bulbs switched and dimmed over the home network.

The list lives in ~/.config/enlace/lamps.toml (name, id and the bulb's secret
key; mode 0600, never in git). `enlace lamp link` fills it once from the Smart
Life account; after that every command goes straight to the bulb, no cloud.
The bulb's address is remembered in the state folder and looked up again when
the router hands it a new one.

Does not: pair a bulb to Wi-Fi (the Smart Life app does), or work when the PC
and the bulb are on different networks.
"""

from __future__ import annotations

import colorsys
import json
import os
import time
import tomllib
from dataclasses import dataclass

from . import store

# Data points of a Tuya "dj" light (the *_v2 set).
SWITCH, MODE, BRIGHT, TEMP, COLOUR = "20", "21", "22", "23", "24"
BRIGHT_MIN, SCALE = 10, 1000

COLOURS = {
    "red": 0, "orange": 30, "yellow": 55, "green": 120, "cyan": 180,
    "blue": 240, "purple": 275, "pink": 320,
}
TEMPS = {"warm": 0, "neutral": 50, "cold": 100}


class LampError(Exception):
    pass


@dataclass
class Lamp:
    name: str
    id: str
    key: str
    default: bool = False


def path():
    return store.config_dir() / "lamps.toml"


def read() -> list[Lamp]:
    try:
        data = tomllib.loads(path().read_text())
    except (FileNotFoundError, tomllib.TOMLDecodeError):
        return []
    lamps = []
    for entry in data.get("lamp", []):
        if all(isinstance(entry.get(k), str) and entry[k] for k in ("name", "id", "key")):
            lamps.append(Lamp(entry["name"], entry["id"], entry["key"], bool(entry.get("default"))))
    return lamps


def write(lamps: list[Lamp]) -> None:
    lines = [
        "# Lamps Enlace controls over the home Wi-Fi. Written by `enlace lamp link`.",
        "# The key is a secret: this file stays on this PC. `default = true` marks the",
        "# lamp that commands without --lamp act on.",
    ]
    for lamp in lamps:
        lines += ["", "[[lamp]]", f"name = {json.dumps(lamp.name, ensure_ascii=False)}",
                  f"id = {json.dumps(lamp.id)}", f"key = {json.dumps(lamp.key)}"]
        if lamp.default:
            lines.append("default = true")
    path().parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path(), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write("\n".join(lines) + "\n")
    os.chmod(path(), 0o600)


def pick(lamps: list[Lamp], name: str | None) -> Lamp:
    if not lamps:
        raise LampError("No lamps yet. Run: enlace lamp link <Smart Life user code>")
    if name is None:
        return next((lamp for lamp in lamps if lamp.default), lamps[0])
    wanted = name.casefold()
    matches = [lamp for lamp in lamps if wanted in lamp.name.casefold()]
    exact = [lamp for lamp in matches if lamp.name.casefold() == wanted]
    if len(exact) == 1 or len(matches) == 1:
        return (exact or matches)[0]
    raise LampError(f"No lamp called {name!r}." if not matches else f"{name!r} matches several lamps.")


# ---- values -----------------------------------------------------------------

def level(text: str, current: int, low: int = 0) -> int:
    """'70' is absolute, '+10' / '-10' move from the current percentage."""
    try:
        value = current + int(text) if text[:1] in "+-" else int(text)
    except ValueError:
        raise LampError(f"Not a percentage: {text!r}") from None
    return max(low, min(100, value))


def bright_value(percent: int) -> int:
    return max(BRIGHT_MIN, round(percent * SCALE / 100))


def temp_percent(text: str, current: int) -> int:
    return TEMPS[text] if text in TEMPS else level(text, current)


def colour_value(text: str, brightness: int = 100) -> str:
    """A colour name or #rrggbb as the bulb's hue/saturation/value hex string."""
    name = text.casefold()
    if name in COLOURS:
        hue, sat = COLOURS[name], 1.0
    else:
        raw = name.lstrip("#")
        if len(raw) != 6 or any(c not in "0123456789abcdef" for c in raw):
            raise LampError(f"Unknown colour {text!r}. Use #rrggbb or: {', '.join(COLOURS)}")
        r, g, b = (int(raw[i:i + 2], 16) / 255 for i in (0, 2, 4))
        h, sat, _ = colorsys.rgb_to_hsv(r, g, b)
        hue = round(h * 360)
    return f"{hue:04x}{round(sat * SCALE):04x}{bright_value(brightness):04x}"


def describe(dps: dict) -> dict:
    """The bulb's raw data points as what a person would say."""
    colour = str(dps.get(COLOUR) or "")
    in_colour = dps.get(MODE) == "colour" and len(colour) == 12
    raw = int(colour[8:12], 16) if in_colour else int(dps.get(BRIGHT) or 0)
    state = {
        "on": bool(dps.get(SWITCH)),
        "mode": "colour" if in_colour else "white",
        "brightness": round(raw * 100 / SCALE),
        "temp": round(int(dps.get(TEMP) or 0) * 100 / SCALE),
    }
    if in_colour:
        state["hue"] = int(colour[0:4], 16)
        sat = int(colour[4:8], 16) / SCALE
        rgb = colorsys.hsv_to_rgb(state["hue"] / 360, sat * 0.75, 1)
    else:  # warm amber to cold blue-white
        mix = state["temp"] / 100
        rgb = tuple((a + (b - a) * mix) / 255 for a, b in zip((255, 200, 120), (205, 225, 255)))
    state["tint"] = "#" + "".join(f"{round(c * 255):02x}" for c in rgb)
    return state


def changes(dps: dict, *, on: bool | None = None, toggle: bool = False,
            brightness: str | None = None, temp: str | None = None,
            colour: str | None = None) -> dict:
    """The data points to write so the bulb ends up as asked. Setting a
    brightness, temperature or colour also turns it on."""
    now = describe(dps)
    out: dict = {}
    if toggle:
        on = not now["on"]
    percent = level(brightness, now["brightness"], low=1) if brightness is not None else None
    if colour is not None:
        out[MODE] = "colour"
        out[COLOUR] = colour_value(colour, percent or now["brightness"] or 100)
    elif temp is not None:
        out[MODE] = "white"
        out[TEMP] = round(temp_percent(temp, now["temp"]) * SCALE / 100)
        if percent is not None:
            out[BRIGHT] = bright_value(percent)
    elif percent is not None:
        if now["mode"] == "colour":
            out[COLOUR] = str(dps[COLOUR])[:8] + f"{bright_value(percent):04x}"
        else:
            out[BRIGHT] = bright_value(percent)
    if on is None and out:
        on = True
    if on is not None and (on != now["on"] or toggle):
        out[SWITCH] = on
    return out


# ---- the bulb ---------------------------------------------------------------

def _cache_path():
    return store.state_dir() / "lamps.json"


def _cache() -> dict:
    try:
        return json.loads(_cache_path().read_text())
    except (FileNotFoundError, ValueError):
        return {}


def _remember(lamp_id: str, ip: str, version: float, searched: float = 0) -> None:
    cache = _cache()
    cache[lamp_id] = {"ip": ip, "version": version, "searched": searched}
    _cache_path().parent.mkdir(parents=True, exist_ok=True)
    _cache_path().write_text(json.dumps(cache))


def _open(lamp: Lamp, ip: str, version: float):
    import tinytuya

    device = tinytuya.Device(
        lamp.id, ip, lamp.key, version=version,
        connection_timeout=3, connection_retry_limit=1, connection_retry_delay=1,
    )
    device.set_socketPersistent(True)
    return device


def _find(lamp: Lamp) -> tuple[str, float]:
    import tinytuya

    found = tinytuya.find_device(dev_id=lamp.id)
    if not found or not found.get("ip"):
        raise LampError(f"{lamp.name}: not on the network (off at the wall?)")
    return found["ip"], float(found.get("version") or 3.3)


SEARCH_EVERY = 300  # seconds between network searches when `quick`


def connect(lamp: Lamp, quick: bool = False):
    """An open device and its current data points. Tries the remembered
    address first; if the bulb is not there, looks for it on the network.
    `quick` is for callers that poll (the bar): the search takes up to 18 s,
    so they get at most one every five minutes and an error in between."""
    known = _cache().get(lamp.id)
    if known and known.get("ip"):
        device = _open(lamp, known["ip"], known["version"])
        dps = (device.status() or {}).get("dps")
        if dps:
            return device, dps
        device.close()
    if quick:
        if known and time.time() - known.get("searched", 0) < SEARCH_EVERY:
            raise LampError(f"{lamp.name}: not reachable")
        _remember(lamp.id, (known or {}).get("ip", ""), (known or {}).get("version", 3.3),
                  searched=time.time())
    ip, version = _find(lamp)
    device = _open(lamp, ip, version)
    answer = device.status() or {}
    if not answer.get("dps"):
        device.close()
        raise LampError(f"{lamp.name}: {answer.get('Error') or 'no answer'}")
    _remember(lamp.id, ip, version)
    return device, answer["dps"]


def state(lamp: Lamp, quick: bool = False) -> dict:
    device, dps = connect(lamp, quick)
    device.close()
    return describe(dps)


def apply(lamp: Lamp, **wanted) -> dict:
    """Change the lamp and return its new state."""
    device, dps = connect(lamp)
    try:
        out = changes(dps, **wanted)
        if out:
            answer = device.set_multiple_values(out) or {}
            if answer.get("Error"):
                raise LampError(f"{lamp.name}: {answer['Error']}")
            dps = {**dps, **out}
    finally:
        device.close()
    return describe(dps)


# ---- linking ----------------------------------------------------------------

CLIENT_ID = "HA_3y9q4ak7g4ephrvke"  # the public id of Tuya's log-in-by-QR flow
SCHEMA = "haauthorize"


def link_start(user_code: str) -> tuple[object, str, str]:
    """Ask Tuya for a login QR. Returns (session, token, text to draw as QR)."""
    from tuya_sharing import LoginControl

    login = LoginControl()
    answer = login.qr_code(CLIENT_ID, SCHEMA, user_code)
    if not answer.get("success"):
        raise LampError(f"Tuya refused the user code: {answer.get('msg') or answer}")
    token = answer["result"]["qrcode"]
    return login, token, f"tuyaSmart--qrLogin?token={token}"


def link_finish(login, token: str, user_code: str) -> list[Lamp] | None:
    """None while the QR has not been scanned; then the account's lights."""
    from tuya_sharing import Manager

    ok, info = login.login_result(token, CLIENT_ID, user_code)
    if not ok:
        return None
    tokens = {k: info[k] for k in ("t", "uid", "expire_time", "access_token", "refresh_token")}
    manager = Manager(CLIENT_ID, user_code, info["terminal_id"], info["endpoint"], tokens)
    manager.update_device_cache()
    return [
        Lamp(device.name.strip(), device.id, device.local_key)
        for device in manager.device_map.values()
        if device.category == "dj" and device.local_key
    ]


def merge(old: list[Lamp], new: list[Lamp]) -> list[Lamp]:
    """Fresh names and keys, keeping which lamp was the default."""
    default = next((lamp.id for lamp in old if lamp.default), None)
    for lamp in new:
        lamp.default = lamp.id == default
    return new
