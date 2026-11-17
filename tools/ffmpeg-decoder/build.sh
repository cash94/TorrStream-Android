#!/bin/bash
# Сборка FFmpeg-расширения Media3 (lib-decoder-ffmpeg) в app/libs.
#
# Google не публикует это расширение в Maven (нужен свой FFmpeg), а готовое
# от Jellyfin выходит с опозданием на несколько версий Media3. Версия
# расширения обязана совпадать с Media3 приложения (оно наследует внутренние
# классы плеера), поэтому при обновлении media3Version в app/build.gradle
# расширение пересобирается этим скриптом и AAR коммитится.
#
# Поверх исходников decoder_ffmpeg кладётся overlay/: программный видеодекодер
# (ExperimentalFfmpegVideoRenderer — в Media3 1.11.1 он заглушка; реализация по
# мотивам androidx/media#1591, без libyuv), сопоставление mime видео в
# FfmpegLibrary и JNI. При обновлении Media3 overlay сверять с новыми исходниками.
#
# Нужны: Android SDK с NDK r26b и CMake 3.22.1 (sdkmanager "ndk;26.1.10909125"
# "cmake;3.22.1"), JDK 17, git, make. Работает на Linux и в Git Bash на Windows
# (make — make.exe из пакета MSYS2 в PATH). Сборка FFmpeg на 4 архитектуры —
# 5–15 минут на Linux, около получаса на Windows.
#
#   ANDROID_HOME=/путь/к/sdk tools/ffmpeg-decoder/build.sh
#   REUSE_FFMPEG=1 ... — не пересобирать FFmpeg, если он уже собран в build/
set -euo pipefail

MEDIA3_VERSION=1.11.1
FFMPEG_BRANCH=release/6.0
NDK_VERSION=26.1.10909125
# minSdk приложения: библиотеки FFmpeg собираются под этот уровень API
ANDROID_API=23
# Звук: всё, что расширение умеет передать FFmpeg (FfmpegLibrary.getCodecName),
# плюс mp1/mp2 и mlp (родственник TrueHD).
AUDIO_DECODERS=(aac mp3 mp2 mp1 ac3 eac3 truehd mlp dca vorbis opus amrnb amrwb flac alac pcm_mulaw pcm_alaw)
# Видео: только то, что без аппаратного декодера не играет ничто другое
# (MPEG-4 Part 2 у программного декодера Android — до 352×288). H.264/HEVC/VP9
# здесь нет: у Android свои программные. AV1 — отдельным модулем (dav1d).
VIDEO_DECODERS=(mpeg4 h263 msmpeg4v1 msmpeg4v2 msmpeg4v3 vc1 wmv3 mpeg1video mpeg2video mjpeg)

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/tools/ffmpeg-decoder"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" ]]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | sed 's/\\:/:/; s/\\\\/\//g' || true)"
fi
NDK="$SDK/ndk/$NDK_VERSION"
[[ -d "$NDK" ]] || { echo "Нет NDK $NDK_VERSION в $SDK/ndk"; exit 1; }
export ANDROID_HOME="$SDK"  # для Gradle в project/
# configure FFmpeg не переносит обратные косые черты в TMPDIR (Windows)
export TMPDIR=/tmp

WORK="$HERE/build"
SRC="$WORK/media3"
MODULE="$SRC/libraries/decoder_ffmpeg/src/main"
FFMPEG="$MODULE/jni/ffmpeg"
# autocrlf=false: на Windows git иначе портит configure и скрипты переводами строк
GIT=(git -c core.autocrlf=false)

if [[ "${REUSE_FFMPEG:-}" == 1 && -f "$FFMPEG/android-libs/x86_64/libavcodec.a" ]]; then
  echo "== FFmpeg уже собран, беру из $FFMPEG"
  "${GIT[@]}" -C "$SRC" checkout -q -- libraries/decoder_ffmpeg
else
  rm -rf "$WORK"
  mkdir -p "$WORK"

  echo "== Media3 $MEDIA3_VERSION: исходники decoder_ffmpeg"
  "${GIT[@]}" clone -q --depth 1 --branch "$MEDIA3_VERSION" --filter=blob:none --sparse \
    https://github.com/androidx/media.git "$SRC"
  "${GIT[@]}" -C "$SRC" sparse-checkout set libraries/decoder_ffmpeg

  echo "== FFmpeg $FFMPEG_BRANCH"
  "${GIT[@]}" clone -q --depth 1 --branch "$FFMPEG_BRANCH" https://git.ffmpeg.org/ffmpeg.git "$FFMPEG"

  echo "== FFmpeg под Android (${AUDIO_DECODERS[*]} ${VIDEO_DECODERS[*]})"
  bash "$HERE/build_ffmpeg.sh" "$FFMPEG" "$NDK" "$ANDROID_API" "${AUDIO_DECODERS[@]}" "${VIDEO_DECODERS[@]}"
fi

echo "== overlay"
cp -r "$HERE/overlay/." "$MODULE/"
cp "$HERE/overlay-proguard-rules.txt" "$MODULE/../../proguard-rules.txt"

echo "== AAR"
"$ROOT/gradlew" --no-daemon -q -p "$HERE/project" \
  -Pmedia3Version="$MEDIA3_VERSION" -PmoduleDir="$MODULE" -PndkVersion="$NDK_VERSION" \
  assembleRelease

OUT="$ROOT/app/libs/lib-decoder-ffmpeg-$MEDIA3_VERSION.aar"
rm -f "$ROOT"/app/libs/lib-decoder-ffmpeg-*.aar
cp "$HERE/project/build/outputs/aar/project-release.aar" "$OUT"
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
