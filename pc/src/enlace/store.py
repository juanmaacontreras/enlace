"""What the PC remembers: its identity, its TLS certificate and paired devices.

Everything lives in ~/.local/state/enlace (mode 700). Device tokens are stored
hashed, so reading this folder does not let anyone impersonate a device.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import socket
import ssl
import subprocess
import time
from pathlib import Path

PAIR_SECONDS = 120


def state_dir() -> Path:
    base = os.environ.get("XDG_STATE_HOME") or os.path.expanduser("~/.local/state")
    return Path(base) / "enlace"


def config_dir() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "enlace"


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


class Store:
    def __init__(self, root: Path | None = None):
        self.root = root or state_dir()
        self.root.mkdir(parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)

    # --- small JSON files, written atomically ------------------------------

    def _read(self, name: str, default):
        try:
            return json.loads((self.root / name).read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            return default

    def _write(self, name: str, data) -> None:
        tmp = self.root / (name + ".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump(data, fh, indent=2)
        os.replace(tmp, self.root / name)

    # --- identity and certificate ------------------------------------------

    def identity(self) -> dict:
        ident = self._read("identity.json", None)
        if not ident:
            ident = {"id": secrets.token_hex(8), "name": socket.gethostname()}
            self._write("identity.json", ident)
        return ident

    @property
    def cert_path(self) -> Path:
        return self.root / "cert.pem"

    @property
    def key_path(self) -> Path:
        return self.root / "key.pem"

    def ensure_certificate(self) -> None:
        """Self-signed, made once. Devices pin its fingerprint when pairing,
        so replacing it means pairing every device again."""
        if self.cert_path.exists() and self.key_path.exists():
            return
        subprocess.run(
            [
                "openssl", "req", "-x509", "-newkey", "ec",
                "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-keyout", str(self.key_path), "-out", str(self.cert_path),
                "-days", "7300", "-subj", f"/CN=enlace-{self.identity()['id']}",
            ],
            check=True,
            capture_output=True,
        )
        os.chmod(self.key_path, 0o600)

    def fingerprint(self) -> str:
        der = ssl.PEM_cert_to_DER_cert(self.cert_path.read_text())
        return hashlib.sha256(der).hexdigest()

    def tls_context(self) -> ssl.SSLContext:
        self.ensure_certificate()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(self.cert_path, self.key_path)
        return ctx

    # --- pairing -----------------------------------------------------------

    def start_pairing(self) -> str:
        """A one-time code, valid for PAIR_SECONDS. It travels only in the QR."""
        code = secrets.token_urlsafe(16)
        self._write("pairing.json", {"code": code, "expires": time.time() + PAIR_SECONDS})
        return code

    def pairing_pending(self) -> bool:
        pending = self._read("pairing.json", None)
        return bool(pending) and pending["expires"] > time.time()

    def finish_pairing(self, code: str, device_id: str, name: str) -> str | None:
        """Consume the code and return the new device token, or None."""
        pending = self._read("pairing.json", None)
        if not pending or pending["expires"] < time.time():
            return None
        if not isinstance(code, str) or not hmac.compare_digest(pending["code"], code):
            return None
        (self.root / "pairing.json").unlink(missing_ok=True)
        token = secrets.token_urlsafe(32)
        devices = self.devices()
        devices[device_id] = {
            "name": name,
            "token_sha256": _sha256(token),
            "paired": int(time.time()),
        }
        self._write("devices.json", devices)
        return token

    # --- devices -----------------------------------------------------------

    def devices(self) -> dict:
        return self._read("devices.json", {})

    def authenticate(self, device_id: str, token: str) -> dict | None:
        device = self.devices().get(device_id)
        if not device or not isinstance(token, str):
            return None
        if not hmac.compare_digest(device["token_sha256"], _sha256(token)):
            return None
        return device

    def unpair(self, device_id: str) -> bool:
        devices = self.devices()
        if device_id not in devices:
            return False
        del devices[device_id]
        self._write("devices.json", devices)
        return True
