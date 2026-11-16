#!/usr/bin/env bash
# Downloads the build tools (JDK 17, Gradle 8.7, Android SDK 34) into a directory
# and prints the environment to use them. Nothing is installed globally: the
# directory is meant to be a session scratchpad, so a reboot may clear it and
# this script puts it back.
#
# Usage: eval "$(tools/toolchain.sh DIR)"
set -euo pipefail
DIR=$(realpath -m "${1:?usage: tools/toolchain.sh DIR}")
mkdir -p "$DIR"
cd "$DIR"

if [ ! -x jdk/bin/java ]; then
    curl -fsSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p jdk && tar -xzf jdk.tgz -C jdk --strip-components=1 && rm jdk.tgz
fi >&2

if [ ! -x gradle/bin/gradle ]; then
    curl -fsSL -o gradle.zip "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
    unzip -q gradle.zip && mv gradle-8.7 gradle && rm gradle.zip
fi >&2

export JAVA_HOME="$DIR/jdk" ANDROID_HOME="$DIR/sdk"
if [ ! -d sdk/build-tools/34.0.0 ] || [ ! -d sdk/platforms/android-34 ]; then
    curl -fsSL -o clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    rm -rf sdk/cmdline-tools && mkdir -p sdk/cmdline-tools
    unzip -q clt.zip -d sdk/cmdline-tools && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest && rm clt.zip
    # `yes` dies of SIGPIPE when sdkmanager is done: not a failure under pipefail.
    { yes || true; } 2>/dev/null | sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
    sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
fi >&2

cat <<EOF
export JAVA_HOME="$DIR/jdk"
export ANDROID_HOME="$DIR/sdk"
export GRADLE_USER_HOME="$DIR/gradle-home"
export PATH="$DIR/jdk/bin:$DIR/gradle/bin:\$PATH"
EOF
