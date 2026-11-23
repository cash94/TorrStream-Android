package my.torrstream.app.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.lang.reflect.Field

/**
 * MKV, у которого индекс (Cues) найден через ЦЕПОЧКУ SeekHead, — перематываемый.
 *
 * SeekHead — оглавление Matroska: записи «элемент такой-то лежит там-то». Запись может указывать
 * на другой SeekHead, и mkvmerge так и пишет, когда индекс не помещается в начало: в голове
 * файла заглушка на 17 байт со ссылкой в конец, а уже там — Info, Tracks, Tags и Cues.
 * MatroskaExtractor ссылку не проходит: позицию Cues он запоминает только из записи, чей SeekID —
 * сами Cues. На таком файле он доходит до первого кластера без индекса и ставит
 * SeekMap.Unseekable: перемотки нет, просмотр всегда с нуля, позиция не сохраняется. VLC и mpv
 * ссылку проходят.
 *
 * Здесь цепочка читается двумя заглядываниями (peek) ДО того, как разборщик прочтёт хоть байт, и
 * позиция Cues кладётся в его закрытое поле cuesContentPosition — дальше он сам прыгает за
 * индексом, как если бы заглушка называла Cues. Порядок важен: DefaultEbmlReader закрывает
 * элементы только при движении вперёд, и прыжок в конец после открытия Segment сломал бы
 * вложенность. Если в новой Media3 поле переименуют — обёртка ничего не делает.
 *
 * Из Just+ Player (MkvCuesLink, Unlicense).
 */
@OptIn(UnstableApi::class)
class MkvCuesLink(
    private val delegate: Extractor,
    private val matroska: MatroskaExtractor,
) : Extractor {

    private var state = STATE_READ_HEAD

    /** Начало содержимого Segment: SeekPosition отсчитываются от него. */
    private var segmentContentPosition = C.POSITION_UNSET.toLong()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        when (state) {
            STATE_READ_HEAD -> {
                // Без длины в конец не прыгнуть. Не навсегда: загрузка с отложенным сбоем open()
                // (DeferredOpenDataSource) приходит сюда без длины один раз, повтор — с длиной
                if (input.length != C.LENGTH_UNSET.toLong() && input.position == 0L && CUES_FIELD != null) {
                    // Состояние меняется только после peek: оборвалась сеть — повтор сделает заново
                    val chained = chainedSeekHeadPosition(peek(input))
                    state = STATE_DONE
                    if (chained != C.POSITION_UNSET.toLong() && chained < input.length) {
                        state = STATE_READ_CHAINED_SEEK_HEAD
                        seekPosition.position = chained
                        return Extractor.RESULT_SEEK
                    }
                } else if (input.position != 0L) {
                    state = STATE_DONE
                }
            }
            STATE_READ_CHAINED_SEEK_HEAD -> {
                val cues = cuesPosition(peek(input))
                state = STATE_DONE
                if (cues != C.POSITION_UNSET.toLong()) {
                    try {
                        CUES_FIELD?.setLong(matroska, cues)
                        Log.i(TAG, "chained SeekHead: Cues at $cues")
                    } catch (e: Exception) {
                        Log.w(TAG, "cuesContentPosition: $e")
                    }
                }
                // Обратно в начало: разборщик ещё ничего не читал и ждёт заголовок EBML
                seekPosition.position = 0
                return Extractor.RESULT_SEEK
            }
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    /** Сколько есть, до [PEEK_BYTES], не сдвигая позицию чтения. */
    private fun peek(input: ExtractorInput): ByteArray {
        val buffer = ByteArray(PEEK_BYTES)
        var read = 0
        // peekFully бросает на конце файла, а второй SeekHead лежит в паре сотен байт от конца
        while (read < buffer.size) {
            val count = input.peek(buffer, read, buffer.size - read)
            if (count == C.RESULT_END_OF_INPUT) break
            read += count
        }
        input.resetPeekPosition()
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    /** Куда ведёт первый SeekHead, если сам он Cues не называет; иначе POSITION_UNSET. */
    private fun chainedSeekHeadPosition(head: ByteArray): Long = try {
        val reader = Ebml(head)
        if (reader.readId() != ID_EBML_HEADER) throw IllegalStateException("not EBML")
        reader.skip(reader.readSize())
        while (true) {
            val id = reader.readId()
            val size = reader.readSize()
            if (id == ID_SEGMENT) break
            reader.skip(size)
        }
        segmentContentPosition = reader.position.toLong()
        var result = C.POSITION_UNSET.toLong()
        while (true) {
            val id = reader.readId()
            val size = reader.readSize()
            if (id == ID_SEEK_HEAD) {
                val (cues, chained) = readSeekHead(reader, size)
                // Называет Cues — разборщику ничего не нужно, даже если есть и ссылка
                if (cues == C.POSITION_UNSET.toLong() && chained != C.POSITION_UNSET.toLong()) {
                    result = segmentContentPosition + chained
                }
                break
            }
            // Кластер или Cues раньше SeekHead — оглавления в голове нет
            if (id == ID_CLUSTER) break
            reader.skip(size)
        }
        result
    } catch (e: Exception) {
        // Кончились байты или файл устроен иначе — разборщик читает поток как всегда
        C.POSITION_UNSET.toLong()
    }

    private fun cuesPosition(bytes: ByteArray): Long = try {
        val reader = Ebml(bytes)
        if (reader.readId() != ID_SEEK_HEAD) {
            C.POSITION_UNSET.toLong()
        } else {
            val (cues, _) = readSeekHead(reader, reader.readSize())
            if (cues == C.POSITION_UNSET.toLong()) cues else segmentContentPosition + cues
        }
    } catch (e: Exception) {
        C.POSITION_UNSET.toLong()
    }

    /** Записи одного SeekHead: (Cues, следующий SeekHead), позиции от начала Segment. */
    private fun readSeekHead(reader: Ebml, size: Long): Pair<Long, Long> {
        var cues = C.POSITION_UNSET.toLong()
        var chained = C.POSITION_UNSET.toLong()
        val end = reader.position + size
        while (reader.position < end) {
            val id = reader.readId()
            val entrySize = reader.readSize()
            if (id != ID_SEEK) {
                reader.skip(entrySize)
                continue
            }
            val entryEnd = reader.position + entrySize
            var seekId = -1L
            var seekPos = C.POSITION_UNSET.toLong()
            while (reader.position < entryEnd) {
                val childId = reader.readId()
                val childSize = reader.readSize()
                // SeekID хранит ID элемента как на диске, с битами длины — как константы ниже
                when (childId) {
                    ID_SEEK_ID -> seekId = reader.readUInt(childSize)
                    ID_SEEK_POSITION -> seekPos = reader.readUInt(childSize)
                    else -> reader.skip(childSize)
                }
            }
            if (seekPos == C.POSITION_UNSET.toLong()) continue
            if (seekId == ID_CUES) cues = seekPos
            else if (seekId == ID_SEEK_HEAD) chained = seekPos
        }
        return cues to chained
    }

    /** Минимальный разбор EBML по массиву: бросает, когда байты кончились. */
    private class Ebml(private val bytes: ByteArray) {
        var position = 0
            private set

        private fun byte(): Int {
            if (position >= bytes.size) throw IndexOutOfBoundsException()
            return bytes[position++].toInt() and 0xFF
        }

        private fun lengthOf(first: Int): Int {
            var mask = 0x80
            var length = 1
            while (length <= 8 && first and mask == 0) {
                mask = mask shr 1
                length++
            }
            if (length > 8) throw IllegalStateException("bad varint")
            return length
        }

        /** ID — с битами длины, как пишется на диске. */
        fun readId(): Long {
            val first = byte()
            val length = lengthOf(first)
            if (length > 4) throw IllegalStateException("bad id")
            var value = first.toLong()
            repeat(length - 1) { value = (value shl 8) or byte().toLong() }
            return value
        }

        /** Размер — без битов длины; «неизвестный» (все единицы) — Long.MAX_VALUE. */
        fun readSize(): Long {
            val first = byte()
            val length = lengthOf(first)
            var value = (first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            repeat(length - 1) {
                val b = byte()
                allOnes = allOnes && b == 0xFF
                value = (value shl 8) or b.toLong()
            }
            return if (allOnes) Long.MAX_VALUE else value
        }

        fun readUInt(size: Long): Long {
            if (size !in 1..8) throw IllegalStateException("bad uint")
            var value = 0L
            repeat(size.toInt()) { value = (value shl 8) or byte().toLong() }
            return value
        }

        fun skip(size: Long) {
            if (size < 0 || size > bytes.size - position) throw IndexOutOfBoundsException()
            position += size.toInt()
        }
    }

    private companion object {
        const val TAG = "MkvCuesLink"

        const val ID_EBML_HEADER = 0x1A45DFA3L
        const val ID_SEGMENT = 0x18538067L
        const val ID_SEEK_HEAD = 0x114D9B74L
        const val ID_SEEK = 0x4DBBL
        const val ID_SEEK_ID = 0x53ABL
        const val ID_SEEK_POSITION = 0x53ACL
        const val ID_CUES = 0x1C53BB6BL
        const val ID_CLUSTER = 0x1F43B675L

        /**
         * SeekHead — первый потомок Segment, оглавление в несколько десятков байт. Больше не
         * надо: в конце торрент-раздачи эти байты кому-то ещё качать до первой картинки.
         */
        const val PEEK_BYTES = 16 * 1024

        const val STATE_READ_HEAD = 0
        const val STATE_READ_CHAINED_SEEK_HEAD = 1
        const val STATE_DONE = 2

        val CUES_FIELD: Field? = try {
            MatroskaExtractor::class.java.getDeclaredField("cuesContentPosition").apply { isAccessible = true }
        } catch (e: Throwable) {
            Log.w(TAG, "unavailable: $e")
            null
        }
    }
}
