#!/bin/bash
# Сборка AV1-расширения Media3 (lib-decoder-av1: Libdav1dVideoRenderer на dav1d)
# в app/libs.
#
# Как и FFmpeg-расширение (../ffmpeg-decoder/build.sh), в Maven его нет: dav1d
# собирается под 4 ABI, модуль decoder_av1 берётся из исходников Media3 той же
# версии, что и в app/build.gradle. Нужен там, где у устройства нет AV1 вовсе
# (Android до 10 без аппаратного) — программно до 1080p.
#
# Нужны: Android SDK с NDK r26b и CMake 3.22.1, JDK 17, git, meson и ninja
# (pip install meson ninja), nasm 2.14+ (ассемблер dav1d для x86). Работает на
# Linux и в Git Bash на Windows.
#
#   ANDROID_HOME=/путь/к/sdk tools/av1-decoder/build.sh
set -euo pipefail

MEDIA3_VERSION=1.11.1
DAV1D_TAG=1.5.1
CPU_FEATURES_TAG=v0.10.1
NDK_VERSION=26.1.10909125
ANDROID_API=23

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/av1-decoder"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | sed 's/\\:/:/; s/\\\\/\//g' || true)"
fi
NDK="$SDK/ndk/$NDK_VERSION"
[[ -d "$NDK" ]] || { echo "Нет NDK $NDK_VERSION в $SDK/ndk"; exit 1; }
export ANDROID_HOME="$SDK"  # для Gradle в project/
case "$(uname -s)" in
  Linux*) HOST=linux-x86_64; EXE= ;;
  Darwin*) HOST=darwin-x86_64; EXE= ;;
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXE=.exe ;;
  *) echo "Неизвестная ОС сборки: $(uname -s)"; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST/bin"
for tool in meson ninja nasm; do
  command -v "$tool" > /dev/null || { echo "Нет $tool в PATH"; exit 1; }
done
NASM="$(command -v nasm)"
if [[ "$HOST" == windows-x86_64 ]]; then
  TC="$(cygpath -m "$TC")"
  NASM="$(cygpath -m "$NASM")"
fi

WORK="$HERE/build"
SRC="$WORK/media3"
MODULE="$SRC/libraries/decoder_av1/src/main"
GIT=(git -c core.autocrlf=false)
rm -rf "$WORK"
mkdir -p "$WORK"

echo "== Media3 $MEDIA3_VERSION: исходники decoder_av1"
"${GIT[@]}" clone -q --depth 1 --branch "$MEDIA3_VERSION" --filter=blob:none --sparse \
  https://github.com/androidx/media.git "$SRC"
"${GIT[@]}" -C "$SRC" sparse-checkout set libraries/decoder_av1

echo "== dav1d $DAV1D_TAG, cpu_features $CPU_FEATURES_TAG"
"${GIT[@]}" clone -q --depth 1 --branch "$DAV1D_TAG" https://code.videolan.org/videolan/dav1d.git "$MODULE/jni/dav1d"
"${GIT[@]}" clone -q --depth 1 --branch "$CPU_FEATURES_TAG" https://github.com/google/cpu_features "$MODULE/jni/cpu_features"

# abi meson-cpu-family meson-cpu triple api
build_abi() {
  local abi="$1" family="$2" cpu="$3" triple="$4" api="$5"
  echo "== dav1d $abi"
  local dir="$WORK/dav1d-$abi"
  mkdir -p "$dir"
  # Свой cross-файл: clang с --target вместо обёрток NDK (на Windows это .cmd)
  cat > "$dir/cross.meson" <<EOF
[binaries]
c = ['$TC/clang$EXE', '--target=$triple$api']
cpp = ['$TC/clang++$EXE', '--target=$triple$api']
ar = '$TC/llvm-ar$EXE'
strip = '$TC/llvm-strip$EXE'
nasm = '$NASM'

[host_machine]
system = 'android'
cpu_family = '$family'
cpu = '$cpu'
endian = 'little'
EOF
  meson setup "$dir/out" "$MODULE/jni/dav1d" --cross-file="$dir/cross.meson" \
    --default-library=static --buildtype=release \
    -Denable_tools=false -Denable_tests=false -Denable_examples=false > "$dir/setup.log"
  ninja -C "$dir/out" > "$dir/ninja.log"
  mkdir -p "$MODULE/jni/nativelib/$abi"
  cp "$dir/out/src/libdav1d.a" "$MODULE/jni/nativelib/$abi/"
}
API64=$(( ANDROID_API < 21 ? 21 : ANDROID_API ))
build_abi armeabi-v7a arm armv7 armv7a-linux-androideabi "$ANDROID_API"
build_abi arm64-v8a aarch64 aarch64 aarch64-linux-android "$API64"
build_abi x86 x86 i686 i686-linux-android "$ANDROID_API"
build_abi x86_64 x86_64 x86_64 x86_64-linux-android "$API64"

echo "== AAR"
"$ROOT/gradlew" --no-daemon -q -p "$HERE/project" \
  -Pmedia3Version="$MEDIA3_VERSION" -PmoduleDir="$MODULE" -PndkVersion="$NDK_VERSION" \
  assembleRelease

OUT="$ROOT/app/libs/lib-decoder-av1-$MEDIA3_VERSION.aar"
rm -f "$ROOT"/app/libs/lib-decoder-av1-*.aar
cp "$HERE/project/build/outputs/aar/project-release.aar" "$OUT"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
