#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
JNI_DIR="$SCRIPT_DIR/src/main/jni"
FFMPEG_VERSION="9.0.2"
FFMPEG_SHA256="8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"
LIBYUV_COMMIT="b25fa8992056629c99d7815516e7be6b82509897"

SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to an installed Android SDK." >&2
    exit 1
fi

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    NDK_DIR="$ANDROID_NDK_ROOT"
else
NDK_DIR="$(find "$SDK_DIR/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)"
fi
if [[ -z "${NDK_DIR:-}" || ! -d "$NDK_DIR" ]]; then
    echo "Install the Android NDK or set ANDROID_NDK_HOME." >&2
    exit 1
fi
ANDROID_NDK_VERSION="$(basename -- "$NDK_DIR")"
export ANDROID_NDK_VERSION

case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)
        if [[ -d "$NDK_DIR/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
            HOST_PLATFORM="darwin-arm64"
        else
            HOST_PLATFORM="darwin-x86_64"
        fi
        ;;
    Darwin-x86_64) HOST_PLATFORM="darwin-x86_64" ;;
    Linux-x86_64) HOST_PLATFORM="linux-x86_64" ;;
    Linux-aarch64|Linux-arm64) HOST_PLATFORM="linux-aarch64" ;;
    *) echo "Unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

if [[ -d "$SDK_DIR/cmake/3.22.1/bin" ]]; then
    PATH="$SDK_DIR/cmake/3.22.1/bin:$PATH"
    export PATH
fi

FFMPEG_SOURCE="$JNI_DIR/ffmpeg"
LIBYUV_SOURCE="$JNI_DIR/libyuv"
ARCHIVE="$SCRIPT_DIR/third_party/ffmpeg-${FFMPEG_VERSION}.tar.xz"

echo "Verifying FFmpeg ${FFMPEG_VERSION} source archive"
if command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$FFMPEG_SHA256" "$ARCHIVE" | shasum -a 256 -c -
elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$FFMPEG_SHA256" "$ARCHIVE" | sha256sum -c -
else
    echo "Install shasum or sha256sum to verify the FFmpeg source archive." >&2
    exit 1
fi
rm -rf "$FFMPEG_SOURCE"
mkdir -p "$FFMPEG_SOURCE"
tar -xJf "$ARCHIVE" --strip-components=1 -C "$FFMPEG_SOURCE"

rm -rf "$LIBYUV_SOURCE"
git init -q "$LIBYUV_SOURCE"
git -C "$LIBYUV_SOURCE" remote add origin https://chromium.googlesource.com/libyuv/libyuv
git -C "$LIBYUV_SOURCE" fetch --depth=1 origin "$LIBYUV_COMMIT"
git -C "$LIBYUV_SOURCE" checkout --detach -q FETCH_HEAD

"$JNI_DIR/build_ffmpeg.sh" "$SCRIPT_DIR/src/main" "$NDK_DIR" "$HOST_PLATFORM" 23 \
    h264 hevc vp8 vp9 av1 mpeg2video mpeg4 prores aac mp3 opus flac vorbis ac3 eac3
"$JNI_DIR/build_yuv.sh" "$SCRIPT_DIR/src/main" "$NDK_DIR" 23

# Use the Android Gradle Plugin's standard jniLibs directory. Keeping imported
# shared objects out of CMake's source-set DSL also avoids legacy source-set API
# casts in AGP 9 while retaining normal per-ABI packaging.
JNI_LIBS_DIR="$SCRIPT_DIR/src/main/jniLibs"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    mkdir -p "$JNI_LIBS_DIR/$abi"
    cp "$JNI_DIR/ffmpeg/android-libs/$abi"/*.so "$JNI_LIBS_DIR/$abi/"
    cp "$JNI_DIR/libyuv/android-libs/$abi"/*.so "$JNI_LIBS_DIR/$abi/"
done

cat > "$SCRIPT_DIR/src/main/assets/nexa/ffmpeg-build.txt" <<EOF
FFmpeg ${FFMPEG_VERSION}
Source archive SHA-256: ${FFMPEG_SHA256}
Source: https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz
Configure: --disable-gpl --disable-version3 --disable-nonfree --disable-autodetect --disable-avformat
Enabled decoders: h264 hevc vp8 vp9 av1 mpeg2video mpeg4 prores aac mp3 opus flac vorbis ac3 eac3
Verified configuration for each ABI: CONFIG_GPL=0 CONFIG_GPLV3=0 CONFIG_VERSION3=0 CONFIG_NONFREE=0 in config.h
Android NDK version: ${ANDROID_NDK_VERSION}
libyuv commit: ${LIBYUV_COMMIT}
The libraries are separate shared objects; see the LGPL and BSD notices in assets/nexa/licenses.
EOF

cd "$SCRIPT_DIR"
./gradlew --no-daemon -PnexaNdkVersion="$ANDROID_NDK_VERSION" assembleRelease

AAR="$SCRIPT_DIR/build/outputs/aar/ffmpeg-decoder-release.aar"
DESTINATION="$PLUGIN_DIR/android/libs/nexa-media3-ffmpeg-decoder-1.11.1.aar"
mkdir -p "$(dirname -- "$DESTINATION")"
cp "$AAR" "$DESTINATION"

VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "$VERIFY_DIR"' EXIT
unzip -q "$DESTINATION" -d "$VERIFY_DIR"
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    for library in libffmpegJNI.so libavcodec.so libavutil.so libswscale.so libswresample.so libyuv.so; do
        test -f "$VERIFY_DIR/jni/$abi/$library" || {
            echo "AAR is missing jni/$abi/$library" >&2
            exit 1
        }
    done
    test ! -f "$VERIFY_DIR/jni/$abi/libavformat.so" || {
        echo "AAR contains unused libavformat.so for $abi" >&2
        exit 1
    }
done
jar tf "$VERIFY_DIR/classes.jar" | rg -q 'androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoRenderer.class'
for asset in \
    assets/nexa/ffmpeg-build.txt \
    assets/nexa/licenses/FFmpeg-LGPL-2.1.txt \
    assets/nexa/licenses/AndroidX-Apache-2.0.txt \
    assets/nexa/licenses/libyuv-BSD-3-Clause.txt; do
    test -f "$VERIFY_DIR/$asset" || {
        echo "AAR is missing $asset" >&2
        exit 1
    }
done
grep -Fq 'CONFIG_GPL=0 CONFIG_GPLV3=0 CONFIG_VERSION3=0 CONFIG_NONFREE=0' \
    "$VERIFY_DIR/assets/nexa/ffmpeg-build.txt"
echo "Built LGPL-only Media3 FFmpeg decoder AAR: $DESTINATION"
