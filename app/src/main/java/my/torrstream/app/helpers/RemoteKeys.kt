package my.torrstream.app.helpers

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import my.torrstream.app.helpers.Prefs.appPrefs

/**
 * Какие кнопки пульта работают как «Назад».
 *
 * Системная «Назад» — KEYCODE_BACK, но RETURN на ИК-пультах китайских приставок (Tanix, X96
 * и их клоны) прошивка часто назначает на другой код: ESCAPE, «B» геймпада или вообще
 * нестандартный, которого нет в раскладке (тогда keyCode = 0, и узнать кнопку можно только
 * по скан-коду). Такие нажатия приложение раньше молча пропускало.
 *
 * Известные коды распознаются сами; любую другую кнопку можно назначить в меню приложения
 * («Кнопка „Назад“ на пульте») — она запоминается по коду или, если кода нет, по скан-коду.
 */
object RemoteKeys {
    private const val CUSTOM_CODE_KEY = "back_key_code"
    private const val CUSTOM_SCAN_KEY = "back_key_scan"

    /** Коды, которые и так означают «Назад» у пультов, клавиатур и геймпадов. */
    private val BACK_CODES = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_NAVIGATE_OUT,
    )

    /**
     * Кнопки, которые назначить «Назадом» нельзя: навигация, ОК, цифры, «Стереть» (ею
     * стирают текст в поиске), меню, громкость, питание.
     */
    fun isReserved(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE -> true
        else -> keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
    }

    /** Код назначенной кнопки, KEYCODE_UNKNOWN — если назначена по скан-коду, -1 — не назначена. */
    private fun Context.customCode(): Int = appPrefs.getInt(CUSTOM_CODE_KEY, -1)
    private fun Context.customScan(): Int = appPrefs.getInt(CUSTOM_SCAN_KEY, -1)

    /** Назначенная кнопка словами — для окна назначения. */
    fun describeCustom(context: Context): String? {
        val code = context.customCode()
        if (code < 0) return null
        return describe(code, context.customScan())
    }

    fun describe(event: KeyEvent): String = describe(event.keyCode, event.scanCode)

    private fun describe(keyCode: Int, scanCode: Int): String =
        "${KeyEvent.keyCodeToString(keyCode)} (keyCode $keyCode, scanCode $scanCode)"

    fun saveCustom(context: Context, event: KeyEvent) = context.appPrefs.edit {
        putInt(CUSTOM_CODE_KEY, event.keyCode)
        putInt(CUSTOM_SCAN_KEY, event.scanCode)
    }

    fun clearCustom(context: Context) = context.appPrefs.edit {
        remove(CUSTOM_CODE_KEY)
        remove(CUSTOM_SCAN_KEY)
    }

    /**
     * Нажатие кнопки-псевдонима, переписанное в KEYCODE_BACK; null — это не псевдоним (или уже
     * сама «Назад»). Время, повтор, флаги (в том числе «долгое нажатие») и устройство
     * сохраняются — удержание работает так же, как у системной кнопки.
     */
    fun toBack(context: Context, event: KeyEvent): KeyEvent? {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || !isBack(context, event)) return null
        return KeyEvent(
            event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK,
            event.repeatCount, event.metaState, event.deviceId, event.scanCode,
            event.flags, event.source
        )
    }

    /** Нажатие — «Назад»: системная кнопка, известный заменитель или назначенная кнопка. */
    fun isBack(context: Context, event: KeyEvent): Boolean {
        if (event.keyCode in BACK_CODES) return true
        val code = context.customCode()
        if (code < 0) return false
        // Кода нет в раскладке прошивки — сравниваем по скан-коду
        return if (code == KeyEvent.KEYCODE_UNKNOWN)
            event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode == context.customScan()
        else
            event.keyCode == code
    }
}
