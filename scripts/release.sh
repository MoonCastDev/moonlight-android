#!/bin/sh
# Builds signed release APKs of both apps and checks their signature.
# Usage: MOONCAST_KEYSTORE=/path/to/keystore.properties scripts/release.sh
# (or keystore.properties next to settings.gradle). Writes out/MoonCast-<version>.apk and out/MoonCastTV-<version>.apk.
set -e
cd "$(dirname "$0")/.."
PROPS=${MOONCAST_KEYSTORE:-keystore.properties}
[ -f "$PROPS" ] || { echo "release.sh: no keystore.properties (set MOONCAST_KEYSTORE)" >&2; exit 1; }
export MOONCAST_KEYSTORE=$(cd "$(dirname "$PROPS")" && pwd)/$(basename "$PROPS")

# apksigner runs on Java too
[ -n "$JAVA_HOME" ] && PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
[ -n "$APKSIGNER" ] || { echo "release.sh: apksigner not found under $SDK/build-tools" >&2; exit 1; }

./gradlew --no-daemon assembleNonRootHandheldRelease assembleNonRootTvRelease
VERSION=$(sed -n 's/^ *versionName "\(.*\)"/\1/p' app/build.gradle | head -1)
mkdir -p out
cp app/build/outputs/apk/nonRootHandheld/release/app-nonRoot-handheld-release.apk "out/MoonCast-$VERSION.apk"
cp app/build/outputs/apk/nonRootTv/release/app-nonRoot-tv-release.apk "out/MoonCastTV-$VERSION.apk"
for a in "out/MoonCast-$VERSION.apk" "out/MoonCastTV-$VERSION.apk"; do
    echo "== $a"
    "$APKSIGNER" verify --print-certs "$a" | grep -E "Signer #1 certificate (DN|SHA-256)"
done
