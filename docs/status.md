# Status

## Verified without a device (2026-11-12)

- PC tests, app contract tests, `tools/interop.sh --mic` (pairing, refusals, discovery, the three
  modules, test tone recorded back from the `enlace-mic` source), `tools/screens.sh`.
- Real service on the PC: text and a JPEG shared into the Wayland clipboard as `image/png`.

## Confirmed on the phone (Redmi Note 11, Android 13, app v4) — 2026-11-13

- Install from the GitHub release, QR scan and pairing ("vinculó y anda", the user).
- Microphone: the PC had `enlace-mic` ("Redmi Note 11 (Enlace)") as its default source and a
  3-second recording from it peaked at -16 dBFS.

## Not yet verified on a real device

The tablet has not installed it yet. Still to check on either device:

- Microphone over a long call: the foreground service on HyperOS with the screen off, delay, the
  quick-settings tile, stop from the notification.
- Compartir → A la compu from Gallery, Chrome and WhatsApp; "Enviar a la compu" on selected text.
- Action buttons.
- The update card: it shows only when GitHub has a release newer than the installed build, so the
  first real check is the second release.
- Whether HyperOS kills the microphone service in the background (if so: battery "no restrictions").

## Next

- PC → device clipboard (Android allows writing the clipboard from a background service).
- Retire the old path in dotfiles once the microphone is confirmed: `phone-mic`,
  `phone-mic.service`, the phone entries of `audio-in`, `clipdrop` and LocalSend.
