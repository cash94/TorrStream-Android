package my.torrstream.app.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import my.torrstream.app.App
import my.torrstream.app.BuildConfig
import my.torrstream.app.net.HttpHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Дисковый кэш картинок TMDB для WebView — то, что делает HTTP-кэш браузера на ПК.
 *
 * WebView работает с LOAD_NO_CACHE (страница и скрипты всегда свежие), поэтому без этого
 * кэша каждый постер на каждом экране заново шёл в сеть. Картинки TMDB неизменны: один путь —
 * один файл навсегда, проверять свежесть незачем. Ключ — путь от «/t/p/» («/t/p/w342/abc.jpg»)
 * без хоста: один и тот же постер с любого зеркала — одна запись, и переключение зеркал
 * кэш не сбрасывает.
 *
 * Вытеснение — LRU: при переполнении ([MAX_ENTRIES] файлов или [MAX_BYTES]) удаляются
 * картинки, которые дольше всех не показывались. Порядок переживает перезапуск — это mtime
 * файла, его обновляем при каждом попадании.
 */
object ImageCache {
    private const val TAG = "ImageCache"
    private const val MAX_ENTRIES = 2500
    private const val MAX_BYTES = 100L * 1024 * 1024
    /** Больше — не кэшируем: это уже не постер, и одна такая запись вытеснила бы десятки. */
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val PATH_MARK = "/t/p/"
    private const val TMP_SUFFIX = ".tmp"

    /** «w342/abc.jpg»: размер и имя файла TMDB, больше в пути ничего быть не должно. */
    private val PATH_RE = Regex("^[a-z0-9]+/[A-Za-z0-9_-]+\\.(jpe?g|png|webp|svg)$")

    private val HEADERS = mapOf(
        // Картинку может прочитать и canvas/fetch с другого источника — как с самого зеркала
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "max-age=31536000, immutable",
    )

    private val dir by lazy { File(App.context.cacheDir, "images") }

    /** Имя файла → размер. accessOrder: итерация идёт от самой давней картинки к свежей. */
    private val index = LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true)
    private var totalBytes = 0L
    private var loaded = false
    private val lock = Any()

    private val client: OkHttpClient by lazy {
        // Тот же разрешительный TLS, что у остального приложения: WebView ошибки
        // сертификатов зеркал игнорирует (SysView.onReceivedSslError), кэш не должен быть строже
        HttpHelper.getOkHttpClient(HttpHelper.DEFAULT_CONNECTION_TIMEOUT)
    }

    /**
     * Ответ для WebView или null, если запрос не наш (тогда WebView грузит сам).
     * Зовётся из shouldInterceptRequest — на фоновом потоке.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.isForMainFrame) return null
        val url = request.url?.toString() ?: return null
        val name = fileNameOf(url) ?: return null
        val headers = request.requestHeaders.orEmpty()
        if (headers.keys.any { it.equals("Range", ignoreCase = true) }) return null

        return try {
            ensureLoaded()
            val cached = openCached(name)
            // Промах: сеть — уже при чтении ответа, на потоке, который читает поток. Здесь
            // блокироваться нельзя: потоков у shouldInterceptRequest мало, и одна медленная
            // картинка задержала бы все остальные запросы страницы.
            val data = cached ?: LazyStream { download(url, name, headers) }
            WebResourceResponse(mimeOf(name), null, 200, "OK", HEADERS, data)
        } catch (e: Exception) {
            Log.w(TAG, "intercept $url", e)
            null
        }
    }

    /** «https://зеркало/image/t/p/w342/abc.jpg?x» → «w342_abc.jpg», не картинка TMDB — null. */
    private fun fileNameOf(url: String): String? {
        val i = url.indexOf(PATH_MARK)
        if (i < 0) return null
        val path = url.substring(i + PATH_MARK.length).substringBefore('?').substringBefore('#')
        if (!PATH_RE.matches(path)) return null
        return path.replace('/', '_')
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    /** Индекс с диска: порядок LRU — по mtime файлов. Один раз на процесс. */
    private fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            dir.mkdirs()
            val files = dir.listFiles().orEmpty()
            files.filter { TMP_SUFFIX in it.name }.forEach { it.delete() } // недописанные
            files.filter { TMP_SUFFIX !in it.name }
                .sortedBy { it.lastModified() }
                .forEach { f ->
                    val size = f.length()
                    index[f.name] = size
                    totalBytes += size
                }
            trimLocked()
            if (BuildConfig.DEBUG) Log.d(TAG, "Загружено ${index.size} картинок, ${totalBytes / 1024} КБ")
        }
    }

    private fun openCached(name: String): InputStream? {
        synchronized(lock) { if (index[name] == null) return null } // get() двигает запись в конец LRU
        val file = File(dir, name)
        return try {
            val stream = FileInputStream(file)
            file.setLastModified(System.currentTimeMillis())
            stream
        } catch (e: IOException) {
            // Файл стёрла система (чистка кэша при нехватке места) — забываем запись
            forget(name)
            null
        }
    }

    private fun download(url: String, name: String, headers: Map<String, String>): InputStream {
        val builder = Request.Builder().url(url)
        // Заголовки WebView (User-Agent, Referer, Accept) — зеркало должно видеть тот же запрос
        // Accept-Encoding не копируем: с ним OkHttp перестаёт сам распаковывать gzip
        headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
            .forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
            val body = resp.body() ?: throw IOException("Пустой ответ")
            val type = body.contentType()
            // Зеркало, отдающее страницу ошибки с кодом 200, не должно попасть в кэш навсегда
            if (type != null && type.type() != "image") throw IOException("Не картинка: $type")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IOException("Пустой ответ")
            if (bytes.size <= MAX_IMAGE_BYTES) store(name, bytes)
            return ByteArrayInputStream(bytes)
        }
    }

    private fun store(name: String, bytes: ByteArray) {
        try {
            val tmp = File(dir, name + TMP_SUFFIX + Thread.currentThread().id)
            tmp.writeBytes(bytes)
            val file = File(dir, name)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return
            }
            synchronized(lock) {
                index.put(name, bytes.size.toLong())?.let { totalBytes -= it }
                totalBytes += bytes.size
                trimLocked()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Не сохранена $name", e)
        }
    }

    private fun forget(name: String) {
        synchronized(lock) { index.remove(name)?.let { totalBytes -= it } }
    }

    /** Вытесняет самые давние картинки, пока кэш не уложится в оба предела. */
    private fun trimLocked() {
        val it = index.entries.iterator()
        while ((index.size > MAX_ENTRIES || totalBytes > MAX_BYTES) && it.hasNext()) {
            val eldest = it.next()
            File(dir, eldest.key).delete()
            totalBytes -= eldest.value
            it.remove()
        }
    }

    /**
     * Поток, который открывает источник при первом чтении. Ошибка сети отсюда — это ошибка
     * загрузки картинки для страницы: у <img> срабатывает onerror, и каталог идёт на
     * следующее зеркало, как и без кэша.
     */
    private class LazyStream(private val open: () -> InputStream) : InputStream() {
        private var source: InputStream? = null
        private fun src() = source ?: open().also { source = it }
        override fun read(): Int = src().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = src().read(b, off, len)
        override fun available(): Int = source?.available() ?: 0
        override fun close() {
            source?.close()
        }
    }
}
