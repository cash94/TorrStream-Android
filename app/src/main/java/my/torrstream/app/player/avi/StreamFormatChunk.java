/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package my.torrstream.app.player.avi;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;

/** Holds the {@link Format} information contained in an STRF chunk. */
@OptIn(markerClass = UnstableApi.class)
/* package */ final class StreamFormatChunk implements AviChunk {
  private static final String TAG = "StreamFormatChunk";

  @Nullable
  public static AviChunk parseFrom(int trackType, ParsableByteArray body) {
    if (trackType == C.TRACK_TYPE_VIDEO) {
      return parseBitmapInfoHeader(body);
    } else if (trackType == C.TRACK_TYPE_AUDIO) {
      return parseWaveFormatEx(body);
    } else {
      Log.w(
          TAG,
          "Ignoring strf box for unsupported track type: " + Util.getTrackTypeString(trackType));
      return null;
    }
  }

  public final Format format;

  /** biCompression видеодорожки (0 — у аудио). */
  public final int compression;

  public StreamFormatChunk(Format format) {
    this(format, 0);
  }

  public StreamFormatChunk(Format format, int compression) {
    this.format = format;
    this.compression = compression;
  }

  @Override
  public int getType() {
    return RobustAviExtractor.FOURCC_strf;
  }

  @Nullable
  private static AviChunk parseBitmapInfoHeader(ParsableByteArray body) {
    body.skipBytes(4); // biSize.
    int width = body.readLittleEndianInt();
    int height = body.readLittleEndianInt();
    body.skipBytes(4); // biPlanes (2 bytes), biBitCount (2 bytes).
    int compression = body.readLittleEndianInt();
    // Незнакомый biCompression не отбрасываем сразу: RobustAviExtractor попробует fccHandler из strh.
    String mimeType = getMimeTypeFromCompression(compression);
    Format.Builder formatBuilder = new Format.Builder();
    formatBuilder.setWidth(width).setHeight(height).setSampleMimeType(mimeType);
    return new StreamFormatChunk(formatBuilder.build(), compression);
  }

  // Syntax defined by the WAVEFORMATEX structure. See
  // https://docs.microsoft.com/en-us/previous-versions/dd757713(v=vs.85).
  @Nullable
  private static AviChunk parseWaveFormatEx(ParsableByteArray body) {
    int formatTag = body.readLittleEndianUnsignedShort();
    @Nullable String mimeType = getMimeTypeFromTag(formatTag);
    if (mimeType == null) {
      Log.w(TAG, "Ignoring track with unsupported format tag " + formatTag);
      return null;
    }
    int channelCount = body.readLittleEndianUnsignedShort();
    int samplesPerSecond = body.readLittleEndianInt();
    body.skipBytes(6); // averageBytesPerSecond (4 bytes), nBlockAlign (2 bytes).
    int bitsPerSample = body.readLittleEndianUnsignedShort();
    int pcmEncoding = Util.getPcmEncoding(bitsPerSample);
    int cbSize = body.bytesLeft() > 0 ? body.readLittleEndianUnsignedShort() : 0;
    Format.Builder formatBuilder = new Format.Builder();
    formatBuilder
        .setSampleMimeType(mimeType)
        .setChannelCount(channelCount)
        .setSampleRate(samplesPerSecond);
    if (mimeType.equals(MimeTypes.AUDIO_RAW) && pcmEncoding != C.ENCODING_INVALID) {
      formatBuilder.setPcmEncoding(pcmEncoding);
    }
    if (mimeType.equals(MimeTypes.AUDIO_AAC) && cbSize > 0) {
      byte[] codecData = new byte[cbSize];
      body.readBytes(codecData, /* offset= */ 0, codecData.length);
      formatBuilder.setInitializationData(ImmutableList.of(codecData));
    }
    return new StreamFormatChunk(formatBuilder.build());
  }

  @Nullable
  private static String getMimeTypeFromTag(int tag) {
    switch (tag) {
      case 0x1: // WAVE_FORMAT_PCM
        return MimeTypes.AUDIO_RAW;
      case 0x55: // WAVE_FORMAT_MPEGLAYER3
        return MimeTypes.AUDIO_MPEG;
      case 0xff: // WAVE_FORMAT_AAC
        return MimeTypes.AUDIO_AAC;
      case 0x2000: // WAVE_FORMAT_DVM - AC3
        return MimeTypes.AUDIO_AC3;
      case 0x2001: // WAVE_FORMAT_DTS2
        return MimeTypes.AUDIO_DTS;
      default:
        return null;
    }
  }

  /**
   * MIME видео по fourcc, без учёта регистра (как avpriv_toupper4 у FFmpeg). Media3 знает только
   * несколько написаний; в раздачах встречаются x264/X264/h264, DAVC, XviD, MP4V, HEVC и другие.
   */
  @Nullable
  /* package */ static String getMimeTypeFromCompression(int compression) {
    switch (toUpper(compression)) {
      case 0x3234504d: // MP42
        return MimeTypes.VIDEO_MP42;
      case 0x3334504d: // MP43
      case 0x33564944: // DIV3 (DivX 3 = MS-MPEG4 v3, декодирует FFmpeg msmpeg4)
      case 0x34564944: // DIV4
      case 0x35564944: // DIV5
      case 0x36564944: // DIV6
      case 0x3347504d: // MPG3
      case 0x31345041: // AP41
      case 0x314c4f43: // COL1
      case 0x4956414e: // NAVI
        return MimeTypes.VIDEO_MP43;
      case 0x34363248: // H264
      case 0x34363258: // X264
      case 0x31435641: // AVC1
      case 0x43564144: // DAVC
      case 0x48535356: // VSSH
      case 0x34363256: // V264
      case 0x34363251: // Q264
      case 0x43564147: // GAVC
        return MimeTypes.VIDEO_H264;
      case 0x43564548: // HEVC
      case 0x35363248: // H265
      case 0x35363258: // X265
      case 0x31564548: // HEV1
      case 0x31435648: // HVC1
        return MimeTypes.VIDEO_H265;
      case 0x44495633: // 3VID
      case 0x32564933: // 3IV2
      case 0x58564944: // DIVX
      case 0x30355844: // DX50
      case 0x34504d46: // FMP4
      case 0x44495658: // XVID
      case 0x58495658: // XVIX
      case 0x5634504d: // MP4V
      case 0x5334504d: // MP4S
      case 0x3253344d: // M4S2
      case 0x34504d55: // UMP4
      case 0x34504d52: // RMP4
      case 0x34504d53: // SMP4
      case 0x4d475844: // DXGM
      case 0x34584448: // HDX4
      case 0x56344d44: // DM4V
      case 0x305a4c42: // BLZ0
      case 0x53444646: // FFDS
      case 0x57465646: // FVFW
      case 0x46315657: // WV1F
      case 0x47444553: // SEDG
        return MimeTypes.VIDEO_MP4V;
      case 0x47504a4d: // MJPG
        return MimeTypes.VIDEO_MJPEG;
      default:
        return null;
    }
  }

  private static int toUpper(int fourcc) {
    int result = 0;
    for (int shift = 0; shift < 32; shift += 8) {
      int c = (fourcc >>> shift) & 0xFF;
      if (c >= 'a' && c <= 'z') {
        c -= 'a' - 'A';
      }
      result |= c << shift;
    }
    return result;
  }

}
