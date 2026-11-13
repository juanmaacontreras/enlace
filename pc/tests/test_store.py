import json
import time

from enlace.store import Store


def test_pairing_issues_a_token_once(tmp_path):
    store = Store(tmp_path)
    code = store.start_pairing()
    assert store.finish_pairing("wrong", "d1", "Redmi") is None
    token = store.finish_pairing(code, "d1", "Redmi")
    assert token
    assert store.finish_pairing(code, "d2", "Other") is None  # single use
    assert store.authenticate("d1", token)["name"] == "Redmi"
    assert store.authenticate("d1", "not-the-token") is None
    assert store.authenticate("d2", token) is None


def test_token_is_not_stored_in_clear(tmp_path):
    store = Store(tmp_path)
    token = store.finish_pairing(store.start_pairing(), "d1", "Redmi")
    assert token not in (tmp_path / "devices.json").read_text()


def test_expired_code_is_refused(tmp_path):
    store = Store(tmp_path)
    code = store.start_pairing()
    pending = json.loads((tmp_path / "pairing.json").read_text())
    pending["expires"] = time.time() - 1
    (tmp_path / "pairing.json").write_text(json.dumps(pending))
    assert store.finish_pairing(code, "d1", "Redmi") is None


def test_unpair(tmp_path):
    store = Store(tmp_path)
    token = store.finish_pairing(store.start_pairing(), "d1", "Redmi")
    assert store.unpair("d1")
    assert store.authenticate("d1", token) is None
    assert not store.unpair("d1")


def test_state_is_private(tmp_path):
    store = Store(tmp_path / "state")
    store.start_pairing()
    assert (tmp_path / "state").stat().st_mode & 0o777 == 0o700
    assert (tmp_path / "state" / "pairing.json").stat().st_mode & 0o777 == 0o600
