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
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.TreeSet
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
 * Качает картинки только сам кэш, своей очередью ([enqueue]) — на потоках OkHttp, не WebView.
 * Сеть на потоке WebView (shouldInterceptRequest) держит все остальные запросы страницы:
 * пока такие потоки висели на медленном или молчащем зеркале, каталог переставал грузить
 * постеры, а карточка — детали.
 *
 * Запросы бывают двух видов:
 *  - фоновый поток страницы (PosterPrefetch в poster-prefetch.js) помечает свои запросы
 *    «ts_pf» ([MARK_PARAM]): ему нужна не картинка, а чтобы она оказалась в кэше. Ему сразу
 *    отвечаем пустым 200, а картинку ставим в очередь — WebView её не качает вовсе;
 *  - <img> страницы: есть на диске — отдаём, нет — WebView грузит сам, а кэш ставит ту же
 *    картинку в очередь первой. Раньше так было со всеми картинками, и каждую новую
 *    качали дважды: WebView для страницы и кэш для себя (замер: 8 МБ трафика на 3.9 МБ
 *    постеров). Теперь второй раз — только если <img> опередил докачку.
 *
 * Очередь повторяет порядок PosterPrefetch: сначала промахи <img> (их видно прямо сейчас),
 * потом срочные — свежая пачка впереди прежних, внутри пачки по порядку, — потом фоновые по
 * очереди и не больше [LOW_PARALLEL] разом. Фоновые страница может выбросить ([dropLow]):
 * ушли с главной — «на потом» уже не наступит.
 */
object ImageCache {
    private const val TAG = "ImageCache"
    private const val MAX_ENTRIES = 2500
    private const val MAX_BYTES = 100L * 1024 * 1024
    /** Больше — не кэшируем: это уже не постер, и одна такая запись вытеснила бы десятки. */
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val PATH_MARK = "/t/p/"
    private const val TMP_SUFFIX = ".tmp"

    /**
     * Метка запроса фонового потока страницы: «u.<номер пачки>» — срочный, «l» — фоновый.
     * Номер пачки — счётчик сообщений воркеру: больше — свежее.
     */
    private const val MARK_PARAM = "ts_pf"

    /** «w342/abc.jpg»: размер и имя файла TMDB, больше в пути ничего быть не должно. */
    private val PATH_RE = Regex("^[a-z0-9]+/[A-Za-z0-9_-]+\\.(jpe?g|png|webp|svg)$")

    private val HEADERS = mapOf(
        // Картинку может прочитать и canvas/fetch с другого источника — как с самого зеркала
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "max-age=31536000, immutable",
    )

    /** Ответ фоновому потоку: «принято», тела нет — картинка ему не нужна. */
    private val QUEUED_HEADERS = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store",
    )

    private val dir by lazy { File(App.context.cacheDir, "images") }

    /** Имя файла → размер. accessOrder: итерация идёт от самой давней картинки к свежей. */
    private val index = LinkedHashMap<String, Long>(MAX_ENTRIES, 0.75f, true)
    private var totalBytes = 0L
    private var loaded = false
    private val lock = Any()

    /** Таймаут докачки: соединение — половина, чтение и весь запрос — целиком. */
    private const val PREFETCH_TIMEOUT_MS = 10_000
    /** Сколько картинок качается одновременно, из них фоновых — не больше [LOW_PARALLEL]. */
    private const val PARALLEL = 6
    private const val LOW_PARALLEL = 3
    private const val PER_HOST = 3
    /** Длиннее очереди не держим: выпадают самые старые — фокус давно ушёл от них. */
    private const val URGENT_MAX = 120
    private const val LOW_MAX = 600

    private val client: OkHttpClient by lazy {
        // Тот же разрешительный TLS, что у остального приложения: WebView ошибки
        // сертификатов зеркал игнорирует (SysView.onReceivedSslError), кэш не должен быть строже
        HttpHelper.getOkHttpClient(PREFETCH_TIMEOUT_MS).newBuilder()
            // Простоявшее несколько минут соединение роутер может молча закрыть, и запрос по
            // нему висит до таймаута. По умолчанию OkHttp держит соединения 5 минут — дольше,
            // чем живут многие домашние NAT. 30 с хватает, чтобы переиспользовать соединение
            // при листании, и мёртвые в пуле не задерживаются.
            .connectionPool(ConnectionPool(PARALLEL, 30, TimeUnit.SECONDS))
            .dispatcher(Dispatcher().apply {
                maxRequests = PARALLEL
                maxRequestsPerHost = PER_HOST
            })
            .build()
    }

    // ==================== ОЧЕРЕДЬ ====================

    /** Класс очереди: промах <img>, срочный и фоновый запрос фонового потока страницы. */
    private const val CLS_IMG = 0
    private const val CLS_URGENT = 1
    private const val CLS_LOW = 2

    private class Job(
        val name: String,
        val url: String,
        val headers: Map<String, String>,
        val cls: Int,
        val batch: Long,
        val seq: Long,
    )

    /** Свежая пачка — первой, внутри пачки — по порядку прихода. */
    private val freshFirst = Comparator<Job> { a, b ->
        if (a.batch != b.batch) b.batch.compareTo(a.batch) else a.seq.compareTo(b.seq)
    }
    private val arrivalOrder = Comparator<Job> { a, b -> a.seq.compareTo(b.seq) }

    // Всё ниже — под [qLock]
    private val qLock = Any()
    private val queues = arrayOf(TreeSet(freshFirst), TreeSet(freshFirst), TreeSet(arrivalOrder))
    private val queued = HashMap<String, Job>()
    private val running = HashSet<String>()
    private var active = 0
    private var activeLow = 0
    private var seq = 0L
    /** Своя нумерация пачек: номер страницы сбрасывается при её перезагрузке, наш — нет. */
    private var batch = 0L
    private var lastPageBatch: String? = null

    /**
     * Ответ для WebView или null, если запрос не наш или картинки нет на диске (тогда WebView
     * грузит сам). Зовётся из shouldInterceptRequest — на фоновом потоке WebView, и сети здесь
     * нет ни в каком виде: только диск и постановка в очередь.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method != "GET" || request.isForMainFrame) return null
        val uri = request.url ?: return null
        val url = uri.toString()
        val name = fileNameOf(url) ?: return null
        val headers = request.requestHeaders.orEmpty()
        if (headers.keys.any { it.equals("Range", ignoreCase = true) }) return null
        val mark = try { uri.getQueryParameter(MARK_PARAM) } catch (e: Exception) { null }

        return try {
            ensureLoaded()
            if (mark != null) {
                // Фоновому потоку картинка не нужна — только чтобы она была на диске
                if (touch(name)) return queuedResponse()
                val urgent = mark.startsWith("u")
                enqueue(stripMark(uri), name, headers, if (urgent) CLS_URGENT else CLS_LOW,
                    if (urgent) mark.substringAfter('.', "") else null)
                return queuedResponse()
            }
            val cached = openCached(name)
            if (cached == null) {
                enqueue(url, name, headers, CLS_IMG, null)
                return null
            }
            WebResourceResponse(mimeOf(name), null, 200, "OK", HEADERS, cached)
        } catch (e: Exception) {
            Log.w(TAG, "intercept $url", e)
            null
        }
    }

    private fun queuedResponse() =
        WebResourceResponse("image/jpeg", null, 200, "OK", QUEUED_HEADERS, ByteArrayInputStream(ByteArray(0)))

    /** Адрес без метки — каким его знает страница (и каким его попросит <img>). */
    private fun stripMark(uri: Uri): String {
        val b = uri.buildUpon().clearQuery()
        for (p in uri.queryParameterNames) {
            if (p == MARK_PARAM) continue
            for (v in uri.getQueryParameters(p)) b.appendQueryParameter(p, v)
        }
        return b.build().toString()
    }

    /**
     * В очередь. Уже стоит — поднимаем, если просят срочнее или в более свежей пачке;
     * уже качается — ничего не делаем.
     */
    private fun enqueue(url: String, name: String, headers: Map<String, String>, cls: Int, pageBatch: String?) {
        // Accept-Encoding не копируем: с ним OkHttp перестаёт сам распаковывать gzip
        val hdrs = headers.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
        synchronized(qLock) {
            if (name in running) return
            val b = when (cls) {
                CLS_URGENT -> {
                    // Новая пачка страницы — новая наша: она свежее всех, что уже ждут
                    if (pageBatch != lastPageBatch) { lastPageBatch = pageBatch; batch++ }
                    batch
                }
                CLS_IMG -> Long.MAX_VALUE
                else -> 0L
            }
            val old = queued[name]
            if (old != null) {
                val better = cls < old.cls || (cls == old.cls && cls == CLS_URGENT && b > old.batch)
                if (!better) return
                queues[old.cls].remove(old)
            }
            val job = Job(name, url, hdrs, cls, b, ++seq)
            queued[name] = job
            val q = queues[cls]
            q.add(job)
            val max = if (cls == CLS_LOW) LOW_MAX else URGENT_MAX
            while (q.size > max) q.pollLast()?.let { queued.remove(it.name) }
            pumpLocked()
        }
    }

    /** Запускает следующие загрузки, пока есть свободные места. Под [qLock]. */
    private fun pumpLocked() {
        while (active < PARALLEL) {
            val q = when {
                queues[CLS_IMG].isNotEmpty() -> queues[CLS_IMG]
                queues[CLS_URGENT].isNotEmpty() -> queues[CLS_URGENT]
                queues[CLS_LOW].isNotEmpty() && activeLow < LOW_PARALLEL -> queues[CLS_LOW]
                else -> return
            }
            val job = q.pollFirst() ?: return
            queued.remove(job.name)
            running.add(job.name)
            active++
            if (job.cls == CLS_LOW) activeLow++
            start(job)
        }
    }

    private fun finished(job: Job) {
        synchronized(qLock) {
            running.remove(job.name)
            active--
            if (job.cls == CLS_LOW) activeLow--
            pumpLocked()
        }
    }

    /**
     * Очистить фоновую очередь (страница ушла с главной). Уже идущие загрузки доходят до
     * конца. Возвращает выброшенные адреса — JSON-массив: страница забудет их и попросит
     * снова, когда они опять понадобятся.
     */
    fun dropLow(): String {
        val out = JSONArray()
        synchronized(qLock) {
            for (job in queues[CLS_LOW]) {
                queued.remove(job.name)
                out.put(job.url)
            }
            queues[CLS_LOW].clear()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Фоновая очередь очищена: ${out.length()}")
        return out.toString()
    }

    private fun start(job: Job) {
        val request = try {
            val builder = Request.Builder().url(job.url)
            // Заголовки WebView (User-Agent, Referer, Accept) — зеркало должно видеть тот же запрос
            job.headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        } catch (e: Exception) {
            finished(job)
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finished(job)
                if (BuildConfig.DEBUG) Log.d(TAG, "Не докачана ${job.url}: $e")
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
                        if (bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) store(job.name, bytes)
                    }
                } catch (e: IOException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Не докачана ${job.url}: $e")
                } finally {
                    finished(job)
                }
            }
        })
    }

    // ==================== ДИСК ====================

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

    /** Картинка на диске? Заодно — свежая для LRU. Файл не открываем. */
    private fun touch(name: String): Boolean {
        synchronized(lock) { if (index[name] == null) return false }
        val file = File(dir, name)
        if (!file.exists()) {
            forget(name)
            return false
        }
        file.setLastModified(System.currentTimeMillis())
        return true
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
