package my.torrstream.app.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure

/**
 * MKV с повреждённым местом: пропустить его до следующего кластера, а не обрывать фильм.
 *
 * Разборщик Media3 на первом же неверном байте («No valid varint length mask found» —
 * обычно нули на месте заголовка элемента) бросает исключение, и загрузка умирает. В 1.1.30
 * плеер в ответ перезапускался с того же места, но отчёты показали: если испорчен сам файл
 * («Барри», «Дальнобойщики», «Художник» — TorrServer MatriX), три перезапуска подряд
 * упираются в ту же точку за секунды, и плеер закрывается. VLC и ffmpeg в таком случае ищут
 * следующий кластер и играют дальше — так же и здесь: короткий скачок вместо закрытия.
 *
 * Кластер узнаётся по ID 1F 43 B6 75, за которым идут корректный размер и первым элементом
 * Timecode (E7), возможно после CRC-32 (BF) — так случайное совпадение внутри кадра не
 * принимается за начало кластера.
 * Ищем не дальше [MAX_SCAN_BYTES]; не нашли — ошибка уходит дальше, как раньше (там её
 * ждёт перезапуск плеера).
 */
@OptIn(UnstableApi::class)
class MatroskaResyncExtractor(private val delegate: Extractor) : Extractor {

    private var resyncs = 0

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        delegate.read(input, seekPosition)
    } catch (e: ParserException) {
        resync(input, e)
    } catch (e: IllegalStateException) {
        if (e.message?.contains("varint", ignoreCase = true) != true) throw e
        resync(input, e)
    }

    private fun resync(input: ExtractorInput, error: Exception): Int {
        if (resyncs >= MAX_RESYNCS) throw error
        val from = input.position
        if (!skipToNextCluster(input)) throw error
        resyncs++
        Log.w(TAG, "Damaged data at $from (${error.message}), resumed at cluster ${input.position}, #$resyncs")
        // Состояние разборщика — на начало кластера: блок, элемент, недописанный кадр
        delegate.seek(input.position, C.TIME_UNSET)
        return Extractor.RESULT_CONTINUE
    }

    /** Пропустить вход до следующего кластера; false — не нашли (конец файла или лимит). */
    private fun skipToNextCluster(input: ExtractorInput): Boolean {
        val buffer = ByteArray(WINDOW_BYTES)
        var scanned = 0L
        while (scanned < MAX_SCAN_BYTES) {
            input.resetPeekPosition()
            var filled = 0
            while (filled < buffer.size) {
                val n = input.peek(buffer, filled, buffer.size - filled)
                if (n == C.RESULT_END_OF_INPUT) break
                filled += n
            }
            input.resetPeekPosition()
            for (i in 0..filled - CLUSTER_ID.size) {
                if (matchesCluster(buffer, i, filled)) {
                    input.skipFully(i)
                    return true
                }
            }
            if (filled < buffer.size) return false // конец файла
            // Хвост окна — в следующее: кластер на стыке не потеряется
            val advance = filled - OVERLAP_BYTES
            input.skipFully(advance)
            scanned += advance
        }
        return false
    }

    private fun matchesCluster(buffer: ByteArray, at: Int, filled: Int): Boolean {
        for (k in CLUSTER_ID.indices) if (buffer[at + k] != CLUSTER_ID[k]) return false
        // Размер кластера — vint от 1 до 8 байт (первый байт не ноль), затем ID Timecode
        val sizeAt = at + CLUSTER_ID.size
        if (sizeAt >= filled) return false
        val first = buffer[sizeAt].toInt() and 0xFF
        if (first == 0) return false
        val sizeLength = Integer.numberOfLeadingZeros(first) - 24 + 1
        var timecodeAt = sizeAt + sizeLength
        if (timecodeAt >= filled) return false
        // ffmpeg и другие муксеры первым ставят CRC-32 (BF 84 + 4 байта), Timecode — за ним
        if ((buffer[timecodeAt].toInt() and 0xFF) == CRC32_ID) {
            if (timecodeAt + 1 >= filled || (buffer[timecodeAt + 1].toInt() and 0xFF) != 0x84) return false
            timecodeAt += 2 + 4
            if (timecodeAt >= filled) return false
        }
        return (buffer[timecodeAt].toInt() and 0xFF) == TIMECODE_ID
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private companion object {
        const val TAG = "MatroskaResync"
        val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        const val TIMECODE_ID = 0xE7
        const val CRC32_ID = 0xBF
        const val WINDOW_BYTES = 64 * 1024
        const val OVERLAP_BYTES = 16
        const val MAX_SCAN_BYTES = 64L * 1024 * 1024
        const val MAX_RESYNCS = 50
    }
}
