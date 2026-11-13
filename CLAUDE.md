# Enlace

Links Juanma's phone and tablet (Android) to the PC over the home Wi-Fi. Part of the ecosystem:
https://claude.ai/artifact/PHQmVerxfbkkCipzxGaF2d

**Does:** pairing by QR (once per device); `mic` (the device's microphone becomes a capture
device on the PC); `share` (Compartir → A la compu: text and images land in the PC clipboard,
other files in ~/Downloads); `actions` (buttons in the app that run commands listed on the PC);
`inbox` (`enlace send <files> | --text | --clipboard` on the PC queues things the app fetches
when it opens: text to the device clipboard, images to its gallery, files to Downloads);
`enlace lamp` (PC command for the Tuya Wi-Fi bulbs, `pc/src/enlace/lamps.py`; the phone reaches
it through action buttons).
**Does not:** work outside the LAN, sync anything by itself in the background, push to a
closed app, or touch any other app's data. No accounts, no cloud, no adb (one exception:
`enlace lamp link` logs in to Smart Life once to fetch the bulbs' keys; control is local).

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
- **Releases are cut on this PC**, not in CI (the key stays here): `tools/release.sh` on a pushed
  `main` builds, checks and publishes `enlace.apk` as GitHub Release `v<commit count>`. The app
  checks the latest release when it opens and offers to update (`core/Updater.kt`); the repo is
  public for that, so nothing secret or personal goes in it. CI (`check.yml`) only tests.
- Branch + PR per change; a merged change that touches `app/` is followed by a release.

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
