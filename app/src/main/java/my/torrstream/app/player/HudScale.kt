package my.torrstream.app.player

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import my.torrstream.app.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Размеры ХУДа от ширины экрана — как у веб-плеера (styles.css, «ПЛЕЕР: ХУД ДЛЯ ТЕЛЕВИЗОРА»),
 * где они в vw, а у кнопок ещё и с нижней границей в пикселях (min-width).
 *
 * values-w900dp/dimens_player.xml держит те же числа, но посчитанные для экрана 960dp. На
 * телевизоре с другой плотностью (1280dp при плотности 1,5 — частый случай) веб-плеер
 * крупнее, а ХУД из ресурсов оставался размером под 960dp. Поэтому на широком экране
 * размеры пересчитываются здесь по формулам веба; на узком (телефон, до 900dp) веб берёт
 * пиксели как есть — их уже дают ресурсы.
 *
 * Звать до padControlsForCutout (он запоминает отступы как базовые) и до applyHudStyle.
 */
object HudScale {

    /** Граница @media (max-width: 900px) веба. */
    private const val NARROW_MAX_DP = 900

    /** Экран, под который посчитаны values-w900dp (и фиксированные dp в разметке). */
    private const val BASE_DP = 960f

    private class Metrics(context: Context) {
        val wide = context.resources.configuration.screenWidthDp > NARROW_MAX_DP
        private val vwPx = context.resources.displayMetrics.widthPixels / 100f
        private val density = context.resources.displayMetrics.density

        /** Во сколько раз экран шире 960dp — для отступов, которые в разметке в dp. */
        val k = max(1f, context.resources.configuration.screenWidthDp / BASE_DP)

        fun vw(value: Float) = (value * vwPx).roundToInt()
        fun dp(value: Float) = (value * density).roundToInt()
        fun vwF(value: Float) = value * vwPx
    }

    /** Ползунок перемотки: 1.2vw, но не меньше 14px (::-webkit-slider-thumb). */
    fun seekThumbPx(context: Context): Int {
        val m = Metrics(context)
        val min = m.dp(14f)
        return if (m.wide) max(min, m.vw(1.2f)) else min
    }

    fun apply(root: View) {
        val m = Metrics(root.context)
        if (!m.wide) return
        val ring = m.dp(3f) // кольцо фокуса снаружи круга, см. dimens_player.xml

        // Кнопки: .control-btn 3.6vw (не меньше 40px), значок 1.75vw (не меньше 20px);
        // воспроизведение 5vw (54px) со значком 2.3vw (24px). Отступы — 1.1vw и 1.8vw.
        val btn = max(m.dp(40f), m.vw(3.6f)) + 2 * ring
        val btnPad = (btn - max(m.dp(20f), m.vw(1.75f))) / 2
        val play = max(m.dp(54f), m.vw(5f)) + 2 * ring
        val playPad = (play - max(m.dp(24f), m.vw(2.3f))) / 2
        val btnGap = max(0, m.vw(1.1f) - 2 * ring)
        val playGap = max(0, m.vw(1.8f) - 2 * ring)

        val buttons = listOf(
            R.id.btnPrevEpisode, R.id.btnRewind, R.id.btnPlayPause, R.id.btnForward,
            R.id.btnNextEpisode, R.id.btnEpisodes, R.id.btnTracks, R.id.btnSettings, R.id.btnResize,
        )
        buttons.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            val isPlay = id == R.id.btnPlayPause
            val size = if (isPlay) play else btn
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                width = size
                height = size
                marginStart = when (id) {
                    R.id.btnPrevEpisode -> 0
                    R.id.btnPlayPause, R.id.btnForward -> playGap
                    else -> btnGap
                }
            }
            val pad = if (isPlay) playPad else btnPad
            view.setPadding(pad, pad, pad, pad)
        }

        // Шапка: заголовок 1.9vw, статистика 1.2vw, слева 3.5vw, сверху 1.6vw, справа место
        // под часы (right: 11vw); часы 1.9vw в 1.7vw от верха и 2vw от края
        root.findViewById<View>(R.id.playerHeader)?.updatePadding(
            left = m.vw(3.5f), top = m.vw(1.6f), right = m.vw(11f), bottom = m.dp(48f * m.k),
        )
        root.findViewById<TextView>(R.id.playerTitle)?.px(m.vwF(1.9f))
        root.findViewById<TextView>(R.id.playerStats)?.let {
            it.px(m.vwF(1.2f))
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = m.dp(6f * m.k) }
        }
        root.findViewById<TextView>(R.id.playerClock)?.let {
            it.px(m.vwF(1.9f))
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = m.vw(1.7f)
                marginEnd = m.vw(2f)
            }
        }

        // Низ: поля 3.5vw по бокам и 2.2vw снизу (минус кольцо — кнопки на него шире),
        // время 1.55vw, строка буфера 1.3vw, кнопки в 1.1vw под полосой перемотки
        root.findViewById<View>(R.id.controlsPanel)?.updatePadding(
            left = m.vw(3.5f) - ring, right = m.vw(3.5f) - ring,
            top = m.dp(48f * m.k), bottom = max(0, m.vw(2.2f) - ring),
        )
        root.findViewById<TextView>(R.id.remainingText)?.let {
            it.px(m.vwF(1.3f))
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = m.dp(8f * m.k) }
        }
        root.findViewById<TextView>(R.id.positionText)?.px(m.vwF(1.55f))
        root.findViewById<TextView>(R.id.durationText)?.px(m.vwF(1.55f))
        root.findViewById<View>(R.id.seekBar)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            // У SeekBar свои 7dp поля по бокам — они входят в 1.2vw веба
            val gap = max(0, m.vw(1.2f) - m.dp(7f))
            marginStart = gap
            marginEnd = gap
        }
        root.findViewById<View>(R.id.buttonsRow)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = max(0, m.vw(1.1f) - ring)
        }

        // «← Назад для выхода» (0.95vw) и плашка «Нажмите «Назад» ещё раз» (1.5vw)
        root.findViewById<TextView>(R.id.playerHint)?.let {
            it.px(m.vwF(0.95f))
            it.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = m.vw(0.5f) }
        }
        listOf(R.id.exitAlert, R.id.scaleToast).forEach { id ->
            root.findViewById<TextView>(id)?.let {
                it.px(m.vwF(1.5f))
                it.setPadding(m.vw(1.6f), m.vw(0.7f), m.vw(1.6f), m.vw(0.7f))
            }
        }
        root.findViewById<View>(R.id.exitAlert)?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = m.vw(6f)
        }
    }

    /** Текст в пикселях, как в вебе: системный масштаб шрифта веб-плеер тоже не трогает. */
    private fun TextView.px(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
}
