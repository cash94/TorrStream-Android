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
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.lang.reflect.Field
import java.lang.reflect.Method

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
 *
 * По отчётам 1.1.31–1.1.32 к этому добавлено:
 * - **Испорченный индекс (Cues).** Разборщик отдаёт форматы дорожек только после индекса, а
 *   индекс обычно в конце файла — за ним кластеров нет, и поиск кластера ничего не давал:
 *   плеер закрывался на старте или при продолжении просмотра, без единой дорожки («Художник»,
 *   «Тверская», «Другие», «Нейровася» — 9 отчётов). Теперь индекс закрывается на том, что
 *   успели прочитать ([finishDamagedCues]), и разборщик возвращается к первому кластеру. Перемотка
 *   работает по уцелевшей части индекса; не уцелело ничего — фильм идёт без перемотки.
 * - **Мусор в конце файла.** Порча после последнего кластера («Нейровася» E05 — на последней
 *   секунде) — это конец фильма: поток завершается, и плеер переходит к следующей серии.
 * - **«Top bit not zero»** — испорчена длина NAL внутри кадра (7 отчётов): тот же пропуск до
 *   следующего кластера.
 * - **Размер элемента больше 2 ГБ** (ArrayIndexOutOfBoundsException в DefaultEbmlReader) — тоже.
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
        recover(input, seekPosition, e)
    } catch (e: IllegalStateException) {
        if (!isDamagedData(e)) throw e
        recover(input, seekPosition, e)
    } catch (e: IndexOutOfBoundsException) {
        // Размер элемента — мусор больше 2 ГБ: DefaultEbmlReader пропускает его как Int, и
        // отрицательная длина роняет System.arraycopy (5 отчётов 1.1.32–1.1.38, посреди
        // серии и на старте). Ввод при этом не сдвинулся — ищем кластер, как при порче
        recover(input, seekPosition, e)
    }

    private fun recover(input: ExtractorInput, seekPosition: PositionHolder, error: Exception): Int {
        if (resyncs >= MAX_RESYNCS) throw error

        // Сломался индекс — закрыть его на прочитанном и вернуться туда, откуда за ним прыгнули
        finishDamagedCues(error)?.let { back ->
            resyncs++
            if (back != C.INDEX_UNSET.toLong()) {
                delegate.seek(back, C.TIME_UNSET)
                seekPosition.position = back
                return Extractor.RESULT_SEEK
            }
            // Индекс стоял перед кластерами — дальше обычный поиск первого кластера
        }

        val from = input.position
        return when (skipToNextCluster(input)) {
            Scan.FOUND -> {
                resyncs++
                Log.w(TAG, "Damaged data at $from (${error.message}), resumed at cluster ${input.position}, #$resyncs")
                // Состояние разборщика — на начало кластера: блок, элемент, недописанный кадр
                delegate.seek(input.position, C.TIME_UNSET)
                Extractor.RESULT_CONTINUE
            }
            Scan.END_OF_FILE -> {
                Log.w(TAG, "Damaged data at $from (${error.message}) up to the end of file, finishing")
                // Разборщик дочитает пустой хвост: допишет последний кадр и сообщит о конце
                delegate.seek(input.position, C.TIME_UNSET)
                try {
                    delegate.read(input, seekPosition)
                } catch (e: Exception) {
                    throw error
                }
            }
            Scan.NOT_FOUND -> throw error
        }
    }

    /**
     * Ошибка пришлась на индекс (Cues), который разборщик читает до выдачи форматов дорожек:
     * закрыть индекс на уже прочитанных точках — разборщик отдаст карту перемотки и форматы.
     *
     * Возвращает null — ошибка не в индексе; [C.INDEX_UNSET] — индекс закрыт, читать дальше
     * по порядку; иначе — место, откуда разборщик прыгнул за индексом (вернуться туда).
     *
     * Поля и метод MatroskaExtractor закрытые — через рефлексию (Media3 1.11.1, сборка без R8).
     * Не нашлись (другая версия Media3) — как раньше, обычный поиск кластера.
     */
    private fun finishDamagedCues(error: Exception): Long? {
        val mkv = delegate as? MatroskaExtractor ?: return null
        val inCues = IN_CUES_ELEMENT ?: return null
        val sentSeekMap = SENT_SEEK_MAP ?: return null
        val endMaster = END_MASTER_ELEMENT ?: return null
        val seekBack = SEEK_AFTER_CUES ?: return null
        return try {
            if (sentSeekMap.getBoolean(mkv)) return null
            // Внутри индекса — или разборщик прыгнул за ним, а испорчен уже его заголовок
            val cuesTrip = seekBack.getLong(mkv) != C.INDEX_UNSET.toLong()
            if (!inCues.getBoolean(mkv) && !cuesTrip) return null
            // Закрытие Cues строит карту перемотки из прочитанных точек (нет ни одной —
            // фильм без перемотки) и отдаёт форматы дорожек; assertInCues там не проверяется
            endMaster.invoke(mkv, ID_CUES)
            val back = seekBack.getLong(mkv)
            // Возврат делаем сами: разборщик сделал бы его только после ещё одного элемента
            seekBack.setLong(mkv, C.INDEX_UNSET.toLong())
            Log.w(TAG, "Damaged Cues (${error.message}), using the part read so far; back to $back")
            back
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Cannot finish damaged Cues", e)
            null
        }
    }

    private enum class Scan { FOUND, END_OF_FILE, NOT_FOUND }

    /** Пропустить вход до следующего кластера. */
    private fun skipToNextCluster(input: ExtractorInput): Scan {
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
                    return Scan.FOUND
                }
            }
            if (filled < buffer.size) {
                // Поток кончился. Это конец файла, только если дошли до его длины: оборванное
                // соединение — не повод заканчивать фильм, пусть плеер переоткроет поток
                val length = input.length
                if (length == C.LENGTH_UNSET.toLong() || input.position + filled < length) return Scan.NOT_FOUND
                input.skipFully(filled)
                return Scan.END_OF_FILE
            }
            // Хвост окна — в следующее: кластер на стыке не потеряется
            val advance = filled - OVERLAP_BYTES
            input.skipFully(advance)
            scanned += advance
        }
        return Scan.NOT_FOUND
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

    companion object {
        private const val TAG = "MatroskaResync"
        private val CLUSTER_ID = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75)
        private const val ID_CUES = 0x1C53BB6B
        private const val TIMECODE_ID = 0xE7
        private const val CRC32_ID = 0xBF
        private const val WINDOW_BYTES = 64 * 1024
        private const val OVERLAP_BYTES = 16
        private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
        private const val MAX_RESYNCS = 50

        /**
         * Порча данных, а не ошибка в логике разборщика: неверный заголовок элемента
         * («No valid varint length mask found») или длина NAL с поднятым старшим битом
         * («Top bit not zero: …», ParsableByteArray.readUnsignedIntToInt).
         */
        fun isDamagedData(e: IllegalStateException): Boolean {
            val message = e.message ?: return false
            return message.contains("varint", ignoreCase = true) ||
                    message.startsWith("Top bit not zero")
        }

        private val IN_CUES_ELEMENT: Field? = field("inCuesElement")
        private val SENT_SEEK_MAP: Field? = field("sentSeekMap")
        private val SEEK_AFTER_CUES: Field? = field("seekPositionAfterBuildingCues")
        private val END_MASTER_ELEMENT: Method? = try {
            MatroskaExtractor::class.java.getDeclaredMethod("endMasterElement", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            null
        }

        private fun field(name: String): Field? = try {
            MatroskaExtractor::class.java.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            null
        }
    }
}
