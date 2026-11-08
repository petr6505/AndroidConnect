#!/usr/bin/env bash
# Builds both installables into dist/:
#   dist/androidconnect_<version>_all.deb   (Ubuntu laptop)
#   dist/AndroidConnect-<version>.apk       (Android tablet)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$ROOT/dist" && mkdir -p "$ROOT/dist"

"$ROOT/linux/packaging/build-deb.sh" "$ROOT/dist"

(cd "$ROOT/android" && ./gradlew -q assembleRelease)
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$ROOT/android/app/build.gradle.kts")"
cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/AndroidConnect-$VERSION.apk"
ls -lh "$ROOT/dist"
