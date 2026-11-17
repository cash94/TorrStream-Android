/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.Build;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/**
 * FFmpeg video decoder (TorrStream). Based on androidx/media#1591.
 *
 * <p>{@link SimpleDecoder} hands over one input and expects at most one output per call, while
 * FFmpeg decodes with a delay (B-frames, frame threads) and may refuse input until a frame is
 * taken out ({@code EAGAIN}). Refused samples are copied and queued, and are sent before any newer
 * one, so the order never breaks.
 */
@UnstableApi
/* package */ final class ExperimentalFfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  // LINT.IfChange
  private static final int VIDEO_DECODER_SUCCESS = 0;
  private static final int VIDEO_DECODER_NO_FRAME = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;
  private static final int VIDEO_DECODER_AGAIN = -3;
  private static final int VIDEO_DECODER_ERROR_SURFACE = -4;
  // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

  /**
   * The Android emulator (ranchu/goldfish gralloc) overruns the buffer when a YV12 window is locked
   * for the CPU; there frames go to an RGBA window instead, converted in native code.
   */
  private static final boolean RGBA_SURFACE =
      "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE);

  private final String codecName;
  private long nativeContext;
  private volatile @C.VideoOutputMode int outputMode;

  /** Samples FFmpeg refused with EAGAIN, oldest first. */
  private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>();

  private static final class PendingInput {
    final ByteBuffer data;
    final long timeUs;

    PendingInput(ByteBuffer data, long timeUs) {
      this.data = data;
      this.timeUs = timeUs;
    }
  }

  public ExperimentalFfmpegVideoDecoder(
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads,
      Format format)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native library.");
    }
    codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    nativeContext =
        ffmpegInitialize(
            codecName, getExtraData(format.initializationData), threads, format.width,
            format.height);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Nullable
  private static byte[] getExtraData(List<byte[]> initializationData) {
    int size = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      size += initializationData.get(i).length;
    }
    if (size == 0) {
      return null;
    }
    byte[] extraData = new byte[size];
    int offset = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] data = initializationData.get(i);
      System.arraycopy(data, 0, extraData, offset, data.length);
      offset += data.length;
    }
    return extraData;
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    // FFmpeg parsers may read up to AV_INPUT_BUFFER_PADDING_SIZE bytes past the data.
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = ffmpegReset(nativeContext);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
      pendingInputs.clear();
    }

    // Older refused samples first.
    while (!pendingInputs.isEmpty()) {
      PendingInput pending = pendingInputs.peekFirst();
      int result =
          ffmpegSendPacket(nativeContext, pending.data, pending.data.limit(), pending.timeUs);
      if (result == VIDEO_DECODER_AGAIN) {
        break;
      }
      pendingInputs.removeFirst();
      if (result == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegSendPacket error (see logcat)");
      }
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    if (pendingInputs.isEmpty()) {
      int result =
          ffmpegSendPacket(nativeContext, inputData, inputData.limit(), inputBuffer.timeUs);
      if (result == VIDEO_DECODER_AGAIN) {
        pendingInputs.addLast(copy(inputData, inputBuffer.timeUs));
      } else if (result == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegSendPacket error (see logcat)");
      }
    } else {
      pendingInputs.addLast(copy(inputData, inputBuffer.timeUs));
    }

    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
    if (!decodeOnly) {
      outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
    }
    int result = ffmpegReceiveFrame(nativeContext, outputBuffer, decodeOnly);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegReceiveFrame error (see logcat)");
    }
    if (result == VIDEO_DECODER_NO_FRAME) {
      outputBuffer.shouldBeSkipped = true;
    } else {
      outputBuffer.format = inputBuffer.format;
    }
    return null;
  }

  private static PendingInput copy(ByteBuffer data, long timeUs) {
    data.position(0);
    ByteBuffer copy = ByteBuffer.allocateDirect(data.limit());
    copy.put(data);
    copy.flip();
    return new PendingInput(copy, timeUs);
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  /**
   * Renders the output buffer to the surface. Must only be called in {@link
   * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode. A surface that went away drops the frame quietly.
   */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegDecoderException("Invalid output mode.");
    }
    int result = ffmpegRenderFrame(nativeContext, surface, outputBuffer, RGBA_SURFACE);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      throw new FfmpegDecoderException("Buffer render error (see logcat)");
    }
  }

  private native long ffmpegInitialize(
      String codecName, @Nullable byte[] extraData, int threads, int width, int height);

  private native long ffmpegReset(long context);

  private native void ffmpegRelease(long context);

  private native int ffmpegSendPacket(long context, ByteBuffer data, int size, long timeUs);

  private native int ffmpegReceiveFrame(
      long context, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

  private native int ffmpegRenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer outputBuffer, boolean rgba);
}
