package my.torrstream.app.player.avi;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Ac3Util;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.MpegAudioUtil;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Режет аудиопоток AVI на кадры AC-3/E-AC-3/MP3 по синхрослову.
 *
 * <p>AVI не обязан хранить по кадру в чанке: VirtualDub, AVI-Mux GUI и многие рипы пишут CBR-аудио
 * чанками по полсекунды (12000 байт у AC-3 192 кбит/с, 28000 у 448 кбит/с), и кадры режутся на их
 * стыках. Media3 отдаёт чанк декодеру как один сэмпл: аппаратный AC-3 и FFmpeg получают обрывки
 * кадров, а после перемотки или пересоздания плеера — поток, начинающийся с середины кадра. Здесь
 * байты копятся через границы чанков, и наружу уходит ровно по кадру.
 *
 * <p>Время кадра — от точки привязки (время чанка, где после сброса нашёлся первый кадр, плюс
 * смещение в нём по битрейту) и дальше по счёту сэмплов: так оно не зависит от размеров чанков и
 * от ошибок dwLength в заголовке. Кадр «принимается», только если за ним в потоке тоже кадр — иначе
 * случайное 0x0B77/0xFFE в данных сбивало бы разбор. Если кадров не нашлось вовсе (например, MP3
 * free-format), разборщик переходит на поведение Media3 — чанк целиком.
 */
@OptIn(markerClass = UnstableApi.class)
/* package */ final class AudioFrameSplitter {

  private static final String TAG = "AudioFrameSplitter";

  private static final int TYPE_AC3 = 0;
  private static final int TYPE_MPEG = 1;

  /** Сколько байт просмотреть без единого кадра, прежде чем сдаться и отдавать чанки целиком. */
  private static final int MAX_BYTES_WITHOUT_FRAME = 512 * 1024;

  private static final int[] AC3_SAMPLE_RATES = {48000, 44100, 32000};
  private static final int[] EAC3_HALF_SAMPLE_RATES = {24000, 22050, 16000};

  @Nullable
  public static AudioFrameSplitter createFor(Format format, TrackOutput trackOutput) {
    @Nullable String mimeType = format.sampleMimeType;
    if (MimeTypes.AUDIO_AC3.equals(mimeType) || MimeTypes.AUDIO_E_AC3.equals(mimeType)) {
      return new AudioFrameSplitter(TYPE_AC3, trackOutput);
    }
    if (MimeTypes.AUDIO_MPEG.equals(mimeType)) {
      return new AudioFrameSplitter(TYPE_MPEG, trackOutput);
    }
    return null;
  }

  private final int type;
  private final int headerSize;
  private final TrackOutput trackOutput;
  private final ParsableByteArray sampleData;
  private final byte[] header;
  private final MpegAudioUtil.Header mpegHeader;

  private byte[] buffer;
  private int length;

  /** Начало текущего чанка в {@link #buffer} и его время по таблице чанков. */
  private int chunkStart;

  private long chunkTimeUs;

  /** Предыдущий кадр кончился ровно на начале буфера — следующий ждём там же. */
  private boolean locked;

  private boolean passthrough;
  private int bytesWithoutFrame;

  private long anchorTimeUs;
  private long samplesSinceAnchor;
  private int anchorSampleRate;

  // Разобранный заголовок (parseHeader).
  private int frameSize;
  private int frameSamples;
  private int frameSampleRate;

  private AudioFrameSplitter(int type, TrackOutput trackOutput) {
    this.type = type;
    this.trackOutput = trackOutput;
    headerSize = type == TYPE_AC3 ? 6 : 4;
    header = new byte[headerSize];
    mpegHeader = new MpegAudioUtil.Header();
    sampleData = new ParsableByteArray();
    buffer = new byte[32 * 1024];
    anchorTimeUs = C.TIME_UNSET;
  }

  /** Перемотка: поток продолжится с другого места. */
  public void reset() {
    length = 0;
    chunkStart = 0;
    locked = false;
    bytesWithoutFrame = 0;
    anchorTimeUs = C.TIME_UNSET;
    samplesSinceAnchor = 0;
  }

  public void onChunkStart(int size, long chunkTimeUs) {
    if (buffer.length < length + size) {
      buffer = Arrays.copyOf(buffer, Math.max(length + size, buffer.length * 2));
    }
    chunkStart = length;
    this.chunkTimeUs = chunkTimeUs;
  }

  /** Дочитывает данные чанка; как {@link TrackOutput#sampleData} c allowEndOfInput = false. */
  public int readChunkData(ExtractorInput input, int maxLength) throws IOException {
    int read = input.read(buffer, length, maxLength);
    if (read == C.RESULT_END_OF_INPUT) {
      throw new java.io.EOFException();
    }
    length += read;
    return read;
  }

  public void onChunkEnd() {
    if (passthrough) {
      outputChunkAsIs();
      return;
    }
    int position = 0;
    while (length - position >= headerSize) {
      boolean valid = parseHeader(position);
      if (valid && locked) {
        if (position + frameSize > length) {
          break; // кадр дочитается со следующим чанком
        }
        outputFrame(position);
        position += frameSize;
        continue;
      }
      locked = false;
      if (valid) {
        int next = position + frameSize;
        if (next + headerSize > length) {
          break; // подтвердить нечем — ждём данных
        }
        int sampleRate = frameSampleRate;
        if (parseHeader(next) && frameSampleRate == sampleRate) {
          parseHeader(position);
          locked = true;
          if (anchorTimeUs == C.TIME_UNSET || anchorSampleRate != frameSampleRate) {
            anchor(position);
          }
          continue;
        }
      }
      position++;
      bytesWithoutFrame++;
    }
    if (position > 0) {
      System.arraycopy(buffer, position, buffer, 0, length - position);
      length -= position;
      chunkStart -= position;
    }
    if (!locked && bytesWithoutFrame > MAX_BYTES_WITHOUT_FRAME) {
      Log.w(TAG, "No audio frames found, passing chunks through");
      passthrough = true;
      length = 0;
    }
  }

  private void anchor(int position) {
    // Время первого кадра: время чанка + смещение начала кадра от начала чанка по битрейту.
    long bytesPerSecond = (long) frameSize * frameSampleRate / Math.max(1, frameSamples);
    long offsetUs =
        bytesPerSecond > 0 ? (position - chunkStart) * C.MICROS_PER_SECOND / bytesPerSecond : 0;
    anchorTimeUs = Math.max(0, chunkTimeUs + offsetUs);
    anchorSampleRate = frameSampleRate;
    samplesSinceAnchor = 0;
  }

  private void outputFrame(int position) {
    bytesWithoutFrame = 0;
    if (frameSampleRate != anchorSampleRate) {
      // Частота сменилась посреди потока: продолжаем от времени конца предыдущего кадра.
      anchorTimeUs = currentTimeUs();
      anchorSampleRate = frameSampleRate;
      samplesSinceAnchor = 0;
    }
    long timeUs = currentTimeUs();
    sampleData.reset(buffer, position + frameSize);
    sampleData.setPosition(position);
    trackOutput.sampleData(sampleData, frameSize);
    trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null);
    samplesSinceAnchor += frameSamples;
  }

  private long currentTimeUs() {
    return anchorTimeUs + samplesSinceAnchor * C.MICROS_PER_SECOND / anchorSampleRate;
  }

  private void outputChunkAsIs() {
    int size = length - chunkStart;
    if (size > 0) {
      sampleData.reset(buffer, length);
      sampleData.setPosition(chunkStart);
      trackOutput.sampleData(sampleData, size);
      trackOutput.sampleMetadata(chunkTimeUs, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
    }
    length = 0;
    chunkStart = 0;
  }

  /** Заголовок кадра в {@code buffer[position]}: заполняет frameSize/frameSamples/frameSampleRate. */
  private boolean parseHeader(int position) {
    if (type == TYPE_AC3) {
      if (buffer[position] != 0x0B || buffer[position + 1] != 0x77) {
        return false;
      }
      System.arraycopy(buffer, position, header, 0, headerSize);
      int bsid = (header[5] & 0xF8) >> 3;
      int fscod = (header[4] & 0xC0) >> 6;
      if (bsid > 16) {
        return false;
      }
      if (bsid <= 10) {
        if (fscod == 3) {
          return false;
        }
        frameSampleRate = AC3_SAMPLE_RATES[fscod];
      } else if (fscod == 3) {
        int fscod2 = (header[4] & 0x30) >> 4;
        if (fscod2 == 3) {
          return false;
        }
        frameSampleRate = EAC3_HALF_SAMPLE_RATES[fscod2];
      } else {
        frameSampleRate = AC3_SAMPLE_RATES[fscod];
      }
      frameSize = Ac3Util.parseAc3SyncframeSize(header);
      if (frameSize < headerSize + 1) {
        return false;
      }
      frameSamples = Ac3Util.parseAc3SyncframeAudioSampleCount(ByteBuffer.wrap(header));
      return frameSamples > 0;
    } else {
      if ((buffer[position] & 0xFF) != 0xFF || (buffer[position + 1] & 0xE0) != 0xE0) {
        return false;
      }
      int headerData =
          ((buffer[position] & 0xFF) << 24)
              | ((buffer[position + 1] & 0xFF) << 16)
              | ((buffer[position + 2] & 0xFF) << 8)
              | (buffer[position + 3] & 0xFF);
      if (!mpegHeader.setForHeaderData(headerData) || mpegHeader.frameSize <= headerSize) {
        return false;
      }
      frameSize = mpegHeader.frameSize;
      frameSamples = mpegHeader.samplesPerFrame;
      frameSampleRate = mpegHeader.sampleRate;
      return frameSampleRate > 0;
    }
  }
}
