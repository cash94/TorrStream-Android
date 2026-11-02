#!/bin/bash
# Сборка FFmpeg-расширения Media3 (lib-decoder-ffmpeg) в app/libs.
#
# Google не публикует это расширение в Maven (нужен свой FFmpeg), а готовое
# от Jellyfin выходит с опозданием на несколько версий Media3. Версия
# расширения обязана совпадать с Media3 приложения (оно наследует внутренние
# классы плеера), поэтому при обновлении media3Version в app/build.gradle
# расширение пересобирается этим скриптом и AAR коммитится.
#
# Нужны: Android SDK с NDK r26b и CMake 3.22.1 (sdkmanager "ndk;26.1.10909125"
# "cmake;3.22.1"), JDK 17, git, make. Сборка FFmpeg на 4 архитектуры —
# 5–15 минут.
#
#   ANDROID_HOME=/путь/к/sdk tools/ffmpeg-decoder/build.sh
set -euo pipefail

MEDIA3_VERSION=1.11.1
FFMPEG_BRANCH=release/6.0
NDK_VERSION=26.1.10909125
# minSdk приложения: библиотеки FFmpeg собираются под этот уровень API
ANDROID_API=23
# Только звук — видео декодирует устройство. Список — всё, что расширение
# умеет передать FFmpeg (FfmpegLibrary.getCodecName), плюс mp1/mp2 и mlp
# (родственник TrueHD).
DECODERS=(aac mp3 mp2 mp1 ac3 eac3 truehd mlp dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw)

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)"
fi
NDK="$SDK/ndk/$NDK_VERSION"
[[ -d "$NDK" ]] || { echo "Нет NDK $NDK_VERSION в $SDK/ndk"; exit 1; }

WORK="$ROOT/tools/ffmpeg-decoder/build"
SRC="$WORK/media3"
rm -rf "$WORK"
mkdir -p "$WORK"

echo "== Media3 $MEDIA3_VERSION: исходники decoder_ffmpeg"
git clone -q --depth 1 --branch "$MEDIA3_VERSION" --filter=blob:none --sparse \
  https://github.com/androidx/media.git "$SRC"
git -C "$SRC" sparse-checkout set libraries/decoder_ffmpeg
MODULE="$SRC/libraries/decoder_ffmpeg/src/main"

echo "== FFmpeg $FFMPEG_BRANCH"
git clone -q --depth 1 --branch "$FFMPEG_BRANCH" https://git.ffmpeg.org/ffmpeg.git "$MODULE/jni/ffmpeg"

echo "== FFmpeg под Android (${DECODERS[*]})"
(cd "$MODULE/jni" && ./build_ffmpeg.sh "$MODULE" "$NDK" linux-x86_64 "$ANDROID_API" "${DECODERS[@]}")

echo "== AAR"
"$ROOT/gradlew" --no-daemon -q -p "$ROOT/tools/ffmpeg-decoder/project" \
  -Pmedia3Version="$MEDIA3_VERSION" -PmoduleDir="$MODULE" -PndkVersion="$NDK_VERSION" \
  assembleRelease

OUT="$ROOT/app/libs/lib-decoder-ffmpeg-$MEDIA3_VERSION.aar"
rm -f "$ROOT"/app/libs/lib-decoder-ffmpeg-*.aar
cp "$ROOT/tools/ffmpeg-decoder/project/build/outputs/aar/project-release.aar" "$OUT"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
