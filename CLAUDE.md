# Enlace

Links Juanma's phone and tablet (Android) to the PC over the home Wi-Fi. Part of the ecosystem:
https://claude.ai/artifact/PHQmVerxfbkkCipzxGaF2d

**Does:** pairing by QR (once per device); `mic` (the device's microphone becomes a capture
device on the PC); `share` (Compartir → A la compu: text and images land in the PC clipboard,
other files in ~/Downloads); `actions` (buttons in the app that run commands listed on the PC).
**Does not:** work outside the LAN, sync anything by itself in the background, send from the PC
to the device, or touch any other app's data. No accounts, no cloud, no adb.

- **Two halves, one contract.** `pc/` (Python, asyncio, `uv`) is the service; `app/` (Kotlin,
  views built in code, no Compose) is the Android app. They only share `docs/contract.md` and
  `contract/fixtures.json`, which both test suites run. Change the wire format there first.
- **Core + modules.** The core (pairing, discovery, pinned TLS, frame routing) knows nothing
  about features. A feature is one file in `pc/src/enlace/modules/` plus one package in
  `app/.../modules/`. Read `docs/modules.md` before adding one. PC-only ideas are usually just
  a new entry in `~/.config/enlace/actions.toml`: no code at all.
- UI text in Rioplatense Spanish, all in `app/.../Strings.kt`. PC notifications, code, comments
  and commits in English. Look: Nord, from `Ui.kt`; never style a view by hand.
- Design changes: rendered variants first (`tools/screens.sh` draws the real screens), he picks.
- Signing key: `~/.config/enlace/signing/`, never in git or Drive. Losing it means reinstalling
  the app on every device. PC state (certificate, paired devices): `~/.local/state/enlace/`.
- Session tools (JDK 17, Gradle 8.7, Android SDK 34) go in the scratchpad with `GRADLE_USER_HOME`
  there too. Nothing global. The PC runs the service from this checkout (`uv tool install
  --editable pc`), started by `enlace.service` in dotfiles: restart it after changing `pc/`.
- Branch + PR per change.

## Check

```bash
(cd pc && uv run pytest -q)                  # PC: contract, core over real TLS, modules
gradle testDebugUnitTest assembleDebug       # app: contract; builds the APK
tools/interop.sh [--mic]                     # the app's real link code against the real service
tools/screens.sh                             # real screens to PNG, no device
claude-budget                                # startup context, goal < 3000 tokens
```

`tools/interop.sh` is the test that matters after touching the core on either side.

## Read before

| Before… | Read |
|---|---|
| changing a message, the handshake, pairing or discovery | `docs/contract.md` |
| adding or changing a module | `docs/modules.md` |
| what is confirmed on a real device, and what is next | `docs/status.md` |
