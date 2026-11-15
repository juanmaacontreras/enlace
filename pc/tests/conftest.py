import pytest


@pytest.fixture(autouse=True)
def own_folders(tmp_path, monkeypatch):
    """No test may read or write this PC's real Enlace state, config or Downloads
    (the usage trace would fill with test lines). A test that needs a particular
    folder sets its own on top of this."""
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "xdg-state"))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "xdg-config"))
    monkeypatch.setenv("ENLACE_DOWNLOADS", str(tmp_path / "xdg-downloads"))
