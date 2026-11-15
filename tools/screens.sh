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

mkdir -p "$XDG_CONFIG_HOME/enlace"
cat > "$XDG_CONFIG_HOME/enlace/actions.toml" <<'TOML'
action = [
  { id = "lamp", label = "Luz", icon = "bulb", group = "Luz", run = "true" },
  { id = "lamp-dimmer", label = "Menos", icon = "bright-down", group = "Luz", run = "true" },
  { id = "lamp-brighter", label = "Más", icon = "bright-up", group = "Luz", run = "true" },
  { id = "lamp-study", label = "Estudio", icon = "screen", group = "Luz", run = "true" },
  { id = "lamp-night", label = "Noche", icon = "moon", group = "Luz", run = "true" },
  { id = "lock", label = "Bloquear", icon = "lock", run = "true" },
  { id = "play-pause", label = "Play", icon = "play", run = "true" },
  { id = "next", label = "Siguiente", icon = "next", run = "true" },
  { id = "volume-down", label = "Vol −", icon = "volume-down", run = "true" },
  { id = "volume-up", label = "Vol +", icon = "volume-up", run = "true" },
  { id = "mute", label = "Silenciar", icon = "mute", run = "true" },
]
TOML
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
