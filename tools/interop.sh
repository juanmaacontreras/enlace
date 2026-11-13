#!/usr/bin/env bash
# Cross-check: the app's real connection code (on the JVM, no phone) against
# the real PC service. Starts the service on a spare port with throwaway
# state, pairs, and exercises every module. Usage: tools/interop.sh [--mic]
#
# --mic also streams a second of test tone into PipeWire; it shows two desktop
# notifications and briefly switches the default microphone.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT=47811
TMP=$(mktemp -d)
export XDG_STATE_HOME="$TMP/state" XDG_CONFIG_HOME="$TMP/config" ENLACE_DOWNLOADS="$TMP/downloads"
mkdir -p "$XDG_CONFIG_HOME/enlace"
cat > "$XDG_CONFIG_HOME/enlace/actions.toml" <<TOML
[[action]]
id = "touch"
label = "Touch"
run = "touch $TMP/ran"
TOML

uv run --project pc enlace serve --port "$PORT" > "$TMP/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$TMP"' EXIT
for _ in $(seq 50); do
    grep -q listening "$TMP/server.log" 2>/dev/null && break
    sleep 0.2
done

uv run --project pc enlace send --text "hola ñ" > /dev/null
python3 -c 'import sys; sys.stdout.buffer.write(bytes(i % 249 for i in range(200000)))' > "$TMP/para el celu.bin"
sleep 1  # ids start with the second: keeps the two items in order
uv run --project pc enlace send "$TMP/para el celu.bin" > /dev/null

ENLACE_INTEROP=$(uv run --project pc enlace pair --uri-only --host 127.0.0.1 --port "$PORT")
export ENLACE_INTEROP
[ "${1:-}" = "--mic" ] && export ENLACE_INTEROP_MIC=1

gradle --no-daemon -q testDebugUnitTest --tests 'com.enlace.app.LinkInteropTest'

[ -f "$TMP/ran" ] || { echo "the action button did not run on the PC"; exit 1; }
cmp -s <(python3 -c 'import sys; sys.stdout.buffer.write(bytes(i % 251 for i in range(300000)))') \
    "$ENLACE_DOWNLOADS/apunte ñ.pdf" || { echo "the shared file arrived damaged"; exit 1; }
[ -z "$(ls -A "$XDG_STATE_HOME/enlace/outbox")" ] || { echo "a confirmed item is still queued on the PC"; exit 1; }
grep -q "paired Interop" "$TMP/server.log" || { echo "the PC did not record the pairing"; exit 1; }
echo "interop OK: pairing, refusals, discovery, actions, share, inbox${ENLACE_INTEROP_MIC:+, mic}"
