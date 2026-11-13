#!/usr/bin/env bash
# Draws the app's real screens to PNG without a device (Robolectric), against
# the real PC service on a spare port so the linked screen shows real buttons.
# Usage: tools/screens.sh [OUT_DIR]   (default: build/screens)
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=$(realpath -m "${1:-build/screens}")
PORT=47812
TMP=$(mktemp -d)
export XDG_STATE_HOME="$TMP/state" XDG_CONFIG_HOME="$TMP/config"

uv run --project pc enlace serve --port "$PORT" > "$TMP/server.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; rm -rf "$TMP"' EXIT
for _ in $(seq 50); do
    grep -q listening "$TMP/server.log" 2>/dev/null && break
    sleep 0.2
done

uv run --project pc enlace send --text "https://campus.example/tp3" > /dev/null

ENLACE_INTEROP=$(uv run --project pc enlace pair --uri-only --host 127.0.0.1 --port "$PORT")
export ENLACE_INTEROP ENLACE_SCREENS="$OUT"
gradle --no-daemon -q testDebugUnitTest --tests 'com.enlace.app.ScreensTest'
ls "$OUT"
