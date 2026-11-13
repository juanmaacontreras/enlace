#!/usr/bin/env bash
# Builds the signed APK on this PC and publishes it as a GitHub Release, which
# is what the installed app offers as an update (core/Updater.kt).
#
# The version is the number of commits on main: it only goes up, and Android
# refuses to install a lower one over a higher one. The signing key never
# leaves ~/.config/enlace/signing, which is why this runs here and not in CI.
#
# Needs java 17, gradle 8.7 and ANDROID_HOME in the environment (see CLAUDE.md).
# Usage: tools/release.sh            build, check and publish
#        tools/release.sh --solo-apk OUT   build and check only, copy the APK to OUT
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f "$HOME/.config/enlace/signing/enlace.jks" ] || { echo "no signing key in ~/.config/enlace/signing"; exit 1; }
VERSION=$(git rev-list --count HEAD)

if [ "${1:-}" != "--solo-apk" ]; then
    [ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || { echo "releases are cut from main"; exit 1; }
    [ -z "$(git status --porcelain)" ] || { echo "uncommitted changes"; exit 1; }
    git fetch -q origin main
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main is not pushed"; exit 1; }
fi

(cd pc && uv run pytest -q)
gradle --no-daemon -q testDebugUnitTest assembleDebug -Pvcode="$VERSION" -Pvname="$VERSION"
tools/interop.sh

APK=app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs "$APK" | grep -q "CN=Enlace" \
    || { echo "the APK is not signed with the Enlace key"; exit 1; }

if [ "${1:-}" = "--solo-apk" ]; then
    cp "$APK" "$2"
    echo "v$VERSION -> $2"
    exit 0
fi

OUT=$(mktemp -d)
cp "$APK" "$OUT/enlace.apk"
gh release create "v$VERSION" "$OUT/enlace.apk" --title "v$VERSION" --notes "$(git log -1 --format=%s)"
rm -rf "$OUT"
echo "released v$VERSION"
