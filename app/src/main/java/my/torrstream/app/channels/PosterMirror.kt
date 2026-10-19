package my.torrstream.app.channels

import android.content.Context.MODE_PRIVATE
import android.util.Log
import androidx.core.content.edit
import okhttp3.Request
import my.torrstream.app.App
import my.torrstream.app.net.HttpHelper

/**
 * Зеркало картинок TMDB для постеров на главном экране Android TV (каналы и Watch Next).
 *
 * Постер качает лаунчер, а не приложение, — по адресу, записанному в карточку. Поэтому адрес
 * должен открываться по HTTPS: лаунчеру незашифрованный http может быть запрещён. Зеркала
 * те же, что у веба (/api/client-config, tmdbImages), но веб ходит на них по протоколу
 * страницы (http), и https у зеркала может не работать вовсе: у tsimg.torrstream.online
 * TLS-рукопожатие обрывается, и постеры каналов пропали, когда сервер поставил его первым.
 * Поэтому зеркало проверяется настоящей картинкой: берётся первое, что отдало её по https,
 * а если таких нет — по http. Выбор помнится [CACHE_MS], чтобы каждые 15 минут
 * (обновление «Моих торрентов») не проверять зеркала заново.
 */
object PosterMirror {
    private const val TAG = "PosterMirror"

    private const val PREF_BASE = "image_base"
    private const val PREF_BASE_AT = "image_base_at"
    private const val CACHE_MS = 3 * 60 * 60 * 1000L
    private const val PROBE_SIZE = "w92"

    /** Ни одно зеркало не ответило — последняя надежда. */
    private const val FALLBACK_BASE = "https://image.tmdb.org"

    private val TMDB_PATH = Regex("/t/p/([^/]+)(/[^?#]+)")

    private val prefs get() = App.context.getSharedPreferences("tv_channels", MODE_PRIVATE)
    private val client by lazy { HttpHelper.getOkHttpClient(6000) }

    /**
     * Адреса постеров для одного обновления каналов. Зеркала спрашиваются у сервера и
     * проверяются, только когда впервые нужен постер, а запомненного выбора нет.
     */
    class Resolver(private val mirrors: () -> List<String>) {
        private var base: String? = null

        /** Постер по пути TMDB («/abc.jpg») или по полному адресу TMDB/зеркала; иное — как есть. */
        fun url(pathOrUrl: String?, size: String): String? {
            val value = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
            val path = if (value.startsWith("http", ignoreCase = true)) {
                TMDB_PATH.find(value)?.groupValues?.get(2) ?: return value
            } else {
                if (value.startsWith("/")) value else "/$value"
            }
            val b = base ?: resolve(path).also { base = it }
            return "$b/t/p/$size$path"
        }

        private fun resolve(samplePath: String): String {
            cachedBase()?.let { return it }
            val hosts = try {
                mirrors()
            } catch (e: Exception) {
                emptyList()
            }
            val found = listOf("https", "http").firstNotNullOfOrNull { scheme ->
                hosts.firstNotNullOfOrNull { host ->
                    "$scheme://${host.trim().trimEnd('/')}".takeIf { works(it, samplePath) }
                }
            }
            if (found == null) {
                Log.w(TAG, "Ни одно зеркало не отдало постер: $hosts")
                return prefs.getString(PREF_BASE, null) ?: FALLBACK_BASE
            }
            Log.i(TAG, "Постеры через $found")
            prefs.edit {
                putString(PREF_BASE, found)
                putLong(PREF_BASE_AT, System.currentTimeMillis())
            }
            return found
        }
    }

    private fun cachedBase(): String? {
        val at = prefs.getLong(PREF_BASE_AT, 0L)
        if (System.currentTimeMillis() - at > CACHE_MS) return null
        return prefs.getString(PREF_BASE, null)
    }

    private fun works(base: String, samplePath: String): Boolean = try {
        val request = Request.Builder().url("$base/t/p/$PROBE_SIZE$samplePath").build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful &&
                    response.body()?.contentType()?.type()?.equals("image", ignoreCase = true) == true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Адрес постера TMDB/зеркала — на проверенное зеркало, без сети: для Watch Next, где
     * постер приходит из веба готовой ссылкой (её зеркало могло не уметь https).
     * Пока зеркало не выбрано (каналы ещё не обновлялись) — ссылка как есть.
     */
    fun rewrite(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return url
        val base = prefs.getString(PREF_BASE, null) ?: return value
        val match = TMDB_PATH.find(value) ?: return value
        return "$base/t/p/${match.groupValues[1]}${match.groupValues[2]}"
    }
}
