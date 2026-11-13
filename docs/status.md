# Status

## Verified without a device (2026-11-12)

- PC tests, app contract tests, `tools/interop.sh --mic` (pairing, refusals, discovery, the three
  modules, test tone recorded back from the `enlace-mic` source), `tools/screens.sh`.
- Real service on the PC: text and a JPEG shared into the Wayland clipboard as `image/png`.

## Not yet verified on a real device

Nothing has run on the phone or the tablet yet (adb was down and Waydroid could not start: the
kernel had been updated without a reboot). First install must check:

- QR scan and pairing from the camera.
- Microphone: permission prompts, the foreground service on HyperOS with the screen off, sound
  quality and delay, the quick-settings tile, stop from the notification.
- Compartir → A la compu from Gallery, Chrome and WhatsApp; "Enviar a la compu" on selected text.
- Action buttons.
- Whether HyperOS kills the microphone service in the background (if so: battery "no restrictions").

## Next

- App updates through the link itself (the PC hands the device a newer APK), so the repo can stay
  private and no update ever needs a cable or a download.
- PC → device clipboard (Android allows writing the clipboard from a background service).
- Retire the old path in dotfiles once the microphone is confirmed: `phone-mic`,
  `phone-mic.service`, the phone entries of `audio-in`, `clipdrop` and LocalSend.
