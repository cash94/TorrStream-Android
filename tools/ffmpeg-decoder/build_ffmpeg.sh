#!/bin/bash
# Статические библиотеки FFmpeg под 4 ABI Android для FFmpeg-расширения Media3.
# Замена stock build_ffmpeg.sh из decoder_ffmpeg: тот зовёт обёртки компилятора
# NDK (<triple><api>-clang), а в NDK для Windows это .cmd, которые configure
# FFmpeg запустить не может. Здесь clang вызывается напрямую с --target, так что
# один и тот же скрипт работает на Linux, macOS и в Git Bash/MSYS2 на Windows.
#
# Отличия от stock: включён swscale (видеокадры не в yuv420p переводятся в него),
# libavformat по-прежнему не нужен.
#
#   build_ffmpeg.sh <ffmpeg-src> <ndk> <android-api> <decoder>...
set -eu

FFMPEG_SRC="$1"
NDK_PATH="$2"
ANDROID_API="$3"
shift 3
DECODERS=("$@")

case "$(uname -s)" in
  Linux*) HOST=linux-x86_64 ;;
  Darwin*) HOST=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64 ;;
  *) echo "Неизвестная ОС сборки: $(uname -s)"; exit 1 ;;
esac
TC="$NDK_PATH/toolchains/llvm/prebuilt/$HOST/bin"
[[ -d "$TC" ]] || { echo "Нет тулчейна $TC"; exit 1; }
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

API64=$(( ANDROID_API < 21 ? 21 : ANDROID_API ))

COMMON=(
  --target-os=android
  --enable-cross-compile
  --enable-static
  --disable-shared
  --disable-doc
  --disable-programs
  --disable-everything
  --disable-avdevice
  --disable-avformat
  --enable-swscale
  --disable-postproc
  --disable-avfilter
  --disable-symver
  --enable-swresample
  --disable-v4l2-m2m
  --disable-vulkan
  --disable-mediacodec
  --disable-jni
  --nm="$TC/llvm-nm"
  --ar="$TC/llvm-ar"
  --ranlib="$TC/llvm-ranlib"
  --strip="$TC/llvm-strip"
  --extra-ldexeflags=-pie
)
for d in "${DECODERS[@]}"; do COMMON+=("--enable-decoder=$d"); done

# abi arch cpu triple api доп.флаги-configure...
build_abi() {
  local abi="$1" arch="$2" cpu="$3" triple="$4" api="$5"
  shift 5
  echo "== FFmpeg $abi"
  (
    cd "$FFMPEG_SRC"
    ./configure \
      --libdir="android-libs/$abi" \
      --arch="$arch" \
      --cpu="$cpu" \
      --cc="$TC/clang --target=$triple$api" \
      --cxx="$TC/clang++ --target=$triple$api" \
      "${COMMON[@]}" "$@" > "configure-$abi.log" || { tail -30 ffbuild/config.log; exit 1; }
    make -j"$JOBS" > "make-$abi.log"
    make install-libs > /dev/null
    make clean > /dev/null
  )
}

build_abi armeabi-v7a arm armv7-a armv7a-linux-androideabi "$ANDROID_API" \
  --extra-cflags="-march=armv7-a -mfloat-abi=softfp" \
  --extra-ldflags="-Wl,--fix-cortex-a8"
build_abi arm64-v8a aarch64 armv8-a aarch64-linux-android "$API64"
# x86: ассемблер FFmpeg (nasm) на Android даёт текстовые релокации — как в stock, без него
build_abi x86 x86 i686 i686-linux-android "$ANDROID_API" --disable-asm
build_abi x86_64 x86_64 x86-64 x86_64-linux-android "$API64" --disable-asm
