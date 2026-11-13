import asyncio
import io

from enlace import modules
from enlace.modules import actions, share


class FakeSession:
    device_id, name = "d1", "Redmi"

    def __init__(self):
        self.sent = []

    async def send(self, header, body=b""):
        self.sent.append(header)


def test_every_module_file_is_found():
    assert sorted(modules.load()) == ["actions", "mic", "share"]


def test_safe_name_never_keeps_a_path():
    assert share.safe_name("../../.bashrc", "text/plain") == "bashrc"
    assert share.safe_name("/etc/passwd", "text/plain") == "passwd"
    assert share.safe_name("", "image/png") == "enlace.png"
    assert share.safe_name(None, "application/pdf") == "enlace"
    assert "/" not in share.safe_name("a/b\\c\n.txt", "text/plain")


def test_free_path_does_not_overwrite(tmp_path):
    (tmp_path / "a.pdf").write_text("x")
    assert share.free_path(tmp_path, "a.pdf").name == "a-1.pdf"


def test_jpeg_becomes_png():
    from PIL import Image

    jpeg = io.BytesIO()
    Image.new("RGB", (4, 4), "red").save(jpeg, "JPEG")
    assert share.to_png(jpeg.getvalue()).startswith(b"\x89PNG")
    assert share.to_png(b"not an image") is None


def test_share_to_downloads(tmp_path, monkeypatch):
    monkeypatch.setenv("ENLACE_DOWNLOADS", str(tmp_path))
    async def no_notify(*a, **k): pass
    monkeypatch.setattr(share.desktop, "notify", no_notify)
    session = FakeSession()
    header = {"module": "share", "type": "item", "mime": "application/pdf",
              "name": "../apunte.pdf", "to": "downloads"}
    asyncio.run(share.Share().handle(session, header, b"%PDF"))
    assert (tmp_path / "apunte.pdf").read_bytes() == b"%PDF"
    assert session.sent == [{"module": "share", "type": "done", "result": "saved", "name": "apunte.pdf"}]


def test_actions_run_only_what_the_file_lists(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    marker = tmp_path / "ran"
    (tmp_path / "enlace").mkdir()
    (tmp_path / "enlace" / "actions.toml").write_text(f'''
[[action]]
id = "touch"
label = "Tocar"
icon = "bolt"
run = "touch {marker}"

[[action]]
id = "fails"
run = "false"

[[action]]
label = "no id, ignored"
run = "true"
''')
    session = FakeSession()
    mod = actions.Actions()

    async def scenario():
        await mod.handle(session, {"module": "actions", "type": "list"}, b"")
        await mod.handle(session, {"module": "actions", "type": "run", "id": "touch"}, b"")
        await mod.handle(session, {"module": "actions", "type": "run", "id": "fails"}, b"")
        await mod.handle(session, {"module": "actions", "type": "run", "id": "rm -rf ~"}, b"")

    asyncio.run(scenario())
    listed, ran, failed, unknown = session.sent
    assert listed["actions"] == [
        {"id": "touch", "label": "Tocar", "icon": "bolt"},
        {"id": "fails", "label": "fails", "icon": ""},
    ]
    assert "run" not in json_dump(listed)  # commands never leave the PC
    assert ran == {"module": "actions", "type": "ran", "id": "touch", "ok": True}
    assert marker.exists()
    assert failed["ok"] is False and unknown["ok"] is False


def json_dump(obj):
    import json
    return json.dumps(obj).replace('"ran"', "")


def test_broken_actions_file_gives_no_buttons(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    (tmp_path / "enlace").mkdir()
    (tmp_path / "enlace" / "actions.toml").write_text("[[action")
    assert actions.read() == []


def test_example_actions_file_is_valid(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert actions.write_example() and not actions.write_example()
    assert [a["id"] for a in actions.read()][:2] == ["lock", "play-pause"]
