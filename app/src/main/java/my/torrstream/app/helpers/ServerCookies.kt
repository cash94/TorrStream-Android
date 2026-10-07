package my.torrstream.app.helpers

import android.webkit.CookieManager

/**
 * Cookie из WebView для нативных запросов к серверу TorrStream.
 *
 * Когда на сервере включён вход по паролю, всё API, кроме входа, отвечает 401
 * без сессии. Страница после входа получает HttpOnly-cookie `ts_auth`, а каналы
 * Android TV и встроенный плеер ходят на тот же сервер своими запросами —
 * без неё. Берём cookie у CookieManager: он отдаёт и HttpOnly, и только те,
 * что относятся к хосту адреса, так что чужим серверам ничего не уйдёт.
 */
object ServerCookies {
    fun forUrl(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        // WebView нет или он обновляется — запрос уйдёт без входа, как раньше
        null
    }
}
