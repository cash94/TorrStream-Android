package my.torrstream.app.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import my.torrstream.app.App
import my.torrstream.app.BuildConfig
import my.torrstream.app.net.HttpHelper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 *
 * Промах WebView грузит сам, а кэш докачивает ту же картинку в фоне ([prefetch]). Раньше
 * промах отдавался потоком, который качал картинку прямо при чтении, — на потоке WebView.
 * Таких потоков мало, и пока они висели на медленном или молчащем зеркале (до 15 с на
 * картинку), ждали все запросы страницы, включая API: после нескольких минут простоя
 * соединения в пуле OkHttp оказывались мёртвыми, и каталог переставал грузить постеры, а
 * карточка — детали. Цена фона — второй запрос картинки, один раз, пока её нет на диске.
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

    /** Таймаут фоновой докачки: соединение — половина, чтение и весь запрос — целиком. */
    private const val PREFETCH_TIMEOUT_MS = 10_000
    /** Сколько картинок докачивается одновременно и сколько из них — с одного зеркала. */
    private const val PREFETCH_PARALLEL = 4
    private const val PREFETCH_PER_HOST = 2
    /** Больше ждущих — новые промахи не докачиваем: при быстром листании очередь устаревает. */
    private const val PREFETCH_MAX_PENDING = 64

    /** Имена картинок, которые сейчас докачиваются или ждут очереди. */
    // newSetFromMap, а не ConcurrentHashMap.newKeySet: тот появился только в API 24, minSdk — 23
    private val pending: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val client: OkHttpClient by lazy {
        // Тот же разрешительный TLS, что у остального приложения: WebView ошибки
        // сертификатов зеркал игнорирует (SysView.onReceivedSslError), кэш не должен быть строже
        HttpHelper.getOkHttpClient(PREFETCH_TIMEOUT_MS).newBuilder()
            // Простоявшее несколько минут соединение роутер может молча закрыть, и запрос по
            // нему висит до таймаута. По умолчанию OkHttp держит соединения 5 минут — дольше,
            // чем живут многие домашние NAT. 30 с хватает, чтобы переиспользовать соединение
            // при листании, и мёртвые в пуле не задерживаются.
            .connectionPool(ConnectionPool(PREFETCH_PARALLEL, 30, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply {
                maxRequests = PREFETCH_PARALLEL
                maxRequestsPerHost = PREFETCH_PER_HOST
            })
            .build()
    }

    /**
     * Ответ для WebView или null, если запрос не наш или картинки нет на диске (тогда WebView
     * грузит сам). Зовётся из shouldInterceptRequest — на фоновом потоке WebView, и сети здесь
     * нет ни в каком виде: только чтение файла с диска.
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
            if (cached == null) {
                prefetch(url, name, headers)
                return null
            }
            WebResourceResponse(mimeOf(name), null, 200, "OK", HEADERS, cached)
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

    /**
     * Докачивает картинку на диск асинхронно, в потоках OkHttp — ни поток WebView, ни
     * загрузку самой страницы это не задерживает. Сбой ничего не ломает: картинку уже
     * грузит WebView, в кэш она попадёт при следующем промахе.
     */
    private fun prefetch(url: String, name: String, headers: Map<String, String>) {
        if (pending.size >= PREFETCH_MAX_PENDING || !pending.add(name)) return
        val request = try {
            val builder = Request.Builder().url(url)
            // Заголовки WebView (User-Agent, Referer, Accept) — зеркало должно видеть тот же запрос
            // Accept-Encoding не копируем: с ним OkHttp перестаёт сам распаковывать gzip
            headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
                .forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        } catch (e: Exception) {
            pending.remove(name)
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pending.remove(name)
                if (BuildConfig.DEBUG) Log.d(TAG, "Не докачана $url: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) return
                        val body = resp.body() ?: return
                        val type = body.contentType()
                        // Зеркало, отдающее страницу ошибки с кодом 200, не должно попасть в кэш навсегда
                        if (type != null && type.type() != "image") return
                        if (body.contentLength() > MAX_IMAGE_BYTES) return
                        val bytes = body.bytes()
                        if (bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) store(name, bytes)
                    }
                } catch (e: IOException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Не докачана $url: $e")
                } finally {
                    pending.remove(name)
                }
            }
        })
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

    /** Сколько занимает кэш на диске, байт. Блокирует: при первом вызове читает папку. */
    fun sizeBytes(): Long {
        ensureLoaded()
        synchronized(lock) { return totalBytes }
    }

    /**
     * Удаляет все картинки (меню → «Очистить кэш постеров»). Возвращает освобождённые байты.
     * Картинка, которая докачивается прямо сейчас, ляжет в кэш уже после очистки — это не
     * страшно: она и так будет нужна на экране.
     */
    fun clear(): Long {
        ensureLoaded()
        synchronized(lock) {
            val freed = totalBytes
            dir.listFiles()?.forEach { if (TMP_SUFFIX !in it.name) it.delete() }
            index.clear()
            totalBytes = 0
            return freed
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
}
