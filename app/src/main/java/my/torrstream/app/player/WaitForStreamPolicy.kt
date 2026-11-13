package my.torrstream.app.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Пока TorrServer не отдаёт данные, плеер ждёт, а не закрывается.
 *
 * По умолчанию ExoPlayer после трёх-шести неудачных попыток загрузки (таймаут, обрыв,
 * отказ соединения) выдаёт ошибку, и плеер закрывался — так было с медленной 4K-раздачей
 * (отчёт Haier: SocketTimeoutException). Для торрента это обычное дело: кусок ещё качается.
 * Теперь сетевые ошибки повторяются без счёта (пауза растёт до 5 с), на экране — загрузка,
 * выйти можно кнопкой «Назад».
 *
 * Не повторяются, как и раньше, ошибки, от которых ожидание не спасёт: разбор файла
 * (ParserException), позиция за концом файла — и ответы 4xx (нет такой раздачи или файла),
 * кроме 408 и 429.
 */
@OptIn(UnstableApi::class)
class WaitForStreamPolicy : DefaultLoadErrorHandlingPolicy(Int.MAX_VALUE) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        if (error is HttpDataSource.InvalidResponseCodeException &&
            error.responseCode in 400..499 &&
            error.responseCode !in RETRIABLE_CLIENT_ERRORS
        ) {
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object {
        val RETRIABLE_CLIENT_ERRORS = setOf(408, 429)
    }
}
