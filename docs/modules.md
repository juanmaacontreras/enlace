# Adding a feature

First ask whether it needs code at all. **Something the PC does when a button is tapped**
(lock, switch audio output, pause, open something) is four lines in
`~/.config/enlace/actions.toml`; the app re-reads the list every time it opens. The lamp
buttons are exactly that: entries that run `enlace lamp toggle`, `enlace lamp brightness +20`,
`enlace lamp on --temp warm --brightness 15` (`enlace lamp -h` lists the rest).

A feature that needs something from the device (a sensor, a permission, a stream) is a module:

1. **Contract.** Add its messages to `docs/contract.md` and examples to `contract/fixtures.json`
   (`app_sends` / `pc_sends`). Name it once; that name travels in every frame.
2. **PC.** One file in `pc/src/enlace/modules/` with a `Module` subclass (`name`, `handle`,
   optionally `closed`) and `MODULE = ThatClass`. It is found by itself. Start the docstring with
   what it does and what it does not. Tests in `pc/tests/test_modules.py`.
3. **App.** One package in `app/src/main/java/com/enlace/app/modules/<name>/` with an object
   implementing `Module`, and one line in `Modules.all` (`core/Modules.kt`). Builders for its
   messages go in `core/Messages.kt` with a line in `ContractTest`. Services and activities get
   their own block in the manifest under a `<!-- module: name -->` comment. Text in `Strings.kt`.
4. `tools/interop.sh`: extend `LinkInteropTest` so the new messages cross the real link.

Removing a module is deleting those pieces. Because `welcome.modules` is the intersection, a
device with an older app and a PC with a newer service (or the reverse) keep working: the module
the other side lacks is simply not shown.

## Rules a module keeps

- It never trusts the device for anything that runs or is written on the PC: `actions` runs only
  ids listed in the file; `share` strips every path from a file name; `inbox` serves only ids it
  listed itself.
- It releases what it holds in `closed()`: a connection can drop at any moment.
- Nothing fails quietly. A dead microphone looks like a working one, so `MicService` retries for
  half a minute and then says so in a notification.

## Icons for action buttons

`lock, play, next, previous, volume-up, volume-down, mute, power, screen, moon, bolt, mic, share`.
A new one is one line of 24×24 path data in `Icons` (`Ui.kt`); unknown names draw a dot.
