package my.torrstream.app.player

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.av1.Dav1dDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import my.torrstream.app.player.avi.RobustAviExtractor
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Скорость программного видеодекодера FFmpeg (tools/ffmpeg-decoder) без отрисовки: разборщики
 * приложения → ExperimentalFfmpegVideoDecoder так быстро, как он успевает. Образцы кладутся
 * вручную в /data/local/tmp/ffbench (нет файла — тест пропускается):
 *
 *   adb push xvid-720x400.avi vc1-1080i50-bframes.mkv ... /data/local/tmp/ffbench/
 *
 * Итог — в logcat с тегом FfmpegBench и в выводе am instrument. Декодер пакетный
 * (package-private), поэтому создаётся рефлексией.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FfmpegVideoDecodeBenchmark {

    @Test fun xvid720x400Avi() = bench("xvid-720x400.avi")
    @Test fun divx3Avi() = bench("divx3-640x352.avi")
    @Test fun mpeg2Dvd16x9Mkv() = bench("dvd-mpeg2-16x9.mkv")
    @Test fun vc1720p60Mkv() = bench("vc1-720p60-flightsim.mkv")
    @Test fun vc11080i50Mkv() = bench("vc1-1080i50-bframes.mkv")
    @Test fun av11080p8bitMkvDav1d() = bench("av1-1080p-8bit.mkv")

    private class Sample(val data: ByteArray, val timeUs: Long, val flags: Int)

    /** Забирает сэмплы видеодорожки по мере разбора. */
    private class VideoCollector : ExtractorOutput, TrackOutput {
        var format: Format? = null
        val samples = ArrayDeque<Sample>()
        private val pending = ByteArrayOutputStream()
        private val scratch = ByteArray(64 * 1024)

        override fun track(id: Int, type: Int): TrackOutput =
            if (type == C.TRACK_TYPE_VIDEO) this else DiscardingTrackOutput()
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
        override fun format(format: Format) {
            this.format = format
        }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw java.io.EOFException()
            }
            pending.write(scratch, 0, read)
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val all = pending.toByteArray()
            val end = all.size - offset
            samples.addLast(Sample(all.copyOfRange(end - size, end), timeUs, flags))
            pending.reset()
            pending.write(all, end, offset)
        }
    }

    /** Чтение файла для DefaultExtractorInput, с любой позиции (RESULT_SEEK). */
    private class FileReader(file: File, position: Long) : DataReader {
        private val raf = RandomAccessFile(file, "r").apply { seek(position) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = raf.read(buffer, offset, length)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        fun close() = raf.close()
    }

    private class Source(private val file: File, private val extractor: Extractor) {
        val output = VideoCollector()
        private val holder = PositionHolder()
        private var reader = FileReader(file, 0)
        private var input: ExtractorInput = DefaultExtractorInput(reader, 0, file.length())
        var ended = false
            private set

        init {
            extractor.init(output)
        }

        /** Читает, пока не появится сэмпл или не кончится файл. */
        fun pull() {
            while (output.samples.isEmpty() && !ended) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> ended = true
                    Extractor.RESULT_SEEK -> {
                        reader.close()
                        reader = FileReader(file, holder.position)
                        input = DefaultExtractorInput(reader, holder.position, file.length())
                    }
                }
            }
        }

        fun close() {
            reader.close()
            extractor.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ffmpegDecoder(format: Format, threads: Int): Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, *> {
        assumeTrue("FFmpeg не берёт ${format.sampleMimeType}", FfmpegLibrary.supportsFormat(format.sampleMimeType!!))
        val decoder = Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoDecoder")
            .getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Format::class.java,
            )
            .apply { isAccessible = true }
            .newInstance(8, 8, 1 shl 20, threads, format)
            as Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, *>
        decoder.javaClass.getMethod("setOutputMode", Int::class.javaPrimitiveType)
            .invoke(decoder, C.VIDEO_OUTPUT_MODE_YUV)
        return decoder
    }

    @Suppress("UNCHECKED_CAST")
    private fun bench(name: String) {
        val file = File(DIR, name)
        assumeTrue("нет $file", file.exists())
        assertTrue("FFmpeg не загрузился", FfmpegLibrary.isAvailable())
        val extractor: Extractor = if (name.endsWith(".avi")) {
            RobustAviExtractor(RobustAviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)
        } else {
            MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)
        }
        val source = Source(file, extractor)
        source.pull()
        val format = source.output.format
        assertNotNull("нет видеодорожки", format)
        format!!
        val threads = Runtime.getRuntime().availableProcessors()
        val decoder: Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, *> = if (format.sampleMimeType == "video/av01") {
            // AV1 — dav1d (tools/av1-decoder), как в SoftwareAv1Renderer: потоки по умолчанию dav1d
            Dav1dDecoder(8, 8, 1 shl 20, 0, 2, false).apply { setOutputMode(C.VIDEO_OUTPUT_MODE_YUV) }
        } else ffmpegDecoder(format, threads)
        var samples = 0
        var frames = 0
        var inputDone = false
        var width = 0
        var height = 0
        val started = SystemClock.elapsedRealtime()
        try {
            loop@ while (true) {
                var progress = false
                if (!inputDone) {
                    source.pull()
                    val input = decoder.dequeueInputBuffer()
                    if (input != null) {
                        progress = true
                        val sample = source.output.samples.removeFirstOrNull()
                        if (sample == null) {
                            input.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            input.ensureSpaceForWrite(sample.data.size)
                            input.data!!.put(sample.data)
                            input.flip()
                            input.timeUs = sample.timeUs
                            if (sample.flags and C.BUFFER_FLAG_KEY_FRAME != 0) {
                                input.addFlag(C.BUFFER_FLAG_KEY_FRAME)
                            }
                            samples++
                        }
                        decoder.queueInputBuffer(input)
                    }
                }
                val output = decoder.dequeueOutputBuffer()
                if (output != null) {
                    progress = true
                    if (output.isEndOfStream) {
                        output.release()
                        break@loop
                    }
                    frames++
                    width = output.width
                    height = output.height
                    output.release()
                }
                if (!progress) Thread.sleep(1)
            }
        } finally {
            decoder.release()
            source.close()
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        val line = "%s: %d×%d, %d кадров из %d сэмплов за %.1f с = %.1f кадр/с (%d потоков, %s)".format(
            name, width, height, frames, samples, seconds, frames / seconds, threads, decoder.name,
        )
        Log.i("FfmpegBench", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", line + "\n")
        })
        // Потери: задержка декодера (B-кадры, потоки) в конце файла — несколько кадров
        assertTrue(line, frames >= samples * 0.9)
    }

    private companion object {
        val DIR = File("/data/local/tmp/ffbench")
    }
}
