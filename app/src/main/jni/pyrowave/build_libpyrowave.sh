#!/bin/sh
# Rebuilds the prebuilt lib/arm64-v8a/libpyrowave-shared.so, which app/build.gradle packages, and
# pyrowave.h in this folder, from a PyroWave checkout (https://github.com/Themaister/pyrowave, MIT).
# Adapted from Moonlight X's script of the same name (GPL-3.0). The PyroWave renderer
# (../pyrowave_renderer) loads the library at runtime, so the codec just isn't offered where it's
# missing.
#
#   PYROWAVE_DIR  PyroWave checkout, with Granite checked out by its checkout_granite.sh (run it
#                 again after pulling PyroWave, which pins the Granite commit it needs)
#                 (default: a pyrowave folder next to this repository's folder)
#   ANDROID_NDK   NDK to build with (default: the version app/build.gradle uses, in ANDROID_HOME)
#   CMAKE, NINJA  cmake and ninja (default: the SDK's CMake 3.22.1, then the PATH)
#
# Only arm64-v8a is built: PyroWave needs Vulkan 1.1 with extensions that no 32-bit Android
# device we'd stream to has. The pinned build is PyroWave 21f099d with Granite 53090429 (SOURCE),
# the same as Moonlight X v0.6.9.
set -e

cd "$(dirname "$0")"
HERE=$(pwd)
REPO=$(cd ../../../../.. && pwd)

PYROWAVE_DIR=${PYROWAVE_DIR:-$REPO/../pyrowave}
PYROWAVE_DIR=$(cd "$PYROWAVE_DIR" && pwd)

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$REPO/local.properties" 2>/dev/null)}}
NDK_VERSION=$(sed -n 's/.*ndkVersion "\(.*\)".*/\1/p' "$REPO/app/build.gradle")
ANDROID_NDK=${ANDROID_NDK:-$SDK/ndk/$NDK_VERSION}
if [ ! -d "$ANDROID_NDK" ]; then
    echo "NDK not found at $ANDROID_NDK; set ANDROID_NDK" >&2
    exit 1
fi
CMAKE=${CMAKE:-$( [ -x "$SDK/cmake/3.22.1/bin/cmake" ] && echo "$SDK/cmake/3.22.1/bin/cmake" || echo cmake)}
NINJA=${NINJA:-$( [ -x "$SDK/cmake/3.22.1/bin/ninja" ] && echo "$SDK/cmake/3.22.1/bin/ninja" || echo ninja)}
STRIP=$(ls "$ANDROID_NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)

BUILD=$PYROWAVE_DIR/build-android-arm64
"$CMAKE" -S "$PYROWAVE_DIR" -B "$BUILD" -G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release
"$NINJA" -C "$BUILD" pyrowave-shared

LIBS=$HERE/lib/arm64-v8a
mkdir -p "$LIBS"
"$STRIP" --strip-unneeded -o "$LIBS/libpyrowave-shared.so" "$BUILD/libpyrowave-shared.so"
cp "$PYROWAVE_DIR/pyrowave.h" pyrowave.h
cp "$PYROWAVE_DIR/LICENSE" LICENSE

# Which PyroWave (and Granite) these came from
{
    (cd "$PYROWAVE_DIR" && git log -1 --format='%H %s')
    (cd "$PYROWAVE_DIR/Granite" && git log -1 --format='Granite %H %s')
} > SOURCE

echo "Updated $LIBS and $HERE from $PYROWAVE_DIR ($(cat SOURCE))"
