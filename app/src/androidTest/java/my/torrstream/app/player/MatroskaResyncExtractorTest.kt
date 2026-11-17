package my.torrstream.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException

/**
 * Порча MKV из отчётов диагностики 1.1.31–1.1.32 на настоящем MatroskaExtractor (Media3 1.11.1).
 * Образец — damaged-mkv-sample.mkv: 20 с, H.264 160×120 + AAC, кластер на секунду, индекс
 * (Cues) в конце файла, как у mkvmerge и ffmpeg. Каждую порчу сначала проверяем на голом
 * разборщике (он должен падать — иначе тест ничего не доказывает), потом — на цепочке обёрток
 * плеера (SeekGuard → DolbyVision → Resync), которая должна дойти до конца файла.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MatroskaResyncExtractorTest {

    private lateinit var sample: ByteArray
    private lateinit var intact: Result

    @Before
    fun setUp() {
        sample = InstrumentationRegistry.getInstrumentation().context.assets
            .open("damaged-mkv-sample.mkv").use { it.readBytes() }
        intact = extract(sample, plain())
        assertTrue("intact sample has video", intact.videoSamples > 400)
        assertTrue("intact sample has audio", intact.audioSamples > 0)
        assertTrue(intact.seekMap!!.isSeekable)
    }

    /** «Художник», «Тверская»: индекс испорчен с середины — перемотка по уцелевшей части. */
    @Test
    fun damagedCuesTailKeepsTracksAndPartialSeeking() {
        val (start, end) = cues(sample)
        val data = sample.copyOf()
        val from = start + (end - start) * 2 / 5
        data.fill(0, from, end)
        assertPlainFails(data)

        val r = extract(data, guarded())
        assertEquals(intact.videoSamples, r.videoSamples)
        assertEquals(intact.audioSamples, r.audioSamples)
        assertEquals(2, r.formats)
        assertTrue("seekable on the surviving cue points", r.seekMap!!.isSeekable)
    }

    /** Испорчен сам заголовок индекса: форматы всё равно приходят, фильм идёт без перемотки. */
    @Test
    fun damagedCuesHeaderPlaysWithoutSeeking() {
        val (start, _) = cues(sample)
        val data = sample.copyOf()
        data.fill(0, start, start + 4)
        assertPlainFails(data)

        val r = extract(data, guarded())
        assertEquals(intact.videoSamples, r.videoSamples)
        assertEquals(2, r.formats)
        assertFalse(r.seekMap!!.isSeekable)
    }

    /** «Поймай меня», «Тед Лассо»: «Top bit not zero» — испорчена длина NAL в кадре. */
    @Test
    fun corruptNalLengthSkipsToNextCluster() {
        val data = sample.copyOf()
        val block = videoBlockPayload(data, cluster = 10)
        data[block] = 0x80.toByte() // старший бит 4-байтовой длины NAL
        assertPlainFails(data, "Top bit not zero")

        val r = extract(data, guarded())
        assertTrue("lost at most one cluster", r.videoSamples >= intact.videoSamples - 25)
        assertTrue("the damaged frame is gone", r.videoSamples < intact.videoSamples)
    }

    /** Порча посреди кластера («No valid varint») — как и в 1.1.31, до следующего кластера. */
    @Test
    fun zerosInsideClusterSkipToNextCluster() {
        val data = sample.copyOf()
        val at = clusterStarts(data)[5] + 200
        data.fill(0, at, at + 2048)
        assertPlainFails(data, "varint")

        val r = extract(data, guarded())
        assertTrue(r.videoSamples >= intact.videoSamples - 25)
    }

    /** «Нейровася» E05: мусор после последнего кластера — конец фильма, а не ошибка. */
    @Test
    fun garbageAfterLastClusterEndsPlayback() {
        val data = sample + ByteArray(200 * 1024)
        assertPlainFails(data, "varint")

        val r = extract(data, guarded())
        assertEquals(intact.videoSamples, r.videoSamples)
        assertEquals(intact.audioSamples, r.audioSamples)
    }

    /** Поток оборвался раньше заявленной длины — не конец фильма: ошибка уходит плееру. */
    @Test
    fun truncatedStreamIsNotTreatedAsEnd() {
        val data = sample + ByteArray(200 * 1024)
        try {
            extract(data, guarded(), declaredLength = data.size + 10_000_000L)
            fail("expected the damaged-data error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("varint"))
        }
    }

    // region разбор

    private fun plain(): Extractor = MatroskaExtractor()

    /** Как в AssSubtitles.withAss: SeekGuard(DolbyVision(Resync(Matroska))). */
    private fun guarded(): Extractor =
        SeekGuardExtractor(DolbyVisionBaseLayerExtractor(MatroskaResyncExtractor(MatroskaExtractor())))

    private fun assertPlainFails(data: ByteArray, message: String? = null) {
        try {
            extract(data, plain())
        } catch (e: Exception) {
            if (message != null) assertTrue("${e.message}", e.message.orEmpty().contains(message))
            return
        }
        fail("plain MatroskaExtractor did not fail — the damage proves nothing")
    }

    private class Result(val videoSamples: Int, val audioSamples: Int, val formats: Int, val seekMap: SeekMap?)

    /** Как ProgressiveMediaPeriod: читать до конца, на RESULT_SEEK — новый поток с позиции. */
    private fun extract(data: ByteArray, extractor: Extractor, declaredLength: Long = data.size.toLong()): Result {
        val output = Output()
        extractor.init(output)
        val holder = PositionHolder()
        var input = DefaultExtractorInput(Reader(data, 0), 0, declaredLength)
        var steps = 0
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> {
                    val position = holder.position
                    input = DefaultExtractorInput(Reader(data, position.toInt()), position, declaredLength)
                }
            }
            if (++steps > 1_000_000) fail("extractor does not finish")
        }
        return Result(
            output.tracks.filter { it.type == C.TRACK_TYPE_VIDEO }.sumOf { it.samples },
            output.tracks.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.samples },
            output.tracks.count { it.format != null },
            output.seekMap,
        )
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
        var samples = 0
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
            return n
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            assertNotNull("sample before format", format)
            samples++
        }
    }

    // endregion

    // region поиск элементов в образце

    /** Начало и конец элемента Cues (последнее вхождение ID: первое — ссылка в SeekHead). */
    private fun cues(data: ByteArray): Pair<Int, Int> {
        val start = lastIndexOf(data, CUES_ID)
        assertTrue("Cues in the sample", start > 0)
        val (size, sizeLength) = vint(data, start + CUES_ID.size)
        return start to (start + CUES_ID.size + sizeLength + size.toInt())
    }

    private fun clusterStarts(data: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = indexOf(data, CLUSTER_ID, 0)
        while (i >= 0) {
            out += i
            i = indexOf(data, CLUSTER_ID, i + 1)
        }
        return out
    }

    /** Смещение первого байта данных первого видеокадра (дорожка 1) в кластере [cluster]. */
    private fun videoBlockPayload(data: ByteArray, cluster: Int): Int {
        val start = clusterStarts(data)[cluster]
        val (clusterSize, clusterSizeLength) = vint(data, start + CLUSTER_ID.size)
        var at = start + CLUSTER_ID.size + clusterSizeLength
        val end = at + clusterSize.toInt()
        while (at < end) {
            val idLength = Integer.numberOfLeadingZeros(data[at].toInt() and 0xFF) - 24 + 1
            val id = data[at].toInt() and 0xFF
            val (size, sizeLength) = vint(data, at + idLength)
            val payload = at + idLength + sizeLength
            // SimpleBlock: номер дорожки (vint), 2 байта времени, флаги — затем кадр
            if (idLength == 1 && id == 0xA3 && (data[payload].toInt() and 0xFF) == 0x81) return payload + 4
            at = payload + size.toInt()
        }
        error("no video SimpleBlock in cluster $cluster")
    }

    private fun vint(data: ByteArray, at: Int): Pair<Long, Int> {
        val first = data[at].toInt() and 0xFF
        val length = Integer.numberOfLeadingZeros(first) - 24 + 1
        var value = (first and (0xFF shr length)).toLong()
        for (k in 1 until length) value = (value shl 8) or (data[at + k].toLong() and 0xFF)
        return value to length
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (k in pattern.indices) if (data[i + k] != pattern[k]) continue@outer
            return i
        }
        return -1
    }

    private fun lastIndexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in data.size - pattern.size downTo 0) {
            for (k in pattern.indices) if (data[i + k] != pattern[k]) continue@outer
            return i
        }
        return -1
    }

    // endregion

    private companion object {
        val CUES_ID = byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)
        val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
    }
}
