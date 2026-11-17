/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

/**
 * Decodes and renders video using FFmpeg (TorrStream: the Media3 1.11.1 class is a stub; this is
 * a working one after androidx/media#1591). Only the decoders built into the native library are
 * reported as supported, see {@code tools/ffmpeg-decoder/build.sh}.
 */
@UnstableApi
public final class ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "ExperimentalFfmpegVideoRenderer";

  private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;

  /* Default size based on 720p resolution video compressed by a factor of two. */
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;

  private final int threads;
  private final int numInputBuffers;
  private final int numOutputBuffers;
  private final int maxWidth;
  private final int maxHeight;

  @Nullable private ExperimentalFfmpegVideoDecoder decoder;

  /** Constructor used by DefaultRenderersFactory: any frame size, a thread per core. */
  public ExperimentalFfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        /* threads= */ Runtime.getRuntime().availableProcessors(),
        /* maxWidth= */ Integer.MAX_VALUE,
        /* maxHeight= */ Integer.MAX_VALUE);
  }

  /**
   * @param threads Decoding threads.
   * @param maxWidth Wider video is reported as unsupported (software decoding of it would be a
   *     slide show), so that the player does not pick this renderer for it.
   * @param maxHeight Same for the height.
   */
  public ExperimentalFfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int maxWidth,
      int maxHeight) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = Math.max(threads, 1);
    this.numInputBuffers = DEFAULT_NUM_OF_INPUT_BUFFERS;
    this.numOutputBuffers = DEFAULT_NUM_OF_OUTPUT_BUFFERS;
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public final @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    String mimeType = format.sampleMimeType;
    if (!FfmpegLibrary.isAvailable() || !MimeTypes.isVideo(mimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    } else if (!FfmpegLibrary.supportsFormat(mimeType)
        || format.width > maxWidth
        || format.height > maxHeight) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    } else if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    } else {
      return RendererCapabilities.create(
          C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED);
    }
  }

  @Override
  protected ExperimentalFfmpegVideoDecoder createDecoder(
      Format format, @Nullable CryptoConfig cryptoConfig) throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    ExperimentalFfmpegVideoDecoder decoder =
        new ExperimentalFfmpegVideoDecoder(
            numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, format);
    this.decoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    try {
      if (decoder == null) {
        throw new FfmpegDecoderException("Failed to render output buffer to surface.");
      }
      decoder.renderToSurface(outputBuffer, surface);
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    // The native decoder cannot take new extradata or dimensions: always a new one.
    return new DecoderReuseEvaluation(
        decoderName, oldFormat, newFormat, REUSE_RESULT_NO, DISCARD_REASON_REUSE_NOT_IMPLEMENTED);
  }
}
