"""`enlace serve | pair | devices | unpair | lamp | send`."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import socket
import sys
import time
from urllib.parse import urlencode

from . import lamps, protocol
from .modules import actions, inbox
from .server import Server
from .store import PAIR_SECONDS, Store


def lan_address() -> str:
    """The address other machines on the network reach this one at."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(("192.0.2.1", 9))  # no packet is sent for UDP
            return probe.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def pair_uri(store: Store, code: str, host: str, port: int = protocol.PORT) -> str:
    ident = store.identity()
    query = urlencode({
        "h": host, "p": port, "id": ident["id"], "n": ident["name"],
        "fp": store.fingerprint(), "c": code,
    })
    return "enlace://pair?" + query


def cmd_serve(args) -> int:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    actions.write_example()
    try:
        asyncio.run(Server(port=args.port).serve_forever())
    except KeyboardInterrupt:
        pass
    return 0


def cmd_pair(args) -> int:
    import segno

    store = Store()
    store.ensure_certificate()
    before = set(store.devices())
    uri = pair_uri(store, store.start_pairing(), args.host or lan_address(), args.port)
    if args.uri_only:  # for scripts and tests: no QR, no waiting
        print(uri)
        return 0
    segno.make(uri, error="m").terminal(compact=True, border=2)
    print(f"\n  Scan it from the Enlace app. Valid for {PAIR_SECONDS // 60} minutes.\n")

    deadline = time.time() + PAIR_SECONDS
    while time.time() < deadline:
        time.sleep(0.5)
        devices = store.devices()
        new = set(devices) - before
        if new or not store.pairing_pending():
            name = devices[new.pop()]["name"] if new else None
            print(f"  Paired: {name}" if name else "  Paired.")
            time.sleep(1.5)
            return 0
    print("  Nothing was paired. Run it again for a new code.")
    time.sleep(3)  # long enough to read when this is its own window
    return 1


def cmd_devices(args) -> int:
    devices = Store().devices()
    if not devices:
        print("No paired devices.")
    for device_id, device in devices.items():
        when = time.strftime("%Y-%m-%d", time.localtime(device["paired"]))
        print(f"{device['name']:<24} {device_id}  paired {when}")
    return 0


def cmd_unpair(args) -> int:
    store = Store()
    matches = [
        device_id for device_id, device in store.devices().items()
        if args.device in (device_id, device["name"])
    ]
    if len(matches) != 1:
        print("No device with that name or id." if not matches else "Ambiguous: use the id.")
        return 1
    store.unpair(matches[0])
    print("Unpaired.")
    return 0


BULB_ON, BULB_OFF, BULB_GONE = "\U000f0335", "\U000f0336", "\U000f0e4f"


def _waybar(lamp, state) -> str:
    """One line of Waybar JSON: the bulb icon, a CSS class and a tooltip."""
    if state is None:
        return json.dumps({"text": BULB_GONE, "class": "gone", "tooltip": f"{lamp.name}: not reachable"})
    if not state["on"]:
        return json.dumps({"text": BULB_OFF, "class": "off", "tooltip": f"{lamp.name}: off"})
    return json.dumps({
        "text": f"{BULB_ON} {state['brightness']}%", "class": state["mode"],
        "tooltip": f"{lamp.name}: {state['brightness']}%\nclick: panel · right: on/off · scroll: brightness",
    })


def _show(lamp, state, args=None) -> None:
    if args is not None and args.waybar:
        print(_waybar(lamp, state))
        return
    if args is not None and args.json:
        print(json.dumps({"name": lamp.name, "reachable": state is not None, **(state or {})}))
        return
    if state is None:
        print(f"{lamp.name}: not reachable")
        return
    if not state["on"]:
        print(f"{lamp.name}: off")
        return
    look = f"colour (hue {state['hue']})" if state["mode"] == "colour" else f"white, temp {state['temp']}%"
    print(f"{lamp.name}: on, {state['brightness']}%, {look}")


def cmd_lamp(args) -> int:
    try:
        if args.verb == "link":
            return _lamp_link(args.value)
        known = lamps.read()
        if args.verb == "status":
            targets = [lamps.pick(known, args.lamp)] if args.lamp else known
            if not targets:
                lamps.pick(known, None)
            if args.json or args.waybar:  # one object, for the bar and the panel
                targets = targets[:1] if args.lamp else [lamps.pick(known, None)]
            failed = 0
            for lamp in targets:
                try:
                    _show(lamp, lamps.state(lamp, quick=args.quick), args)
                except lamps.LampError as error:
                    if args.json or args.waybar:
                        _show(lamp, None, args)
                    else:
                        print(error)
                    failed += 1
            return 1 if failed == len(targets) else 0
        if args.verb == "default":
            chosen = lamps.pick(known, args.value)
            for lamp in known:
                lamp.default = lamp is chosen
            lamps.write(known)
            print(f"Default lamp: {chosen.name}")
            return 0
        wanted = {"brightness": args.brightness, "temp": args.temp, "colour": args.color}
        if args.verb in ("on", "off"):
            wanted["on"] = args.verb == "on"
        elif args.verb == "toggle":
            wanted["toggle"] = True
        elif args.verb in ("brightness", "temp", "color"):
            if args.value is None:
                print(f"enlace lamp {args.verb} needs a value")
                return 2
            wanted["colour" if args.verb == "color" else args.verb] = args.value
        lamp = lamps.pick(known, args.lamp)
        _show(lamp, lamps.apply(lamp, **wanted), args)
        return 0
    except lamps.LampError as error:
        print(error)
        return 1


def _lamp_link(user_code: str | None) -> int:
    import segno

    if not user_code:
        print("Give the user code: Smart Life > Yo > settings > Cuenta y seguridad > Código de usuario")
        return 2
    login, token, text = lamps.link_start(user_code)
    segno.make(text, error="m").terminal(compact=True, border=2)
    print("\n  Scan it with the Smart Life app's scanner and confirm. Valid for 3 minutes.\n")
    deadline = time.time() + 180
    while time.time() < deadline:
        found = lamps.link_finish(login, token, user_code)
        if found is not None:
            lamps.write(lamps.merge(lamps.read(), found))
            print(f"  Linked {len(found)} lamp(s): {', '.join(lamp.name for lamp in found) or 'none'}")
            return 0 if found else 1
        time.sleep(2)
    print("  Not scanned. Run it again for a new code.")
    return 1


def _clipboard() -> tuple[bytes, str, str, bool] | None:
    """What the Wayland clipboard holds, as (data, name, mime, is text)."""
    import subprocess

    def paste(*args: str) -> bytes:
        try:
            return subprocess.run(["wl-paste", *args], capture_output=True, timeout=10).stdout
        except (OSError, subprocess.TimeoutExpired):
            return b""

    types = paste("--list-types").decode(errors="replace").split()
    image = next((t for t in ("image/png", "image/jpeg") if t in types), None)
    if image:
        data = paste("--type", image)
        ext = "png" if image == "image/png" else "jpg"
        return (data, time.strftime(f"clip-%Y%m%d-%H%M%S.{ext}"), image, False) if data else None
    data = paste("--no-newline")
    return (data, "clipboard.txt", "text/plain", True) if data.strip() else None


def cmd_send(args) -> int:
    """Queue files or text for the devices; the app fetches them when it opens."""
    import subprocess
    from pathlib import Path

    queued = []
    try:
        if args.text is not None:
            queued.append(inbox.queue(args.text.encode(), "text.txt", "text/plain", text=True))
        if args.clipboard:
            clip = _clipboard()
            if clip is None:
                print("The clipboard is empty.")
                return 1
            queued.append(inbox.queue(*clip))
        for name in args.files:
            path = Path(name).expanduser()
            if not path.is_file():
                print(f"Not a file: {name}")
                return 1
            if path.stat().st_size > inbox.MAX_BYTES:
                raise inbox.TooBig(f"{path.name}: larger than {inbox.MAX_BYTES // (1024 * 1024)} MB")
            queued.append(inbox.queue(path.read_bytes(), path.name))
    except inbox.TooBig as error:
        print(error)
        return 1
    if not queued:
        waiting = inbox.items()
        print("Nothing waiting." if not waiting else "Waiting for a device:")
        for item in waiting:
            print(f"  {item['name']}  ({item['size']} bytes)")
        return 0
    what = queued[0]["name"] if len(queued) == 1 else f"{len(queued)} items"
    if queued[0]["text"] and len(queued) == 1:
        what = "Text"
    print(f"Queued: {what}. Open Enlace on the phone or tablet to receive it.")
    if args.notify:
        subprocess.run(["notify-send", "-a", "enlace", "Enlace", f"{what}  ·  open Enlace on the device"],
                       check=False, timeout=5)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="enlace", description="Link phone and tablet to this PC")
    sub = parser.add_subparsers(dest="command", required=True)
    serve = sub.add_parser("serve", help="run the link service")
    serve.add_argument("--port", type=int, default=protocol.PORT)
    serve.set_defaults(func=cmd_serve)
    pair = sub.add_parser("pair", help="show a QR to pair a new device")
    pair.add_argument("--port", type=int, default=protocol.PORT)
    pair.add_argument("--host", help="address to put in the QR (default: this PC's LAN address)")
    pair.add_argument("--uri-only", action="store_true", help="print the pairing link and exit")
    pair.set_defaults(func=cmd_pair)
    sub.add_parser("devices", help="list paired devices").set_defaults(func=cmd_devices)
    unpair = sub.add_parser("unpair", help="forget a device")
    unpair.add_argument("device", help="name or id")
    unpair.set_defaults(func=cmd_unpair)
    lamp = sub.add_parser(
        "lamp", help="switch and dim the lamps",
        description="status | on | off | toggle | brightness N|+N|-N | temp N|warm|neutral|cold | "
                    "color NAME|#rrggbb | default NAME | link USER_CODE",
    )
    lamp.add_argument("verb", nargs="?", default="status", choices=[
        "status", "on", "off", "toggle", "brightness", "temp", "color", "default", "link"])
    lamp.add_argument("value", nargs="?")
    lamp.add_argument("--lamp", help="part of the lamp's name (default: the default lamp)")
    lamp.add_argument("--brightness", help="with on/toggle/temp/color: percentage")
    lamp.add_argument("--temp", help="with on: white temperature, 0 warm to 100 cold")
    lamp.add_argument("--color", help="with on: colour name or #rrggbb")
    lamp.add_argument("--json", action="store_true", help="print the state as JSON")
    lamp.add_argument("--waybar", action="store_true", help="print the state as Waybar JSON")
    lamp.add_argument("--quick", action="store_true",
                      help="status: do not search the network on every call (for polling)")
    lamp.set_defaults(func=cmd_lamp)
    send = sub.add_parser("send", help="queue files or text for the phone or tablet",
                          description="With no arguments: list what is still waiting.")
    send.add_argument("files", nargs="*", help="files to send")
    send.add_argument("--text", help="text that lands in the device's clipboard")
    send.add_argument("--clipboard", action="store_true", help="send what this PC's clipboard holds")
    send.add_argument("--notify", action="store_true", help="also show a desktop notification")
    send.set_defaults(func=cmd_send)
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
