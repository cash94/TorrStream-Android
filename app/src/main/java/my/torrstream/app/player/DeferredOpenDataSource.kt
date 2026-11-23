package my.torrstream.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * open() не бросает: сбой открытия сообщается из первого read().
 *
 * Загрузчик Media3 (ProgressiveMediaPeriod.ExtractingLoadable.load, finally) запоминает, откуда
 * продолжить после сбоя, по позиции ExtractorInput — а после сбоя в open() это ещё ПРЕЖНИЙ
 * поток: место до перехода, который только что запросил разборщик, или поток загрузки,
 * отменённой перемоткой. Повтор читает оттуда, а разборщик уже считает, что прыгнул. У
 * TorrServer open() падает как раз так: на запрос диапазона он не отвечает, пока не скачает
 * кусок. [SeekGuardExtractor] ловит это со стороны разборщика (только его собственные переходы),
 * здесь — с источника: сбой приходит, когда загрузчик уже создал поток на нужной позиции и
 * применил переход, и записанная позиция — та, что просили. Само исключение то же, так что
 * WaitForStreamPolicy видит то же, что и раньше.
 *
 * Из Just+ Player (DeferredOpenDataSource, Unlicense). Стоит верхним в цепочке источников.
 */
@OptIn(UnstableApi::class)
class DeferredOpenDataSource private constructor(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = DeferredOpenDataSource(upstream.createDataSource())
    }

    private var openFailure: IOException? = null

    /** open() у источника выполнился: только тогда его Uri и заголовки что-то значат. */
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = try {
        upstream.open(dataSpec).also { upstreamOpen = true }
    } catch (e: IOException) {
        openFailure = e
        C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        openFailure?.let { failure ->
            openFailure = null
            throw failure
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = if (upstreamOpen) upstream.uri else null

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (upstreamOpen) upstream.responseHeaders else emptyMap()

    override fun close() {
        // Всегда, как велит контракт: загрузчик после отложенного сбоя считает источник открытым
        openFailure = null
        upstreamOpen = false
        upstream.close()
    }
}
