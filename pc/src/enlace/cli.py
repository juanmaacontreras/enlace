"""`enlace serve | pair | devices | unpair`."""

from __future__ import annotations

import argparse
import asyncio
import logging
import socket
import sys
import time
from urllib.parse import urlencode

from . import protocol
from .modules import actions
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
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
