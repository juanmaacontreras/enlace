# Status

## Verified without a device (2026-11-12)

- PC tests, app contract tests, `tools/interop.sh --mic` (pairing, refusals, discovery, the three
  modules, test tone recorded back from the `enlace-mic` source), `tools/screens.sh`.
- Real service on the PC: text and a JPEG shared into the Wayland clipboard as `image/png`.

## Confirmed on the phone (Redmi Note 11, Android 13, app v4) — 2026-11-13

- Install from the GitHub release, QR scan and pairing ("vinculó y anda", the user).
- Microphone: the PC had `enlace-mic` ("Redmi Note 11 (Enlace)") as its default source and a
  3-second recording from it peaked at -16 dBFS.
- Microphone with the phone suspended (screen off): the user dictated a message through it and
  it was understood. HyperOS did not stop the service in that test.

## Lamp (2026-11-13)

- `enlace lamp` against the real bulb ("Lámpara de la habitación", Tuya protocol 3.5, Wi-Fi):
  on/off/toggle, brightness absolute and relative, temperature, colour by name and hex; state
  read back from the bulb matched. A toggle takes about 0.6 s from the shell.
- The five lamp buttons in `actions.toml` ran through the service's own runner. Not yet tapped
  from the phone.
- Keys came from `Smart Life` by QR (`enlace lamp link <user code>`); re-pairing a bulb in the
  app changes its key, so link again after that. The second bulb ("Luz") was offline.

## Not yet verified on a real device

The tablet has not installed it yet. Still to check on either device:

- Microphone over a long call (an hour with the screen off), delay, the quick-settings tile,
  stop from the notification.
- Compartir → A la compu from Gallery, Chrome and WhatsApp; "Enviar a la compu" on selected text.
- Action buttons.
- The update card: it shows only when GitHub has a release newer than the installed build, so the
  first real check is the second release.
- Whether HyperOS kills the microphone service in the background (if so: battery "no restrictions").

## Next

- Lamp: a `lamp` module in the app (switch, brightness and colour sliders; rendered variants
  first), a key binding and a bar icon on the PC, lamp off when the PC locks.

- PC → device clipboard (Android allows writing the clipboard from a background service).
- Retire the old path in dotfiles once the microphone is confirmed: `phone-mic`,
  `phone-mic.service`, the phone entries of `audio-in`, `clipdrop` and LocalSend.
