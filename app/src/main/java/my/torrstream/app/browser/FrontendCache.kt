package my.torrstream.app.browser

import android.net.Uri
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
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Модули интерфейса (js/css/шрифты/картинки с cash94.github.io/msx) на диске устройства.
 *
 * WebView работает с LOAD_NO_CACHE, так что без этого кэша все ~45 модулей при каждом
 * запуске заново качались с GitHub. Проверять «изменился ли модуль» отдельно не нужно:
 * index.html уже подписывает каждый модуль хешем содержимого — «js/app.js?v=8fcbb2d4»
 * (карта ASSET_V, её пишет tools/legacy-build во фронтенде). Новый хеш — новый файл,
 * старый хеш того же модуля сразу удаляется: на диске всегда одна версия каждого модуля.
 *
 * Два вида запросов:
 *  - с ?v=<8 hex> — неизменны, отдаём с диска без единого запроса в сеть;
 *  - без хеша, но статика (шрифты, картинки категорий) — отдаём с диска, а раз в
 *    [REVALIDATE_MS] в фоне спрашиваем GitHub по ETag, не поменялись ли.
 * Остальное (cache/bing.json, html, js без хеша) не трогаем — WebView грузит сам.
 *
 * Промах WebView грузит сам, а кэш докачивает файл в фоне — как в [ImageCache], и по той
 * же причине: сеть на потоке WebView держит все остальные запросы страницы. Цена — второй
 * запрос модуля, один раз на его новую версию.
 */
object FrontendCache {
    private const val TAG = "FrontendCache"
    private const val HOST = "cash94.github.io"
    private const val PREFIX = "/msx/"
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024
    /** Весь фронтенд — пара мегабайт; больше — значит что-то пошло не так, начинаем с нуля. */
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private const val REVALIDATE_MS = 24L * 60 * 60 * 1000
    private const val TMP_SUFFIX = ".tmp"
    private const val ETAG_SUFFIX = ".etag"
    /** Разделитель «путь@версия» в имени файла: URLEncoder кодирует «@» в путях, путаницы нет. */
    private const val SEP = '@'

    private const val TIMEOUT_MS = 15_000
    private const val PARALLEL = 4

    /** assetV() даёт ровно 8 hex; запасной ?v=Date.now() — 13 цифр, его не кэшируем. */
    private val HASH_RE = Regex("^[0-9a-f]{8}$")
    private val PATH_RE = Regex("^[A-Za-z0-9._/-]+$")
    private val STATIC_EXT = setOf("woff2", "woff", "ttf", "eot", "otf", "png", "jpg", "jpeg", "webp", "svg", "ico", "gif")
    private val HASHED_EXT = STATIC_EXT + setOf("js", "css")

    private val dir by lazy { File(App.context.cacheDir, "frontend") }

    /** Путь модуля («js/app.js») → имя файла на диске. */
    private val index = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false
    private val lock = Any()

    private val pending: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val client: OkHttpClient by lazy {
        HttpHelper.getOkHttpClient(TIMEOUT_MS).newBuilder()
            .connectionPool(ConnectionPool(PARALLEL, 30, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply {
                maxRequests = PARALLEL
                maxRequestsPerHost = PARALLEL
            })
            .build()
    }

    @Volatile private var hits = 0

    /**
     * Ответ с диска или null (запрос не наш или файла ещё нет — тогда WebView грузит сам).
     * Зовётся из shouldInterceptRequest на фоновом потоке WebView: здесь только диск.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.isForMainFrame) return null
        val uri = request.url ?: return null
        if (!HOST.equals(uri.host, ignoreCase = true)) return null
        val fullPath = uri.path ?: return null
        if (!fullPath.startsWith(PREFIX)) return null
        val path = fullPath.substring(PREFIX.length)
        if (!PATH_RE.matches(path) || ".." in path || path.startsWith("cache/")) return null
        val headers = request.requestHeaders.orEmpty()
        if (headers.keys.any { it.equals("Range", ignoreCase = true) }) return null

        val ext = path.substringAfterLast('.', "").lowercase()
        val version = versionOf(uri, ext) ?: return null

        return try {
            ensureLoaded()
            val name = fileName(path, version)
            val file = File(dir, name)
            val stream = if (index[path] == name) open(file) else null
            if (stream == null) {
                fetch(uri.toString(), path, version, headers, etag = null)
                return null
            }
            // Статика без хеша: раз в сутки в фоне спрашиваем, не обновилась ли
            if (version.isEmpty() && System.currentTimeMillis() - file.lastModified() > REVALIDATE_MS) {
                fetch(uri.toString(), path, version, headers, etag = readEtag(name))
            }
            if (BuildConfig.DEBUG && ++hits % 20 == 0) Log.d(TAG, "С диска уже $hits модулей")
            val mime = mimeOf(ext)
            val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null
            WebResourceResponse(mime, encoding, 200, "OK", headersFor(version), stream)
        } catch (e: Exception) {
            Log.w(TAG, "intercept $uri", e)
            null
        }
    }

    /**
     * «v» из запроса: хеш — для модулей с ?v=, пустая строка — для статики без запроса,
     * null — не кэшируем (Date.now(), лишние параметры, js без хеша).
     */
    private fun versionOf(uri: Uri, ext: String): String? {
        val params = uri.queryParameterNames
        if (params.isEmpty()) return if (ext in STATIC_EXT) "" else null
        if (params.size != 1) return null
        val v = uri.getQueryParameter("v") ?: return null
        return if (HASH_RE.matches(v) && ext in HASHED_EXT) v else null
    }

    private fun fileName(path: String, version: String) =
        URLEncoder.encode(path, "UTF-8") + SEP + version

    private fun headersFor(version: String) = mapOf(
        // Шрифты @font-face с другого источника без CORS браузер не примет — GitHub его даёт
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to if (version.isNotEmpty()) "max-age=31536000, immutable" else "no-cache",
    )

    private fun mimeOf(ext: String) = when (ext) {
        "js" -> "application/javascript"
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "eot" -> "application/vnd.ms-fontobject"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "image/jpeg"
    }

    private fun open(file: File): InputStream? = try {
        FileInputStream(file)
    } catch (e: IOException) {
        null // файл стёрла система при чистке кэша — докачаем
    }

    /** Индекс с диска, один раз на процесс. */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            dir.mkdirs()
            val files = dir.listFiles().orEmpty()
            files.filter { TMP_SUFFIX in it.name }.forEach { it.delete() }
            val data = files.filter { TMP_SUFFIX !in it.name && !it.name.endsWith(ETAG_SUFFIX) }
            if (data.sumOf { it.length() } > MAX_TOTAL_BYTES) {
                files.forEach { it.delete() }
            } else {
                // Два файла одного модуля (сбой между записью нового и удалением старого) —
                // оставляем свежий, его скачали последним
                data.sortedBy { it.lastModified() }.forEach { f ->
                    val i = f.name.lastIndexOf(SEP)
                    if (i <= 0) { f.delete(); return@forEach }
                    val path = try { URLDecoder.decode(f.name.substring(0, i), "UTF-8") } catch (e: Exception) { null }
                    if (path == null) { f.delete(); return@forEach }
                    index.put(path, f.name)?.let { old -> deleteFile(old) }
                }
            }
            loaded = true
            if (BuildConfig.DEBUG) Log.d(TAG, "На диске ${index.size} модулей")
        }
    }

    /**
     * Докачивает модуль в фоне, в потоках OkHttp. С [etag] — условный запрос: 304 только
     * продлевает срок свежести файла. Сбой ничего не ломает: модуль уже грузит WebView.
     */
    private fun fetch(url: String, path: String, version: String, headers: Map<String, String>, etag: String?) {
        val key = path + SEP + version
        if (!pending.add(key)) return
        val request = try {
            val builder = Request.Builder().url(url)
            headers.filterKeys {
                !it.equals("Accept-Encoding", ignoreCase = true) && !it.equals("If-None-Match", ignoreCase = true) &&
                    !it.equals("If-Modified-Since", ignoreCase = true)
            }.forEach { (k, v) -> builder.header(k, v) }
            if (etag != null) builder.header("If-None-Match", etag)
            builder.build()
        } catch (e: Exception) {
            pending.remove(key)
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                pending.remove(key)
                if (BuildConfig.DEBUG) Log.d(TAG, "Не докачан $url: $e")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        val name = fileName(path, version)
                        if (resp.code() == 304) {
                            File(dir, name).setLastModified(System.currentTimeMillis())
                            return
                        }
                        if (resp.code() != 200) return
                        val body = resp.body() ?: return
                        // Страница ошибки с кодом 200 (портал Wi-Fi, заглушка провайдера) не
                        // должна лечь в кэш вместо модуля — до следующего хеша
                        if (body.contentType()?.subtype()?.equals("html", ignoreCase = true) == true) return
                        if (body.contentLength() > MAX_FILE_BYTES) return
                        val bytes = body.bytes()
                        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return
                        store(path, name, bytes, resp.header("ETag"))
                    }
                } catch (e: IOException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Не докачан $url: $e")
                } finally {
                    pending.remove(key)
                }
            }
        })
    }

    private fun store(path: String, name: String, bytes: ByteArray, etag: String?) {
        try {
            val tmp = File(dir, name + TMP_SUFFIX + Thread.currentThread().id)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                return
            }
            val etagFile = File(dir, name + ETAG_SUFFIX)
            if (etag != null && name.endsWith(SEP)) etagFile.writeText(etag) else etagFile.delete()
            synchronized(lock) {
                index.put(path, name)?.let { old -> if (old != name) deleteFile(old) }
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "Сохранён $name, ${bytes.size} байт")
        } catch (e: IOException) {
            Log.w(TAG, "Не сохранён $name", e)
        }
    }

    private fun readEtag(name: String): String? = try {
        File(dir, name + ETAG_SUFFIX).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    private fun deleteFile(name: String) {
        File(dir, name).delete()
        File(dir, name + ETAG_SUFFIX).delete()
    }
}
