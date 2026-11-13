# Contract between the app and the PC (version 1)

The fixed format both halves implement. `contract/fixtures.json` holds the same thing as data;
`pc/tests/test_contract.py` and `app/src/test/.../ContractTest.kt` both run against it, and
`tools/interop.sh` runs the app's real link code against the real service. A change here goes
into the fixtures first, then both sides.

## Transport

- TCP port **47800**, TLS 1.2+. The PC's certificate is self-signed, made once
  (`~/.local/state/enlace/cert.pem`). The device trusts exactly that certificate, by the SHA-256
  of its DER form, which it learned from the pairing QR. No certificate authority, no host names.
- UDP port **47800** for finding the PC when its address changed. The device broadcasts
  `{"enlace":1,"find":"<pc id>"}`; only the PC with that id answers
  `{"enlace":1,"pc":"<pc id>","port":47800}`. The answer is not trusted on its own: the pinned
  certificate is what proves the machine is the same.

## Frame

`4 bytes big-endian header length` · `header: UTF-8 JSON object` · `body: header.size bytes`
(`size` is absent when there is no body). Header at most 64 KiB, body at most 256 MiB. A frame
that breaks this ends the connection.

## Session

1. Device → `hello`: `{"type":"hello","v":1,"device":{"id","name"},"modules":[…]}` plus either
   `"pair":"<code from the QR>"` or `"token":"<device token>"`. No body allowed.
2. PC → `welcome`: `{"type":"welcome","v":1,"pc":{"id","name"},"modules":[…]}`; `modules` is what
   **both** sides have, and the app shows only those. After a `pair` it also carries `"token"`,
   issued once and stored hashed on the PC. Or → `{"type":"error","code":…}` and the PC hangs up:
   `unauthorized`, `bad_pair_code`, `unsupported_version`, `bad_request`.
3. Then any number of frames, each with `"module":"<name>"`, routed to that module. Unknown
   module → `{"type":"error","code":"unknown_module"}`. A module that fails answers
   `{"type":"error","code":"failed","module":…}` and the link stays up.

A device opens one connection per job (the screen, a share, the microphone stream) and closes it.

## Pairing

`enlace pair` writes a one-time code (valid 2 minutes) and shows
`enlace://pair?h=<host>&p=<port>&id=<pc id>&n=<pc name>&fp=<sha256 hex>&c=<code>` as a QR.

## Modules

| Module | Device sends | PC answers |
|---|---|---|
| `mic` | `start` `{format:"s16le", rate:48000\|44100\|16000, channels:1\|2}` | `started`, or error `unsupported_format` / `no_audio_server` |
| | `audio` + body: raw PCM, any length | nothing (dropped if the PC falls behind) |
| | `stop` | `stopped` |
| `share` | `item` `{mime, name?, to:"clipboard"\|"downloads"}` + body | `done` `{result:"clipboard"\|"saved", name?}` |
| `actions` | `list` | `list` `{actions:[{id,label,icon}]}` (commands never leave the PC) |
| | `run` `{id}` | `ran` `{id, ok}` |

Closing the connection ends whatever the device had running (the microphone).
