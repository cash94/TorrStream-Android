package my.torrstream.app.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import my.torrstream.app.net.HttpHelper
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Трейлеры RuTube с устройства, мимо прокси сервера.
 *
 * Сам RuTube страницу не пускает: ни API, ни плейлисты, ни сегменты не отдают заголовок
 * Access-Control-Allow-Origin, и fetch или hls.js из WebView ответа не получат. Поэтому такие
 * запросы приложение выполняет само и возвращает странице с этим заголовком. Веб узнаёт о режиме
 * через AndroidJS.trailerDirect() и в APK без него продолжает ходить через сервер.
 *
 * Сети на потоке WebView нет: их мало, и ожидание ответа здесь задержало бы картинки каталога
 * (см. ImageCache). Ответ отдаётся сразу, а соединение открывает первое чтение тела. Код ответа
 * к этому моменту неизвестен, поэтому сбой — это ошибка чтения: fetch и hls.js видят сетевую
 * ошибку и идут своим обычным запасным путём.
 */
object TrailerDirect {
    private const val TIMEOUT_MS = 20_000
    private const val MIME_PLAYLIST = "application/vnd.apple.mpegurl"
    private const val MIME_SEGMENT = "video/mp2t"
    private const val MIME_JSON = "application/json"

    private val CORS_HEADERS = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Range, Content-Type",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
    )

    private val client: OkHttpClient by lazy { HttpHelper.getOkHttpClient(TIMEOUT_MS) }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url ?: return null
        if (!isTrailerRequest(url)) return null
        return when (request.method) {
            "GET" -> WebResourceResponse(mimeOf(url), null, 200, "OK", CORS_HEADERS, LazyBody(url.toString()))
            "OPTIONS" -> WebResourceResponse(
                "text/plain", null, 200, "OK", CORS_HEADERS, ByteArrayInputStream(ByteArray(0))
            )
            else -> null
        }
    }

    /** API, балансировщик и плейлисты с сегментами. Картинки RuTube идут как обычно. */
    private fun isTrailerRequest(url: Uri): Boolean {
        val host = url.host ?: return false
        val path = url.path.orEmpty()
        return when {
            host == "rutube.ru" -> path.startsWith("/api/")
            host == "bl.rutube.ru" -> true
            host.endsWith(".rutube.ru") || host.endsWith(".rtbcdn.ru") -> path.contains("/hls-vod/")
            else -> false
        }
    }

    private fun mimeOf(url: Uri): String {
        val path = url.path.orEmpty()
        return when {
            path.endsWith(".m3u8") -> MIME_PLAYLIST
            path.endsWith(".ts") -> MIME_SEGMENT
            else -> MIME_JSON
        }
    }

    /** Поток, открывающий соединение при первом чтении — уже на потоке чтения WebView. */
    private class LazyBody(private val url: String) : InputStream() {
        private var call: Call? = null
        private var response: Response? = null
        private var stream: InputStream? = null

        private fun source(): InputStream {
            stream?.let { return it }
            val newCall = client.newCall(Request.Builder().url(url).build())
            call = newCall
            val resp = newCall.execute()
            response = resp
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
            val body = resp.body() ?: throw IOException("пустой ответ")
            return body.byteStream().also { stream = it }
        }

        override fun read(): Int = source().read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            source().read(buffer, offset, length)

        override fun close() {
            // Страница ушла, не дочитав (трейлер сняли): соединение незачем держать
            call?.cancel()
            response?.close()
        }
    }
}
