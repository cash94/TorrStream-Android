package my.torrstream.app.player

import android.content.Context
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/**
 * ASS/SSA через libass для ExoPlayer. Сам Media3 берёт из ASS только текст и позицию, поэтому
 * оформленные субтитры (плашки \p1, \fs, вложенные в MKV шрифты) выходили кашей из команд
 * рисования. libVLC это не касается: у него libass свой.
 *
 * Собрано вручную, а не через buildWithAssSupport: тот не отдаёт AssHandler (нечего освобождать
 * при пересборке плеера), кладёт слой на весь SubtitleView (нам нужна рамка видео — надписи
 * привязаны к кадру, см. [fitTo]) и подменяет фабрику экстракторов так, что теряется выбор
 * экстрактора по адресу и заголовкам.
 *
 * OVERLAY_CANVAS: отрисовка в своём потоке, в UI-поток приходят только готовые битмапы.
 *
 * Очередь серий. Дорожки ASS живут в одном AssHandler по id дорожки, а id у серий совпадают.
 * Плеер начинает разбирать следующую серию, как только докачан конец текущей, и её дорожка
 * подменила бы текущую за несколько минут до титров. Поэтому libass получает только файл,
 * который сейчас играет ([currentItem]), а подгруженная заранее серия разбирается обычным
 * MatroskaExtractor. Если в ней нашёлся ASS, при переходе плеер пересобирается ([attach]): заодно
 * сбрасывается смещение времени — AssRenderer считает его от начала первой серии в очереди.
 */
@OptIn(UnstableApi::class)
class AssSubtitles(context: Context) {

    private val handler = AssHandler(
        AssRenderType.OVERLAY_CANVAS,
        AssHandlerConfig(maxRenderPixels = MAX_RENDER_PIXELS),
    )
    private val parsers = AssSubtitleParserFactory(handler)
    private val view = AssSubtitleView(context, handler).apply {
        isFocusable = false
    }

    /** Что играет: пишет главный поток, читают потоки загрузки при разборе файла. */
    @Volatile
    private var currentItem: MediaItem? = null

    /** В подгруженной заранее серии есть ASS, который libass не достался. */
    @Volatile
    private var preloadedAss = false

    /** Разбор подгруженной заранее серии: только отмечает, что в ней есть ASS. */
    private val preloadParsers = object : SubtitleParser.Factory {
        private val base = DefaultSubtitleParserFactory()

        override fun supportsFormat(format: Format) = base.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format) = base.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser {
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) preloadedAss = true
            return base.create(format)
        }
    }

    /** Выбрана ли дорожка ASS: реплик в player.currentCues у неё нет. */
    val hasSelectedTrack: Boolean
        get() = handler.track != null

    /** Фабрика на каждый элемент очереди: разбору файла надо знать, чей он. */
    fun mediaSourceFactory(dataSource: DataSource.Factory): MediaSource.Factory {
        fun factoryFor(item: MediaItem?) =
            DefaultMediaSourceFactory(dataSource, AssExtractors(item)).setSubtitleParserFactory(parsers)

        return object : MediaSource.Factory by factoryFor(null) {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource =
                factoryFor(mediaItem).createMediaSource(mediaItem)
        }
    }

    fun renderersFactory(base: RenderersFactory): RenderersFactory = base.withAssSupport(handler)

    /**
     * Подключить к плееру. [onPreloadedAss] — перешли на серию, разобранную заранее без libass:
     * плеер надо пересобрать.
     */
    fun attach(player: ExoPlayer, onPreloadedAss: () -> Unit) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentItem = mediaItem
                val rebuild = preloadedAss
                preloadedAss = false
                if (rebuild) onPreloadedAss()
            }
        })
        handler.init(player)
    }

    /** Слой поверх видео, в той же рамке, что и поверхность: положить рядом с ней. */
    fun addTo(parent: ViewGroup) {
        parent.addView(view, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
    }

    /** Рамка поверхности видео: надписи и плашки стоят на своём месте в кадре при любом масштабе. */
    fun fitTo(width: Int, height: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        view.layoutParams = lp
    }

    /** Настройка размера субтитров; надписи в кадре вырастут вместе с репликами. */
    fun setFontScale(scale: Float) {
        handler.render?.setFontScale(scale)
    }

    /** Звать после player.release(): иначе отрисовка ещё может обратиться к дорожке. */
    fun release() {
        (view.parent as? ViewGroup)?.removeView(view)
        handler.release()
    }

    private inner class AssExtractors(private val item: MediaItem?) : ExtractorsFactory {
        private val base = DefaultExtractorsFactory()

        override fun createExtractors(): Array<Extractor> = withAss(base.createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            withAss(base.createExtractors(uri, responseHeaders))

        private fun withAss(extractors: Array<Extractor>): Array<Extractor> {
            val mkv = if (item == currentItem) AssMatroskaExtractor(parsers, handler)
            else MatroskaExtractor(preloadParsers)
            extractors.forEachIndexed { i, extractor ->
                if (extractor is MatroskaExtractor) extractors[i] = mkv
            }
            return extractors
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /** На 4K-экране рисуем как на 1080p и растягиваем: libass считает на процессоре. */
        const val MAX_RENDER_PIXELS = 1920 * 1080
    }
}
