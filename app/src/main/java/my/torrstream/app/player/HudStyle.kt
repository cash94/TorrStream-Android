package my.torrstream.app.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

/**
 * Вид ХУДа встроенного плеера в стиле веб-плеера (styles.css, «ПЛЕЕР: ХУД ДЛЯ ТЕЛЕВИЗОРА»).
 *
 * Фокус — цвет из «Внешнего вида» веб-интерфейса (ui-customizer.js, focusColor), поэтому фоны
 * собираются в коде, а не в drawable-ресурсах. Как и в вебе, фокус — кольцо этого цвета:
 * у кнопки — белый круг с кольцом (тёмный значок на нём даёт hud_icon_tint), у полосы
 * перемотки и строк списка — кольцо вокруг.
 *
 * Кольцо в вебе — box-shadow снаружи элемента; здесь элемент на кольцо шире
 * (dimens_player.xml), а круг без фокуса вписан внутрь. Каждый вызов отдаёт новый Drawable:
 * состояние (фокус, нажатие) у Drawable своё, общий на несколько кнопок нельзя.
 */
object HudStyle {

    /** Цвет фокуса по умолчанию — как defaultSettings.focusColor в ui-customizer.js. */
    const val DEFAULT_FOCUS_COLOR = 0xFFFF8C00.toInt()

    private const val RING_DP = 3f
    private const val TRACK_COLOR = 0xFF3A3A4A.toInt()  // #seek-slider
    private const val THUMB_COLOR = 0xFFE7E4E4.toInt()  // ::-webkit-slider-thumb

    /** «#ff8c00» (или «#f80») из веба; всё, что не разобрать, — цвет по умолчанию. */
    fun parseColor(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        val hex = when {
            Regex("^#[0-9a-fA-F]{6}$").matches(value) -> value
            Regex("^#[0-9a-fA-F]{3}$").matches(value) ->
                "#" + value.drop(1).map { "$it$it" }.joinToString("")
            else -> return DEFAULT_FOCUS_COLOR
        }
        return Color.parseColor(hex)
    }

    private fun Context.dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun rect(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
    }

    /**
     * Кнопка ХУДа. Без фокуса — прозрачная, у воспроизведения — светлая подложка
     * (.control-btn.hud-play); на фокусе — белый круг с кольцом цвета фокуса.
     */
    fun button(context: Context, focusColor: Int, play: Boolean): Drawable {
        val ring = context.dp(RING_DP)
        val focused = oval(Color.WHITE).apply { setStroke(ring, focusColor) }
        val pressed = InsetDrawable(oval(withAlpha(Color.WHITE, if (play) 0.24f else 0.1f)), ring)
        val normal: Drawable = if (play) InsetDrawable(oval(withAlpha(Color.WHITE, 0.14f)), ring)
        else ColorDrawable(Color.TRANSPARENT)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    /**
     * Полоса перемотки: тонкая серая дорожка без заливки пройденного, как в вебе; на фокусе
     * её обводит кольцо. SeekBar высотой 10dp: дорожка 4dp посередине, по 3dp на кольцо.
     */
    fun seekTrack(context: Context, focusColor: Int): Drawable {
        val ring = context.dp(RING_DP)
        val trackRadius = context.dp(2f).toFloat()
        val focused = rect(TRACK_COLOR, trackRadius).apply {
            // Обводка GradientDrawable рисуется внутри границ — это и есть кольцо вокруг дорожки
            setStroke(ring, focusColor)
            cornerRadius = trackRadius + ring
        }
        val normal = InsetDrawable(rect(TRACK_COLOR, trackRadius), ring)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun seekThumb(context: Context): Drawable = oval(THUMB_COLOR).apply {
        val size = HudScale.seekThumbPx(context)
        setSize(size, size)
    }

    /**
     * Подсветка строки, на которой пульт. ListView рисует её сам (selector) — строки состояния
     * focused не получают, поэтому кольцо фокуса задаётся списку, а не строке.
     */
    fun listSelector(context: Context, focusColor: Int): Drawable {
        val ring = context.dp(RING_DP)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rect(withAlpha(focusColor, 0.25f), 0f))
            addState(
                intArrayOf(),
                rect(withAlpha(focusColor, 0.2f), 0f).apply { setStroke(ring, focusColor) },
            )
        }
    }

    /** Строка боковой панели (серии, дорожки, настройки): текущий пункт — лёгкая подложка. */
    fun listItem(focusColor: Int): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rect(withAlpha(focusColor, 0.1f), 0f))
            addState(intArrayOf(android.R.attr.state_activated), rect(withAlpha(focusColor, 0.1f), 0f))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

    /** Текст строки: текущий пункт — цветом фокуса. */
    fun listItemText(focusColor: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(focusColor, 0xE6FFFFFF.toInt()),
    )

    /** «Пропустить заставку»: тёмная плашка, на фокусе — кольцо цвета фокуса. */
    fun skipButton(context: Context, focusColor: Int): Drawable {
        val radius = context.dp(8f).toFloat()
        val dark = 0xCC1A1A20.toInt()
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rect(dark, radius).apply { setStroke(context.dp(RING_DP), focusColor) },
            )
            addState(intArrayOf(android.R.attr.state_pressed), rect(withAlpha(focusColor, 0.25f), radius))
            addState(
                intArrayOf(),
                rect(dark, radius).apply { setStroke(context.dp(1f), 0x66FFFFFF) },
            )
        }
    }
}
