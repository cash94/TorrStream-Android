/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <new>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "ffmpeg_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder.java)

static jmethodID growOutputBufferMethod;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified codec, passing the
 * provided extraData as initialization data for the decoder if it is non-NULL.
 * Returns the created context.
 */
AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext* context);

jstring ffmpegGetVersion(JNIEnv* env, jobject thiz) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

jint ffmpegGetInputBufferPaddingSize(JNIEnv* env, jobject thiz) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

jboolean ffmpegHasDecoder(JNIEnv* env, jobject thiz, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

jlong ffmpegInitialize(JNIEnv* env, jobject thiz, jstring codecName,
                       jbyteArray extraData, jboolean outputFloat,
                       jint rawSampleRate, jint rawChannelCount) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                              rawChannelCount);
}

jint ffmpegDecode(JNIEnv* env, jobject thiz, jlong context, jobject inputData,
                  jint inputSize, jobject decoderOutputBuffer,
                  jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext*)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

jint ffmpegGetChannelCount(JNIEnv* env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->ch_layout.nb_channels;
}

jint ffmpegGetSampleRate(JNIEnv* env, jobject thiz, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->sample_rate;
}

jlong ffmpegReset(JNIEnv* env, jobject thiz, jlong jContext,
                  jbyteArray extraData) {
  AVCodecContext* context = (AVCodecContext*)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

void ffmpegRelease(JNIEnv* env, jobject thiz, jlong context) {
  if (context) {
    releaseContext((AVCodecContext*)context);
  }
}

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t*)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)context->extradata);
  }
  if (context->codec_id == AV_CODEC_ID_PCM_MULAW ||
      context->codec_id == AV_CODEC_ID_PCM_ALAW) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  return context;
}

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer) {
  int result = 0;
  // Queue input data.
  result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  // Dequeue output data until it runs out.
  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    // Resample output.
    AVSampleFormat sampleFormat = context->sample_fmt;
    int channelCount = context->ch_layout.nb_channels;
    int sampleRate = context->sample_rate;
    int sampleCount = frame->nb_samples;
    int dataSize = av_samples_get_buffer_size(NULL, channelCount, sampleCount,
                                              sampleFormat, 1);
    SwrContext* resampleContext = static_cast<SwrContext*>(context->opaque);
    if (!resampleContext) {
      result =
          swr_alloc_set_opts2(&resampleContext,             // ps
                              &context->ch_layout,          // out_ch_layout
                              context->request_sample_fmt,  // out_sample_fmt
                              sampleRate,                   // out_sample_rate
                              &context->ch_layout,          // in_ch_layout
                              sampleFormat,                 // in_sample_fmt
                              sampleRate,                   // in_sample_rate
                              0,                            // log_offset
                              NULL                          // log_ctx
          );
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      result = swr_init(resampleContext);
      if (result < 0) {
        logError("swr_init", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      context->opaque = resampleContext;
    }

    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    int bufferOutSize = outSampleSize * channelCount * outSamples;
    if (outSize + bufferOutSize > outputSize) {
      LOGD(
          "Output buffer size (%d) too small for output data (%d), "
          "reallocating buffer.",
          outputSize, outSize + bufferOutSize);
      outputSize = outSize + bufferOutSize;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    result = swr_convert(resampleContext, &outputBuffer, bufferOutSize,
                         (const uint8_t**)frame->data, frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    outputBuffer += bufferOutSize;
    outSize += bufferOutSize;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext* context) {
  if (!context) {
    return;
  }
  SwrContext* swrContext;
  if ((swrContext = (SwrContext*)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}

// ---------------------------------------------------------------------------
// Video (TorrStream). The decoded picture is copied as I420 into the
// VideoDecoderOutputBuffer (VIDEO_OUTPUT_MODE_YUV renders it with GL as is) and,
// in VIDEO_OUTPUT_MODE_SURFACE_YUV, from there into a YV12 ANativeWindow buffer,
// like the libvpx/dav1d extensions do. Based on androidx/media#1591, without
// libyuv (plain plane copies) and with frames other than 4:2:0 8-bit converted
// by swscale.

// LINT.IfChange
static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_NO_FRAME = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
static const int VIDEO_DECODER_AGAIN = -3;
static const int VIDEO_DECODER_ERROR_SURFACE = -4;
// LINT.ThenChange(../java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoDecoder.java)

// android.graphics.ImageFormat.YV12
static const int kImageFormatYV12 = 0x32315659;

static jfieldID outputDataField;
static jfieldID outputWidthField;
static jfieldID outputHeightField;
static jfieldID outputYStrideField;
static jfieldID outputUvStrideField;
static jfieldID outputColorspaceField;
static jfieldID outputTimeUsField;
static jmethodID initForYuvFrameMethod;

struct VideoContext {
  AVCodecContext* codec = nullptr;
  AVPacket* packet = nullptr;
  AVFrame* frame = nullptr;
  // swscale target for frames that are not 8-bit 4:2:0.
  SwsContext* sws = nullptr;
  AVFrame* converted = nullptr;

  ANativeWindow* window = nullptr;
  jobject surface = nullptr;  // global ref
  int windowWidth = 0;
  int windowHeight = 0;
  int windowFormat = 0;
  // I420 -> RGBA for the RGBA window (render thread, separate from |sws|).
  SwsContext* renderSws = nullptr;

  void ReleaseWindow(JNIEnv* env) {
    if (window) {
      ANativeWindow_release(window);
      window = nullptr;
    }
    if (surface) {
      env->DeleteGlobalRef(surface);
      surface = nullptr;
    }
    windowWidth = 0;
    windowHeight = 0;
    windowFormat = 0;
  }
};

static inline int alignTo16(int value) { return (value + 15) & ~15; }

/** AVColorSpace to VideoDecoderOutputBuffer.COLORSPACE_*. */
static int toOutputColorspace(AVColorSpace colorspace) {
  switch (colorspace) {
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M:
    case AVCOL_SPC_SMPTE240M:
      return 1;
    case AVCOL_SPC_BT709:
      return 2;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL:
      return 3;
    default:
      return 0;
  }
}

static void copyPlane(uint8_t* dst, int dstStride, const uint8_t* src,
                      int srcStride, int width, int height) {
  for (int y = 0; y < height; y++) {
    memcpy(dst, src, width);
    dst += dstStride;
    src += srcStride;
  }
}

static void releaseVideoContext(JNIEnv* env, VideoContext* context) {
  if (!context) return;
  if (context->codec) avcodec_free_context(&context->codec);
  if (context->packet) av_packet_free(&context->packet);
  if (context->frame) av_frame_free(&context->frame);
  if (context->converted) av_frame_free(&context->converted);
  if (context->sws) sws_freeContext(context->sws);
  if (context->renderSws) sws_freeContext(context->renderSws);
  context->ReleaseWindow(env);
  delete context;
}

jlong ffmpegVideoInitialize(JNIEnv* env, jobject thiz, jstring codecName,
                            jbyteArray extraData, jint threads, jint width,
                            jint height) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Video codec not found.");
    return 0L;
  }
  VideoContext* context = new (std::nothrow) VideoContext();
  if (!context) return 0L;
  context->codec = avcodec_alloc_context3(codec);
  context->packet = av_packet_alloc();
  context->frame = av_frame_alloc();
  if (!context->codec || !context->packet || !context->frame) {
    LOGE("Failed to allocate video context.");
    releaseVideoContext(env, context);
    return 0L;
  }
  AVCodecContext* codecContext = context->codec;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    codecContext->extradata_size = size;
    codecContext->extradata = static_cast<uint8_t*>(
        av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!codecContext->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseVideoContext(env, context);
      return 0L;
    }
    env->GetByteArrayRegion(extraData, 0, size,
                            reinterpret_cast<jbyte*>(codecContext->extradata));
  }
  // Container dimensions: msmpeg4 and wmv3 bitstreams do not carry them.
  if (width > 0 && height > 0) {
    codecContext->width = width;
    codecContext->height = height;
  }
  codecContext->thread_count = threads;
  codecContext->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
  // Torrent streams may be damaged: show what can be decoded instead of failing.
  codecContext->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(codecContext, codec, nullptr);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseVideoContext(env, context);
    return 0L;
  }
  return reinterpret_cast<jlong>(context);
}

jlong ffmpegVideoReset(JNIEnv* env, jobject thiz, jlong jContext) {
  VideoContext* context = reinterpret_cast<VideoContext*>(jContext);
  if (!context || !context->codec) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }
  avcodec_flush_buffers(context->codec);
  return jContext;
}

void ffmpegVideoRelease(JNIEnv* env, jobject thiz, jlong jContext) {
  releaseVideoContext(env, reinterpret_cast<VideoContext*>(jContext));
}

jint ffmpegVideoSendPacket(JNIEnv* env, jobject thiz, jlong jContext,
                           jobject inputData, jint inputSize, jlong timeUs) {
  VideoContext* context = reinterpret_cast<VideoContext*>(jContext);
  uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(inputData));
  if (!context || !data) {
    LOGE("Invalid context or input buffer.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  AVPacket* packet = context->packet;
  packet->data = data;
  packet->size = inputSize;
  // Containers give PTS (MKV) or DTS (AVI); with both set, the decoder's
  // best_effort_timestamp picks whichever stays monotonic in output order.
  packet->pts = timeUs;
  packet->dts = timeUs;
  // Not refcounted: avcodec_send_packet copies the data.
  int result = avcodec_send_packet(context->codec, packet);
  packet->data = nullptr;
  packet->size = 0;
  if (result == AVERROR(EAGAIN)) {
    return VIDEO_DECODER_AGAIN;
  }
  if (result < 0) {
    logError("avcodec_send_packet", result);
    return result == AVERROR_INVALIDDATA ? VIDEO_DECODER_NO_FRAME
                                         : VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}

/** Converts the frame to 8-bit 4:2:0 with swscale; returns null on failure. */
static AVFrame* convertToYuv420p(VideoContext* context, AVFrame* src) {
  AVFrame* dst = context->converted;
  if (dst && (dst->width != src->width || dst->height != src->height)) {
    av_frame_free(&context->converted);
    dst = nullptr;
  }
  if (!dst) {
    dst = av_frame_alloc();
    if (!dst) return nullptr;
    dst->format = AV_PIX_FMT_YUV420P;
    dst->width = src->width;
    dst->height = src->height;
    if (av_frame_get_buffer(dst, 0) < 0) {
      av_frame_free(&dst);
      return nullptr;
    }
    context->converted = dst;
  }
  context->sws = sws_getCachedContext(
      context->sws, src->width, src->height,
      static_cast<AVPixelFormat>(src->format), dst->width, dst->height,
      AV_PIX_FMT_YUV420P, SWS_BILINEAR, nullptr, nullptr, nullptr);
  if (!context->sws) return nullptr;
  sws_scale(context->sws, src->data, src->linesize, 0, src->height, dst->data,
            dst->linesize);
  dst->colorspace = src->colorspace;
  return dst;
}

jint ffmpegVideoReceiveFrame(JNIEnv* env, jobject thiz, jlong jContext,
                             jobject outputBuffer, jboolean decodeOnly) {
  VideoContext* context = reinterpret_cast<VideoContext*>(jContext);
  AVFrame* frame = context->frame;
  int result = avcodec_receive_frame(context->codec, frame);
  if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
    return VIDEO_DECODER_NO_FRAME;
  }
  if (result < 0) {
    logError("avcodec_receive_frame", result);
    return result == AVERROR_INVALIDDATA ? VIDEO_DECODER_NO_FRAME
                                         : VIDEO_DECODER_ERROR_OTHER;
  }
  if (decodeOnly) {
    av_frame_unref(frame);
    return VIDEO_DECODER_NO_FRAME;
  }

  AVFrame* picture = frame;
  if (frame->format != AV_PIX_FMT_YUV420P &&
      frame->format != AV_PIX_FMT_YUVJ420P) {
    picture = convertToYuv420p(context, frame);
    if (!picture) {
      LOGE("Conversion from pixel format %d failed.", frame->format);
      av_frame_unref(frame);
      return VIDEO_DECODER_ERROR_OTHER;
    }
  }

  const int width = picture->width;
  const int height = picture->height;
  const int uvWidth = (width + 1) / 2;
  const int uvHeight = (height + 1) / 2;
  const int yStride = alignTo16(width);
  const int uvStride = alignTo16(uvWidth);
  jboolean initialized = env->CallBooleanMethod(
      outputBuffer, initForYuvFrameMethod, width, height, yStride, uvStride,
      toOutputColorspace(frame->colorspace));
  if (env->ExceptionCheck() || !initialized) {
    av_frame_unref(frame);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  int64_t pts = frame->best_effort_timestamp;
  if (pts != AV_NOPTS_VALUE) {
    env->SetLongField(outputBuffer, outputTimeUsField, pts);
  }
  jobject dataObject = env->GetObjectField(outputBuffer, outputDataField);
  uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(dataObject));
  env->DeleteLocalRef(dataObject);
  if (!data) {
    av_frame_unref(frame);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  uint8_t* u = data + yStride * height;
  uint8_t* v = u + uvStride * uvHeight;
  copyPlane(data, yStride, picture->data[0], picture->linesize[0], width, height);
  copyPlane(u, uvStride, picture->data[1], picture->linesize[1], uvWidth, uvHeight);
  copyPlane(v, uvStride, picture->data[2], picture->linesize[2], uvWidth, uvHeight);
  av_frame_unref(frame);
  return VIDEO_DECODER_SUCCESS;
}

jint ffmpegVideoRenderFrame(JNIEnv* env, jobject thiz, jlong jContext,
                            jobject surface, jobject outputBuffer,
                            jboolean rgba) {
  VideoContext* context = reinterpret_cast<VideoContext*>(jContext);
  if (!context->surface || !env->IsSameObject(context->surface, surface)) {
    context->ReleaseWindow(env);
    context->window = ANativeWindow_fromSurface(env, surface);
    if (!context->window) {
      LOGE("ANativeWindow_fromSurface failed.");
      return VIDEO_DECODER_ERROR_SURFACE;
    }
    context->surface = env->NewGlobalRef(surface);
  }
  const int width = env->GetIntField(outputBuffer, outputWidthField);
  const int height = env->GetIntField(outputBuffer, outputHeightField);
  const int srcYStride = env->GetIntField(outputBuffer, outputYStrideField);
  const int srcUvStride = env->GetIntField(outputBuffer, outputUvStrideField);
  // YV12 is what the libvpx/dav1d extensions use. RGBA (with an I420->RGBA
  // conversion here) is for gralloc that cannot lock YV12 for the CPU: the
  // Android emulator (ranchu) overruns the buffer in gralloc_lock.
  const int format = rgba ? WINDOW_FORMAT_RGBX_8888 : kImageFormatYV12;
  if (context->windowWidth != width || context->windowHeight != height ||
      context->windowFormat != format) {
    if (ANativeWindow_setBuffersGeometry(context->window, width, height,
                                         format)) {
      LOGE("ANativeWindow_setBuffersGeometry failed.");
      return VIDEO_DECODER_ERROR_SURFACE;
    }
    context->windowWidth = width;
    context->windowHeight = height;
    context->windowFormat = format;
  }
  ANativeWindow_Buffer buffer;
  int result = ANativeWindow_lock(context->window, &buffer, nullptr);
  if (result || !buffer.bits) {
    // The surface went away (-19, ENODEV): drop the frame, the renderer gets a
    // new surface or none.
    LOGE("ANativeWindow_lock failed: %d", result);
    context->ReleaseWindow(env);
    return VIDEO_DECODER_ERROR_SURFACE;
  }
  jobject dataObject = env->GetObjectField(outputBuffer, outputDataField);
  const uint8_t* src =
      static_cast<const uint8_t*>(env->GetDirectBufferAddress(dataObject));
  env->DeleteLocalRef(dataObject);
  if (src && rgba) {
    const int colorspace = env->GetIntField(outputBuffer, outputColorspaceField);
    context->renderSws = sws_getCachedContext(
        context->renderSws, width, height, AV_PIX_FMT_YUV420P, buffer.width,
        buffer.height, AV_PIX_FMT_RGBA, SWS_POINT, nullptr, nullptr, nullptr);
    if (context->renderSws) {
      const int* coefficients = sws_getCoefficients(
          colorspace == 2 ? SWS_CS_ITU709
                          : colorspace == 3 ? SWS_CS_BT2020 : SWS_CS_ITU601);
      int srcRange, dstRange, brightness, contrast, saturation;
      int *invTable, *table;
      sws_getColorspaceDetails(context->renderSws, &invTable, &srcRange, &table,
                               &dstRange, &brightness, &contrast, &saturation);
      sws_setColorspaceDetails(context->renderSws, coefficients, 0, table, 1,
                               brightness, contrast, saturation);
      const uint8_t* planes[3] = {
          src, src + srcYStride * height,
          src + srcYStride * height + srcUvStride * ((height + 1) / 2)};
      const int strides[3] = {srcYStride, srcUvStride, srcUvStride};
      uint8_t* dst[1] = {static_cast<uint8_t*>(buffer.bits)};
      const int dstStrides[1] = {buffer.stride * 4};
      sws_scale(context->renderSws, planes, strides, 0, height, dst, dstStrides);
    }
  } else if (src) {
    const int copyWidth = width < buffer.width ? width : buffer.width;
    const int copyHeight = height < buffer.height ? height : buffer.height;
    const int uvWidth = (copyWidth + 1) / 2;
    const int uvHeight = (copyHeight + 1) / 2;
    const uint8_t* srcU = src + srcYStride * height;
    const uint8_t* srcV = srcU + srcUvStride * ((height + 1) / 2);
    // YV12: Y, then V, then U; chroma stride is half the luma stride aligned
    // to 16, chroma height is half the buffer height.
    uint8_t* dstY = static_cast<uint8_t*>(buffer.bits);
    const int dstUvStride = alignTo16(buffer.stride / 2);
    uint8_t* dstV = dstY + buffer.stride * buffer.height;
    uint8_t* dstU = dstV + dstUvStride * ((buffer.height + 1) / 2);
    copyPlane(dstY, buffer.stride, src, srcYStride, copyWidth, copyHeight);
    copyPlane(dstV, dstUvStride, srcV, srcUvStride, uvWidth, uvHeight);
    copyPlane(dstU, dstUvStride, srcU, srcUvStride, uvWidth, uvHeight);
  }
  if (ANativeWindow_unlockAndPost(context->window)) {
    LOGE("ANativeWindow_unlockAndPost failed.");
    return VIDEO_DECODER_ERROR_SURFACE;
  }
  return src ? VIDEO_DECODER_SUCCESS : VIDEO_DECODER_ERROR_OTHER;
}

static bool registerVideoNatives(JNIEnv* env) {
  jclass bufferClass =
      env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (!bufferClass) {
    LOGE("JNI_OnLoad: FindClass failed for VideoDecoderOutputBuffer");
    return false;
  }
  outputDataField = env->GetFieldID(bufferClass, "data", "Ljava/nio/ByteBuffer;");
  outputWidthField = env->GetFieldID(bufferClass, "width", "I");
  outputHeightField = env->GetFieldID(bufferClass, "height", "I");
  outputYStrideField = env->GetFieldID(bufferClass, "yStride", "I");
  outputUvStrideField = env->GetFieldID(bufferClass, "uvStride", "I");
  outputColorspaceField = env->GetFieldID(bufferClass, "colorspace", "I");
  outputTimeUsField = env->GetFieldID(bufferClass, "timeUs", "J");
  initForYuvFrameMethod =
      env->GetMethodID(bufferClass, "initForYuvFrame", "(IIIII)Z");
  if (!outputDataField || !outputWidthField || !outputHeightField ||
      !outputYStrideField || !outputUvStrideField || !outputColorspaceField ||
      !outputTimeUsField ||
      !initForYuvFrameMethod) {
    LOGE("JNI_OnLoad: VideoDecoderOutputBuffer members not found");
    return false;
  }
  jclass decoderClass = env->FindClass(
      "androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoDecoder");
  if (!decoderClass) {
    LOGE("JNI_OnLoad: FindClass failed for ExperimentalFfmpegVideoDecoder");
    return false;
  }
  static const JNINativeMethod kMethods[] = {
      {"ffmpegInitialize", "(Ljava/lang/String;[BIII)J",
       reinterpret_cast<void*>(ffmpegVideoInitialize)},
      {"ffmpegReset", "(J)J", reinterpret_cast<void*>(ffmpegVideoReset)},
      {"ffmpegRelease", "(J)V", reinterpret_cast<void*>(ffmpegVideoRelease)},
      {"ffmpegSendPacket", "(JLjava/nio/ByteBuffer;IJ)I",
       reinterpret_cast<void*>(ffmpegVideoSendPacket)},
      {"ffmpegReceiveFrame",
       "(JLandroidx/media3/decoder/VideoDecoderOutputBuffer;Z)I",
       reinterpret_cast<void*>(ffmpegVideoReceiveFrame)},
      {"ffmpegRenderFrame",
       "(JLandroid/view/Surface;Landroidx/media3/decoder/"
       "VideoDecoderOutputBuffer;Z)I",
       reinterpret_cast<void*>(ffmpegVideoRenderFrame)},
  };
  if (env->RegisterNatives(decoderClass, kMethods,
                           sizeof(kMethods) / sizeof(kMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for ExperimentalFfmpegVideoDecoder");
    return false;
  }
  return true;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  // Video is optional: without it (e.g. the class stripped by R8) the audio
  // decoders must keep working.
  if (!registerVideoNatives(env)) {
    env->ExceptionClear();
  }
  jclass clazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegAudioDecoder");
  if (!clazz) {
    LOGE("JNI_OnLoad: FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(clazz, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: GetMethodID failed");
    return -1;
  }
  static const JNINativeMethod kFfmpegAudioDecoderMethods[] = {
      {"ffmpegInitialize", "(Ljava/lang/String;[BZII)J",
       reinterpret_cast<void*>(ffmpegInitialize)},
      {"ffmpegDecode",
       "(JLjava/nio/ByteBuffer;ILandroidx/media3/decoder/"
       "SimpleDecoderOutputBuffer;Ljava/nio/ByteBuffer;I)I",
       reinterpret_cast<void*>(ffmpegDecode)},
      {"ffmpegGetChannelCount", "(J)I",
       reinterpret_cast<void*>(ffmpegGetChannelCount)},
      {"ffmpegGetSampleRate", "(J)I",
       reinterpret_cast<void*>(ffmpegGetSampleRate)},
      {"ffmpegReset", "(J[B)J", reinterpret_cast<void*>(ffmpegReset)},
      {"ffmpegRelease", "(J)V", reinterpret_cast<void*>(ffmpegRelease)},
  };
  if (env->RegisterNatives(clazz, kFfmpegAudioDecoderMethods,
                           sizeof(kFfmpegAudioDecoderMethods) /
                               sizeof(kFfmpegAudioDecoderMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegAudioDecoder");
    return -1;
  }

  jclass libraryClazz =
      env->FindClass("androidx/media3/decoder/ffmpeg/FfmpegLibrary");
  if (!libraryClazz) {
    LOGE("JNI_OnLoad: FindClass failed for FfmpegLibrary");
    return -1;
  }
  static const JNINativeMethod kFfmpegLibraryMethods[] = {
      {"ffmpegGetVersion", "()Ljava/lang/String;",
       reinterpret_cast<void*>(ffmpegGetVersion)},
      {"ffmpegGetInputBufferPaddingSize", "()I",
       reinterpret_cast<void*>(ffmpegGetInputBufferPaddingSize)},
      {"ffmpegHasDecoder", "(Ljava/lang/String;)Z",
       reinterpret_cast<void*>(ffmpegHasDecoder)},
  };
  if (env->RegisterNatives(libraryClazz, kFfmpegLibraryMethods,
                           sizeof(kFfmpegLibraryMethods) /
                               sizeof(kFfmpegLibraryMethods[0])) < 0) {
    LOGE("JNI_OnLoad: RegisterNatives failed for FfmpegLibrary");
    return -1;
  }

  return JNI_VERSION_1_6;
}
