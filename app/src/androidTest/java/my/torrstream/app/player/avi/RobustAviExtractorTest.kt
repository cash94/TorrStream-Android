package my.torrstream.app.player.avi

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.avi.AviExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.math.abs

/**
 * AVI из отчётов диагностики 1.1.31–1.1.33, которые встроенный плеер отдавал libVLC, — на
 * настоящем AviExtractor Media3 1.11.1 и настоящем FFmpeg-декодере из lib-decoder-ffmpeg.
 * Образцы — 10 с MPEG-4 ASP/H.264 160×120 + AC-3/MP3, собраны ffmpeg и пересобраны скриптом
 * (scratchpad avimux.py) так, как пишут VirtualDub/AVI-Mux. Каждую порчу сначала проверяем на
 * стоковом разборщике (он должен сломаться — иначе тест ничего не доказывает), потом на
 * RobustAviExtractor.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class RobustAviExtractorTest {

    // region 1. AC-3 полусекундными чанками (id 60–63, 106/107, 145–152)

    /**
     * Аудио AC-3 192 кбит/с чанками по 12000 байт (= maxInputSize из отчётов): в чанке 15,6 кадра,
     * кадры режутся на стыках. Сток отдаёт чанк одним сэмплом, и пакет FFmpeg начинается с обрывка
     * кадра. ac3_decode_frame (FFmpeg 6.0) ищет синхрослово 0B77/770B где угодно в пакете; если в
     * обрывке случайно есть такая пара байт, он разбирает мусорный «кадр», а затем проверка
     * «нет ли за кадром зависимого E-AC-3» возвращает AAC_AC3_PARSE_ERROR_* — не
     * AVERROR_INVALIDDATA, и JNI Media3 отдаёт AUDIO_DECODER_ERROR_OTHER: «Error decoding (see
     * logcat)», плеер уходил в VLC. Шум в образце подобран (anoisesrc seed=4) так, что ложное
     * синхрослово стоит в обрывке в начале чанка №10 (5,0 с) — в живом AC-3 таких пар хватает.
     */
    @Test
    fun ac3ChunksSplitAcrossFramesDecodeWithFfmpeg() {
        assertTrue("FFmpeg extension loaded", FfmpegLibrary.isAvailable())
        val data = asset("avi-ac3-interleave.avi")

        val stock = extract(data, stock())
        val stockAudio = stock.audio()
        assertEquals("stock: one sample per 12000-byte chunk", 21, stockAudio.samples.size)
        assertDecodeFails(stockAudio, "stock from start")

        val r = extract(data, robust())
        val audio = r.audio()
        assertEquals("one sample per AC-3 frame", AC3_FRAMES, audio.samples.size)
        assertTrue(audio.samples.all { it.data.size == AC3_FRAME_SIZE && it.isAc3Sync() })
        assertTimesFollowFrames(audio.samples, startUs = 0)
        assertEquals(stock.video().samples.size, r.video().samples.size)
        val pcm = decode(audio)
        assertEquals("all frames decoded", AC3_FRAMES * AC3_FRAME_PCM, pcm)
    }

    /**
     * Отчёты 145/146, 62/63: продолжение с середины фильма. Точку перехода выбирает видео (ключевой
     * кадр 5,0 с), чтение идёт с неё, и первым попадается аудиочанк №11 (5,5 с). Media3 нумерует
     * его по ключевому кадру аудио ДО позиции (№10), да ещё и время чанка считает как номер ×
     * длительность / число чанков, хотя последний чанк короче: звук после перехода опережает
     * картинку на 0,7 с. Плюс поток уходит декодеру с обрывка кадра.
     */
    @Test
    fun ac3AfterSeekKeepsTimeAndFrameBoundary() {
        val data = asset("avi-ac3-interleave.avi")
        val targetUs = 5_300_000L

        // Истинное время чанка CBR — байты до него / 24000 байт/с.
        val stockChunks = extract(data, stock()).audio().samples
        val stockFirst = extract(data, stock(), seekToUs = targetUs).audio().samples.first()
        val chunk = stockChunks.indexOfFirst { it.data.contentEquals(stockFirst.data) }
        val trueUs = stockChunks.take(chunk).sumOf { it.data.size.toLong() } * 1_000_000 / AC3_BYTES_PER_SECOND
        assertTrue(
            "stock labels chunk $chunk (${trueUs} us) with ${stockFirst.timeUs} us — the damage proves nothing",
            abs(stockFirst.timeUs - trueUs) > 200_000,
        )

        val frames = extract(data, robust()).audio().samples
        val seeked = extract(data, robust(), seekToUs = targetUs)
        // Видео — с ключевого кадра 5,0 с (кадр 125), выбранного точкой перехода.
        assertEquals(5_000_000L, seeked.video().samples.first().timeUs)
        assertTrue(seeked.video().samples.first().keyFrame)
        val audio = seeked.audio()
        assertTrue(audio.samples.all { it.data.size == AC3_FRAME_SIZE && it.isAc3Sync() })
        val first = audio.samples.first()
        val frame = frames.indexOfFirst { it.data.contentEquals(first.data) }
        // Первый целый кадр, начинающийся в чанке, с которого пошло чтение.
        assertEquals((chunk * 12_000 + AC3_FRAME_SIZE - 1) / AC3_FRAME_SIZE, frame)
        assertEquals("time of frame $frame", frame * AC3_FRAME_US, first.timeUs)
        assertTimesFollowFrames(audio.samples, startUs = first.timeUs)
        assertEquals(AC3_FRAMES - frame, audio.samples.size)
        assertEquals("every frame decoded", audio.samples.size * AC3_FRAME_PCM, decode(audio))
    }

    // endregion

    // region 2. hdrl: strn нечётной длины (id 74, 85: IllegalArgumentException в ListChunk)

    @Test
    fun oddSizedStreamNameDoesNotBreakHeader() {
        val data = asset("avi-odd-strn.avi")
        try {
            extract(data, stock())
            fail("stock AviExtractor parsed the header — the damage proves nothing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.stackTrace.any { it.className.endsWith("ListChunk") && it.methodName == "parseFrom" })
        }

        val r = extract(data, robust())
        assertEquals(250, r.video().samples.size)
        assertEquals("REN-TV", r.video().format!!.label)
        assertEquals(MimeTypes.AUDIO_AC3, r.audio().format!!.sampleMimeType)
        assertEquals(AC3_FRAMES, r.audio().samples.size)
        assertTrue(r.seekMap!!.isSeekable)
    }

    /** Список, заявивший размер за краем hdrl (обрезанный заголовок), — тоже не повод падать. */
    @Test
    fun chunkSizeBeyondHeaderIsClamped() {
        val data = asset("avi-odd-strn.avi")
        // strn «REN-TV\0» + заполнитель → чётные 8 байт: выравнивание здесь ни при чём
        val strn = indexOf(data, "strn".toByteArray())
        writeIntLe(data, strn + 4, 8)
        val junk = indexOf(data, "JUNK".toByteArray())
        writeIntLe(data, junk + 4, 0x7FFF_0000)
        try {
            extract(data, stock())
            fail("stock AviExtractor parsed the header")
        } catch (e: IllegalArgumentException) {
            // как в отчётах
        }
        val r = extract(data, robust())
        assertEquals(250, r.video().samples.size)
    }

    // endregion

    // region 3. Видео не распознано — играет один звук (id 119)

    @Test
    fun x264FourccIsVideo() {
        val data = asset("avi-x264-fourcc.avi")
        val stock = extract(data, stock())
        assertNull("stock drops the X264 track", stock.tracks.firstOrNull { it.type == C.TRACK_TYPE_VIDEO })
        assertEquals(MimeTypes.AUDIO_MPEG, stock.audio().format!!.sampleMimeType)

        val r = extract(data, robust())
        val video = r.video()
        assertEquals(MimeTypes.VIDEO_H264, video.format!!.sampleMimeType)
        assertEquals(250, video.samples.size) // 251 чанк, последний пустой
        assertTrue("Annex B start code", video.samples[0].data.take(4) == listOf<Byte>(0, 0, 0, 1))
    }

    /** XviD в смешанном регистре в biCompression, верный fourcc — только в fccHandler. */
    @Test
    fun mixedCaseAndHandlerFourcc() {
        val mixed = asset("avi-ac3-interleave.avi")
        replaceAll(mixed, "XVID", "XviD")
        assertNull(extract(mixed, stock()).tracks.firstOrNull { it.type == C.TRACK_TYPE_VIDEO })
        assertEquals(MimeTypes.VIDEO_MP4V, extract(mixed, robust()).video().format!!.sampleMimeType)

        val handlerOnly = asset("avi-ac3-interleave.avi")
        val strf = indexOf(handlerOnly, "strf".toByteArray())
        writeIntLe(handlerOnly, strf + 8 + 16, 0x3F3F3F3F) // biCompression «????», в strh остаётся XVID
        assertNull(extract(handlerOnly, stock()).tracks.firstOrNull { it.type == C.TRACK_TYPE_VIDEO })
        assertEquals(250, extract(handlerOnly, robust()).video().samples.size)
    }

    // endregion

    // region поведение на обычных AVI не меняется

    /** ffmpeg пишет по кадру в чанк: те же сэмплы и то же время, что у стока (MP3 VBR-стиль). */
    @Test
    fun oneFramePerChunkMatchesStock() {
        val data = asset("avi-x264-fourcc.avi")
        replaceAll(data, "X264", "H264")
        val stock = extract(data, stock())
        val r = extract(data, robust())
        assertEquals(stock.video().samples.size, r.video().samples.size)
        val a = stock.audio().samples
        val b = r.audio().samples
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertTrue(a[i].data.contentEquals(b[i].data))
            assertTrue("sample $i: ${a[i].timeUs} vs ${b[i].timeUs}", abs(a[i].timeUs - b[i].timeUs) <= 1_000)
        }
    }

    // endregion

    // region разбор

    private fun stock(): Extractor = AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)

    private fun robust(): Extractor =
        RobustAviExtractor(RobustAviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)

    private class Sample(val data: ByteArray, val timeUs: Long, val keyFrame: Boolean) {
        fun isAc3Sync() = data[0] == 0x0B.toByte() && data[1] == 0x77.toByte()
    }

    private class Result(val tracks: List<Track>, val seekMap: SeekMap?) {
        fun video() = tracks.single { it.type == C.TRACK_TYPE_VIDEO }
        fun audio() = tracks.single { it.type == C.TRACK_TYPE_AUDIO }
    }

    /** Как ProgressiveMediaPeriod: читать до конца, на RESULT_SEEK — новый поток с позиции. */
    private fun extract(data: ByteArray, extractor: Extractor, seekToUs: Long = C.TIME_UNSET): Result {
        val output = Output()
        extractor.init(output)
        val holder = PositionHolder()
        var input = DefaultExtractorInput(Reader(data, 0), 0, data.size.toLong())
        var seekPending = seekToUs != C.TIME_UNSET
        var steps = 0
        while (true) {
            if (seekPending && output.seekMap != null) {
                // Перемотка сразу после подготовки, как продолжение просмотра с позиции.
                seekPending = false
                val point = output.seekMap!!.getSeekPoints(seekToUs).first
                output.tracks.forEach { it.samples.clear() }
                extractor.seek(point.position, point.timeUs)
                input = DefaultExtractorInput(Reader(data, point.position.toInt()), point.position, data.size.toLong())
            }
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    val position = holder.position
                    input = DefaultExtractorInput(Reader(data, position.toInt()), position, data.size.toLong())
                }
            }
            if (++steps > 1_000_000) fail("extractor does not finish")
        }
        return Result(output.tracks, output.seekMap)
    }

    /** Отдаёт данные кусками, как сеть. */
    private class Reader(private val data: ByteArray, private var position: Int) : DataReader {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - position, 4096)
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            return n
        }
    }

    private class Output : ExtractorOutput {
        val tracks = mutableListOf<Track>()
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput =
            tracks.firstOrNull { it.id == id } ?: Track(id, type).also { tracks += it }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class Track(val id: Int, val type: Int) : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Sample>()
        private val pending = ByteArrayOutputStream()
        private val scratch = ByteArray(64 * 1024)

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            pending.write(scratch, 0, n)
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertNotNull("sample before format", format)
            val bytes = pending.toByteArray()
            val end = bytes.size - offset
            samples += Sample(bytes.copyOfRange(end - size, end), timeUs, flags and C.BUFFER_FLAG_KEY_FRAME != 0)
            pending.reset()
            pending.write(bytes, end, offset)
        }
    }

    // endregion

    // region декодирование FFmpeg — как FfmpegAudioRenderer

    /** Прогоняет сэмплы через FfmpegAudioDecoder и возвращает число байт PCM. */
    private fun decode(track: Track): Long {
        val format = track.format!!
        val decoder = FfmpegAudioDecoder(format, 16, 16, maxOf(format.maxInputSize, 5760), false)
        try {
            var pcm = 0L
            var ended = false
            fun drain() {
                while (true) {
                    val out = decoder.dequeueOutputBuffer() ?: return
                    if (out.isEndOfStream) ended = true
                    else if (!out.shouldBeSkipped) pcm += out.data!!.limit()
                    out.release()
                }
            }
            fun queue(fill: (DecoderInputBuffer) -> Unit) {
                val deadline = System.currentTimeMillis() + 10_000
                while (true) {
                    val input = decoder.dequeueInputBuffer()
                    if (input != null) {
                        fill(input)
                        decoder.queueInputBuffer(input)
                        return
                    }
                    drain()
                    if (System.currentTimeMillis() > deadline) fail("decoder stuck")
                    Thread.sleep(1)
                }
            }
            for (sample in track.samples) {
                queue {
                    it.ensureSpaceForWrite(sample.data.size)
                    it.data!!.put(sample.data)
                    it.timeUs = sample.timeUs
                    it.flip()
                }
                drain()
            }
            queue { it.setFlags(C.BUFFER_FLAG_END_OF_STREAM) }
            val deadline = System.currentTimeMillis() + 10_000
            while (!ended) {
                drain()
                if (System.currentTimeMillis() > deadline) fail("no end of stream")
                Thread.sleep(1)
            }
            return pcm
        } finally {
            decoder.release()
        }
    }

    private fun assertDecodeFails(track: Track, what: String) {
        try {
            val pcm = decode(track)
            fail("$what: FFmpeg decoded $pcm bytes without an error — the damage proves nothing")
        } catch (e: FfmpegDecoderException) {
            assertTrue("$what: ${e.message}", e.message.orEmpty().contains("Error decoding"))
        }
    }

    // endregion

    private fun assertTimesFollowFrames(samples: List<Sample>, startUs: Long) {
        samples.forEachIndexed { i, s ->
            val expected = startUs + i * AC3_FRAME_US
            assertTrue("frame $i at ${s.timeUs}, expected $expected", abs(s.timeUs - expected) <= 1)
        }
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (k in pattern.indices) if (data[i + k] != pattern[k]) continue@outer
            return i
        }
        return -1
    }

    private fun replaceAll(data: ByteArray, from: String, to: String) {
        var i = indexOf(data, from.toByteArray())
        assertTrue(i >= 0)
        while (i >= 0) {
            to.toByteArray().copyInto(data, i)
            i = indexOf(data, from.toByteArray(), i + 1)
        }
    }

    private fun writeIntLe(data: ByteArray, at: Int, value: Int) {
        for (k in 0..3) data[at + k] = (value ushr (8 * k)).toByte()
    }

    private companion object {
        /** AC-3 192 кбит/с, 48 кГц: кадр 768 байт, 1536 сэмплов = 32 мс; 240384 байт в образце. */
        const val AC3_FRAME_SIZE = 768
        const val AC3_FRAMES = 313
        const val AC3_FRAME_US = 32_000L
        const val AC3_BYTES_PER_SECOND = 24_000L

        /** PCM 16 бит стерео на кадр. */
        const val AC3_FRAME_PCM = 1536L * 2 * 2
    }
}
