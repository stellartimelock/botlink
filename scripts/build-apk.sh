#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -d "$HOME/android-sdk" ]]; then
    export ANDROID_HOME="$HOME/android-sdk"
    export ANDROID_SDK_ROOT="$HOME/android-sdk"
  fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "Set ANDROID_HOME to your Android SDK." >&2
  exit 1
fi
if [[ ! -f local.properties ]]; then
  echo "sdk.dir=${ANDROID_HOME}" > local.properties
fi
chmod +x ./gradlew
./gradlew :app:assembleRelease
mkdir -p "$root/artifacts"
apk="$(ls "$root"/app/build/outputs/apk/release/app-release*.apk | head -1)"
cp "$apk" "$root/artifacts/BotLink-0.3.3.apk"
echo
echo "APK: $apk"
echo "Copy: $root/artifacts/BotLink-0.3.3.apk"
echo "Release is unsigned unless BOTLINK_KEYSTORE is set (see README)."
