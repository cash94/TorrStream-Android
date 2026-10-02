package my.torrstream.app.torrserver

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import my.torrstream.app.App
import my.torrstream.app.net.HttpHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Свой TorrServer внутри приложения: скачать официальную сборку под Android с GitHub
 * (YouROK/TorrServer, файлы TorrServer-android-<abi>), запустить и держать в фоне
 * ([TorrServerService]). Веб управляет им через AndroidJS (tsLocal*), адрес — тот же
 * http://localhost:8090, что у переключателя «TorrServer на этом устройстве».
 *
 * Скачанный файл запускаем из памяти приложения — это разрешено, пока targetSdk < 29
 * (у нас 28); с 29 такое запрещено (W^X), и бинарник пришлось бы вшивать в APK.
 *
 * Свою копию не запускаем, если порт 8090 уже отвечает: у пользователя может работать
 * TorrServe или другой TorrServer — две копии на одном порту не уживутся.
 */
object TorrServerManager {
    private const val TAG = "TorrServer"
    const val PORT = 8090
    private const val RELEASES = "https://api.github.com/repos/YouROK/TorrServer/releases/latest"
    private const val PREFS = "torrserver"
    private const val PREF_VERSION = "version"
    private const val PREF_AUTOSTART = "autostart"
    /** Статус порта старше этого — перепроверяем при следующем запросе статуса */
    private const val CHECK_TTL_MS = 2000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs get() = App.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client: OkHttpClient by lazy {
        HttpHelper.getOkHttpClient(HttpHelper.DEFAULT_CONNECTION_TIMEOUT).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    /** Короткий клиент для проверки порта: сервер на этом же устройстве отвечает сразу */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
    }

    private val dir: File get() = File(App.context.filesDir, "torrserver")
    val binary: File get() = File(dir, "TorrServer")
    val dataDir: File get() = File(dir, "data")
    val logFile: File get() = File(dir, "torrserver.log")

    // Состояние для веба. Пишется в фоне, читается из потока JavaBridge
    @Volatile var downloading = false; private set
    @Volatile var progress = 0; private set
    @Volatile var error: String? = null; private set
    @Volatile var portVersion: String? = null; private set   // ответ /echo, null — порт молчит
    @Volatile private var portCheckedAt = 0L
    @Volatile var latestVersion: String? = null; private set
    private val checking = AtomicBoolean(false)

    /** Сборка TorrServer под процессор устройства или null, если такой нет */
    val assetSuffix: String?
        get() = Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
            when (it) {
                "arm64-v8a" -> "android-arm64"
                "armeabi-v7a" -> "android-arm7"
                "x86_64" -> "android-amd64"
                "x86" -> "android-386"
                else -> null
            }
        }

    val isInstalled: Boolean get() = binary.isFile && binary.length() > 0
    val installedVersion: String? get() = prefs.getString(PREF_VERSION, null)?.takeIf { isInstalled }
    var autostart: Boolean
        get() = prefs.getBoolean(PREF_AUTOSTART, false)
        set(value) = prefs.edit { putBoolean(PREF_AUTOSTART, value) }

    /** Работает ли TorrServer, запущенный нами (а не чужой на том же порту) */
    val isOwnRunning: Boolean get() = TorrServerService.isProcessAlive

    // ==================== СТАТУС ====================

    /**
     * Снимок для веба. Сеть здесь не трогаем: методы AndroidJS вызываются синхронно,
     * и JS ждал бы ответа. Устаревший статус порта лишь заказывает перепроверку.
     */
    fun statusJson(): String {
        if (System.currentTimeMillis() - portCheckedAt > CHECK_TTL_MS) refreshPortAsync()
        return JSONObject().apply {
            put("supported", assetSuffix != null)
            put("installed", isInstalled)
            put("version", installedVersion ?: JSONObject.NULL)
            put("latest", latestVersion ?: JSONObject.NULL)
            put("downloading", downloading)
            put("progress", progress)
            put("error", error ?: JSONObject.NULL)
            put("running", portVersion != null)
            put("runningVersion", portVersion ?: JSONObject.NULL)
            put("own", isOwnRunning)
            put("autostart", autostart)
            put("port", PORT)
        }.toString()
    }

    fun refreshPortAsync() {
        if (!checking.compareAndSet(false, true)) return
        scope.launch {
            try { checkPort() } finally { checking.set(false) }
        }
    }

    /** Ответ /echo на localhost:8090 — версия работающего TorrServer или null */
    fun checkPort(): String? {
        val version = try {
            probeClient.newCall(Request.Builder().url("http://127.0.0.1:$PORT/echo").build())
                .execute().use { resp ->
                    if (resp.isSuccessful) resp.body()?.string()?.trim()?.take(60)?.ifEmpty { "?" } else null
                }
        } catch (e: IOException) {
            null
        }
        portVersion = version
        portCheckedAt = System.currentTimeMillis()
        return version
    }

    // ==================== УСТАНОВКА ====================

    /** Последний релиз и ссылка на файл под это устройство */
    private fun fetchLatest(): Pair<String, String> {
        val suffix = assetSuffix ?: throw IOException("Процессор устройства не поддерживается")
        val body = client.newCall(Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("GitHub ответил ${resp.code()}")
                resp.body()?.string() ?: throw IOException("Пустой ответ GitHub")
            }
        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val assets = json.optJSONArray("assets") ?: throw IOException("В релизе нет файлов")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == "TorrServer-$suffix") return tag to a.optString("browser_download_url")
        }
        throw IOException("Нет сборки TorrServer-$suffix")
    }

    fun checkLatestAsync() {
        scope.launch {
            try { latestVersion = fetchLatest().first } catch (e: Exception) { Log.w(TAG, "latest: ${e.message}") }
        }
    }

    /**
     * Скачивает последнюю версию и, если всё прошло, запускает. Своя копия на время
     * замены файла останавливается — работающий бинарник не перезаписать.
     */
    fun installAsync(startAfter: Boolean = true) {
        if (downloading) return
        downloading = true
        progress = 0
        error = null
        scope.launch {
            try {
                val (tag, url) = fetchLatest()
                latestVersion = tag
                dir.mkdirs()
                val tmp = File(dir, "TorrServer.download")
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Загрузка: HTTP ${resp.code()}")
                    val body = resp.body() ?: throw IOException("Пустой ответ")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) progress = (done * 100 / total).toInt()
                            }
                        }
                    }
                }
                val wasOwn = isOwnRunning
                if (wasOwn) {
                    TorrServerService.stop(App.context)
                    delay(1500)
                }
                if (binary.exists()) binary.delete()
                if (!tmp.renameTo(binary)) throw IOException("Не удалось сохранить файл")
                if (!binary.setExecutable(true, false)) throw IOException("Файл нельзя запустить")
                prefs.edit { putString(PREF_VERSION, tag) }
                progress = 100
                Log.i(TAG, "Установлен TorrServer $tag")
                if (startAfter || wasOwn) startIfFree()
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                downloading = false
            }
        }
    }

    // ==================== ЗАПУСК ====================

    /**
     * Запускает свою копию, если порт свободен. Порт занят чужим TorrServer — ничего не
     * делаем: он и будет работать на localhost:8090.
     */
    fun startIfFree() {
        scope.launch {
            error = null
            if (!isInstalled) { error = "TorrServer не установлен"; return@launch }
            autostart = true
            if (isOwnRunning) return@launch
            if (checkPort() != null) {
                Log.i(TAG, "Порт $PORT уже занят ($portVersion) — свою копию не запускаем")
                return@launch
            }
            TorrServerService.start(App.context)
            // Даём серверу подняться и обновляем статус для веба
            repeat(10) {
                delay(1000)
                if (checkPort() != null) return@launch
            }
            if (!isOwnRunning) error = "TorrServer не запустился — см. журнал"
        }
    }

    fun stop() {
        autostart = false
        TorrServerService.stop(App.context)
        scope.launch {
            delay(1000)
            checkPort()
        }
    }

    /** Запуск приложения: подняли сервер в прошлый раз — поднимаем снова */
    fun autostartIfNeeded() {
        if (autostart && isInstalled) startIfFree()
    }

    fun deleteBinary() {
        stop()
        scope.launch {
            delay(1500)
            binary.delete()
            prefs.edit { remove(PREF_VERSION) }
        }
    }
}
