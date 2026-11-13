package my.torrstream.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SniffFailure

/**
 * Не даёт ExoPlayer потерять переход, который запросил разборщик файла.
 *
 * Разборщик MKV сначала читает заголовок, у первого кластера прыгает в конец файла за
 * индексом (Cues) и только после индекса отдаёт форматы дорожек (Media3 1.11 — ради глав).
 * У TorrServer конец раздачи ещё не скачан, ответ на этот запрос задерживается дольше
 * таймаута, и открытие потока падает. Тогда ProgressiveMediaPeriod (Media3 1.11.1,
 * ExtractingLoadable.load, finally) записывает в позицию повтора место старого потока —
 * начало кластера, — и повтор идёт туда, а не за индексом. Разборщик считает, что уже
 * прыгнул, читает кадры, а форматов ещё нет: NullPointerException в SampleQueue.commitSample.
 * По отчётам диагностики — 8 из 21 отказа, все на MKV, через ~8,6 с после запуска.
 *
 * Обёртка помнит, куда разборщик просил перейти, и если данные пришли не оттуда — просит
 * ещё раз. Настоящая перемотка плеера идёт через [seek] и запомненное сбрасывает.
 */
@OptIn(UnstableApi::class)
class SeekGuardExtractor(private val delegate: Extractor) : Extractor {

    private var pendingSeekPosition = C.INDEX_UNSET.toLong()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val pending = pendingSeekPosition
        if (pending != C.INDEX_UNSET.toLong()) {
            if (input.position != pending) {
                // Поток открылся не там, куда просили: повторить переход
                seekPosition.position = pending
                return Extractor.RESULT_SEEK
            }
            pendingSeekPosition = C.INDEX_UNSET.toLong()
        }
        val result = delegate.read(input, seekPosition)
        if (result == Extractor.RESULT_SEEK) pendingSeekPosition = seekPosition.position
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingSeekPosition = C.INDEX_UNSET.toLong()
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}
