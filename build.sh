#!/usr/bin/env bash
# Wraps ./gradlew with the toolchain this project was set up against.
# Usage: ./build.sh [gradle tasks...]   (defaults to assembleDebug)
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")"
./gradlew "${@:-assembleDebug}"
