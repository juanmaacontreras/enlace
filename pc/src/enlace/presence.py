"""presence: is a paired device on the home network right now?

The service remembers the address (and hardware address) each device last
connected from. Asking is one ping and a look at the kernel's neighbour table:
a phone asleep on Wi-Fi may not answer the ping, but its radio still answers
the address lookup, and that is what counts.

Does not: need anything from the app (no background work on the device), or
know where a device is when it is not on this network. A device that has not
opened the app since it got a new address looks absent, so use the answer
only for things that are harmless when wrong.
"""

from __future__ import annotations

import ipaddress
import subprocess
import time

WAIT_SECONDS = 9  # the kernel takes this long to give up on an address that stopped answering


def neighbour(address: str) -> tuple[str, str]:
    """(hardware address, state) the kernel has for `address`; empty strings if none."""
    try:
        out = subprocess.run(["ip", "neigh", "show", address], capture_output=True, text=True, timeout=3).stdout
    except (OSError, subprocess.SubprocessError):
        return "", ""
    return parse_neighbour(out)


def parse_neighbour(out: str) -> tuple[str, str]:
    words = out.split()
    if not words:
        return "", ""
    mac = words[words.index("lladdr") + 1].lower() if "lladdr" in words[:-1] else ""
    return mac, words[-1]


def decide(mac: str, state: str, known_mac: str) -> bool | None:
    """True: there. False: gone. None: the kernel has not made up its mind yet."""
    if state == "REACHABLE":
        return not known_mac or mac == known_mac  # another machine took the address: gone
    if state in ("FAILED", ""):
        return False
    return None  # INCOMPLETE, STALE, DELAY, PROBE: still asking (a sleeping phone answers late)


def present(address: str, known_mac: str = "", wait: float = WAIT_SECONDS) -> bool:
    try:
        ipaddress.ip_address(address)
    except ValueError:
        return False
    deadline = time.time() + wait
    while True:
        try:
            answered = subprocess.run(["ping", "-c", "1", "-W", "1", address],
                                      capture_output=True, timeout=3).returncode == 0
        except (OSError, subprocess.SubprocessError):
            answered = False
        mac, state = neighbour(address)
        if answered and (not known_mac or not mac or mac == known_mac):
            return True
        verdict = decide(mac, state, known_mac)
        if verdict is not None:
            return verdict
        if time.time() >= deadline:
            return False
        time.sleep(1)
