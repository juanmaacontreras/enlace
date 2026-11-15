from enlace import presence
from enlace.store import Store

MAC = "aa:bb:cc:dd:ee:ff"


def test_reads_the_kernel_line():
    assert presence.parse_neighbour(f"192.168.0.7 dev enp5s0 lladdr {MAC.upper()} REACHABLE\n") == (MAC, "REACHABLE")
    assert presence.parse_neighbour("192.168.0.7 dev enp5s0 FAILED\n") == ("", "FAILED")
    assert presence.parse_neighbour("") == ("", "")


def test_decides_only_when_the_kernel_is_sure():
    assert presence.decide(MAC, "REACHABLE", MAC) is True
    assert presence.decide(MAC, "REACHABLE", "") is True
    assert presence.decide("11:22:33:44:55:66", "REACHABLE", MAC) is False  # the address went to another machine
    assert presence.decide("", "FAILED", MAC) is False
    assert presence.decide("", "", MAC) is False
    assert presence.decide("", "INCOMPLETE", MAC) is None
    assert presence.decide(MAC, "STALE", MAC) is None
    assert presence.decide(MAC, "DELAY", MAC) is None


def test_an_address_that_is_not_one_is_never_pinged():
    assert presence.present("; rm -rf ~", wait=0) is False


def test_the_store_remembers_where_a_device_was_seen(tmp_path):
    store = Store(tmp_path)
    code = store.start_pairing()
    assert store.finish_pairing(code, "d1", "Redmi")
    store.seen("d1", "192.168.0.7", MAC)
    device = store.devices()["d1"]
    assert (device["address"], device["mac"]) == ("192.168.0.7", MAC) and device["seen"] > 0
    store.seen("stranger", "192.168.0.9")
    assert "stranger" not in store.devices()
