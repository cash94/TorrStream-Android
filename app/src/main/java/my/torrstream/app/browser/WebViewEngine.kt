package my.torrstream.app.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
import androidx.webkit.WebViewCompat
import com.norman.webviewup.lib.WebViewReplace
import com.norman.webviewup.lib.util.FileUtils
import com.norman.webviewup.lib.util.ProcessUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import my.torrstream.app.App
import my.torrstream.app.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import kotlin.coroutines.coroutineContext

/**
 * Движок WebView внутри приложения: системный, установленный пакет (Google WebView, Chrome…)
 * или APK, скачанный из каталога github.com/JonaNorman/WebViewPackage.
 *
 * Зачем: на китайских ТВ-приставках и проекторах системный WebView бывает очень старым
 * (Android 9 с Chromium 66), а обновить его нельзя — пакет подписан ключом производителя.
 * Библиотека WebViewUpgrade (модуль :webviewup) подменяет провайдера WebView только для
 * нашего процесса, систему она не трогает.
 *
 * Подменить движок можно лишь до первого WebView в процессе, поэтому [applyOnStartup]
 * вызывается из App.onCreate, а смена движка в меню требует перезапуска приложения.
 */
object WebViewEngine {
    private const val TAG = "WebViewEngine"

    private const val PREFS = "webview_engine"
    private const val KEY_KIND = "kind"
    private const val KEY_PACKAGE = "package"
    private const val KEY_FILE_ID = "file_id"
    private const val KEY_LABEL = "label"
    private const val KEY_ATTEMPTS = "attempts"

    const val KIND_SYSTEM = "system"
    const val KIND_PACKAGE = "package"
    const val KIND_FILE = "file"

    /**
     * Сколько запусков подряд выбранный движок может не дойти до загруженной страницы.
     * Падение в нативном коде движка не поймать, и без этого предела приложение падало бы
     * при каждом запуске, не давая открыть меню и вернуть системный WebView.
     */
    private const val MAX_ATTEMPTS = 3

    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/JonaNorman/WebViewPackage/main/webview_packages.json"
    /** Снимок каталога в assets — на случай, когда GitHub недоступен. */
    private const val CATALOG_ASSET = "webview_packages.json"
    /** Huawei и Amazon — сборки под свои прошивки, на чужих устройствах от них толку нет. */
    private val CATALOG_VENDORS = listOf("google", "android", "chrome")

    /** Пакеты, которые умеют работать провайдером WebView, если стоят на устройстве. */
    private val KNOWN_PROVIDERS = listOf(
        "com.google.android.webview",
        "com.google.android.webview.beta",
        "com.android.webview",
        "com.android.chrome",
        "com.chrome.beta",
        "org.bromite.webview",
        "org.cromite.webview",
        "app.vanadium.webview",
    )

    private const val ENGINES_DIR = "webview_engines"
    private const val APK_NAME = "base.apk"
    private const val LIBS_DIR = "libs"
    private const val READY_MARK = "ready"

    /** Что выбрано в настройках. */
    data class Selection(
        val kind: String,
        val packageName: String? = null,
        val fileId: String? = null,
        val label: String = "",
    )

    /** Запись каталога WebViewPackage. */
    data class CatalogEntry(
        val vendor: String,
        val vendorName: String,
        val version: String,
        val minApi: Int,
        val arch: String,
        val url: String,
    ) {
        val id: String get() = "$vendor-$version-$arch".replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val label: String get() = "$vendorName $version"
    }

    /** Установленный пакет-провайдер. */
    data class InstalledEngine(val packageName: String, val label: String)

    /** Движок, подменённый в этом процессе, или null — работает системный. */
    @Volatile
    var activeLabel: String? = null
        private set

    /** Почему выбранный движок не включился при запуске — MainActivity покажет один раз. */
    @Volatile
    var startupError: String? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selection(ctx: Context): Selection {
        val p = prefs(ctx)
        return Selection(
            kind = p.getString(KEY_KIND, KIND_SYSTEM) ?: KIND_SYSTEM,
            packageName = p.getString(KEY_PACKAGE, null),
            fileId = p.getString(KEY_FILE_ID, null),
            label = p.getString(KEY_LABEL, null).orEmpty(),
        )
    }

    fun select(ctx: Context, selection: Selection) {
        // commit, а не apply: сразу за выбором идёт перезапуск процесса
        prefs(ctx).edit(commit = true) {
            putString(KEY_KIND, selection.kind)
            putString(KEY_PACKAGE, selection.packageName)
            putString(KEY_FILE_ID, selection.fileId)
            putString(KEY_LABEL, selection.label)
            putInt(KEY_ATTEMPTS, 0)
        }
    }

    private fun resetToSystem(ctx: Context) = select(ctx, Selection(KIND_SYSTEM))

    private fun setAttempts(ctx: Context, value: Int) =
        prefs(ctx).edit(commit = true) { putInt(KEY_ATTEMPTS, value) }

    /**
     * Включает выбранный движок. Только из App.onCreate главного процесса, в главном потоке:
     * WebView в процессе ещё не создавался (его создают и фоновые задачи — CookieManager в
     * обновлении каналов, — так что позже, в MainActivity, могло быть уже поздно).
     */
    fun applyOnStartup(ctx: Context) {
        val sel = selection(ctx)
        if (sel.kind == KIND_SYSTEM) return
        val attempts = prefs(ctx).getInt(KEY_ATTEMPTS, 0)
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "${sel.label}: $attempts launches without a loaded page, back to system")
            resetToSystem(ctx)
            startupError = App.context.getString(R.string.webview_engine_crashed, sel.label)
            return
        }
        // Счётчик растёт до подмены и откатывается после: падение внутри неё (загрузка
        // нативной библиотеки движка) так и останется посчитанным
        setAttempts(ctx, attempts + 1)
        try {
            when (sel.kind) {
                KIND_PACKAGE -> {
                    val info = ctx.packageManager.getPackageInfo(sel.packageName ?: "", 0)
                    WebViewReplace.replace(ctx, info)
                }

                KIND_FILE -> {
                    val dir = engineDir(ctx, sel.fileId ?: "")
                    if (!isReady(dir)) throw IOException("engine files are missing")
                    WebViewReplace.replace(
                        ctx,
                        File(dir, APK_NAME).absolutePath,
                        File(dir, LIBS_DIR).absolutePath,
                    )
                }

                else -> return
            }
            activeLabel = sel.label
            setAttempts(ctx, attempts)
            Log.i(TAG, "WebView engine: ${sel.label} (${WebViewReplace.getReplaceWebViewVersion()})")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to enable ${sel.label}", t)
            resetToSystem(ctx)
            startupError = App.context.getString(
                R.string.webview_engine_failed,
                sel.label,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /** MainActivity открылась с подменённым движком — ещё одна попытка дойти до страницы. */
    fun onActivityStart(ctx: Context) {
        if (activeLabel == null) return
        setAttempts(ctx, prefs(ctx).getInt(KEY_ATTEMPTS, 0) + 1)
    }

    /** Страница загрузилась — движок рабочий, счётчик неудачных запусков обнуляется. */
    fun markHealthy(ctx: Context) {
        if (activeLabel == null) return
        if (prefs(ctx).getInt(KEY_ATTEMPTS, 0) != 0) setAttempts(ctx, 0)
    }

    /** Системный провайдер: имя пакета и версия, как их видит библиотека до подмены. */
    fun systemEngineLabel(ctx: Context): String {
        val name = runCatching { WebViewReplace.getSystemWebViewPackageName() }.getOrNull()
        val version = runCatching { WebViewReplace.getSystemWebViewPackageVersion() }.getOrNull()
        if (name != null) return listOfNotNull(name, version).joinToString(" ")
        val info = WebViewCompat.getCurrentWebViewPackage(ctx)
        return listOfNotNull(info?.packageName, info?.versionName).joinToString(" ")
    }

    /** Установленные провайдеры, кроме системного (он и так есть в списке). */
    fun installedEngines(ctx: Context): List<InstalledEngine> {
        val system = runCatching { WebViewReplace.getSystemWebViewPackageName() }.getOrNull()
            ?: WebViewCompat.getCurrentWebViewPackage(ctx)?.packageName
        val pm = ctx.packageManager
        return KNOWN_PROVIDERS.filter { it != system }.mapNotNull { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            if (info.applicationInfo?.enabled == false) return@mapNotNull null
            InstalledEngine(pkg, "$pkg ${info.versionName.orEmpty()}".trim())
        }
    }

    /** Каталог движков, подходящих устройству: по версии Android и архитектуре процесса. */
    suspend fun loadCatalog(ctx: Context): Pair<List<CatalogEntry>, Boolean> =
        withContext(Dispatchers.IO) {
            val online = runCatching { httpGetText(CATALOG_URL) }
                .onFailure { Log.w(TAG, "Catalog download failed", it) }
                .getOrNull()
                ?.let { runCatching { parseCatalog(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
            val entries = online ?: ctx.assets.open(CATALOG_ASSET).use {
                parseCatalog(it.bufferedReader().readText())
            }
            val instruction = ProcessUtils.getCurrentInstruction()
            // Одна версия бывает и отдельной сборкой под разрядность, и общей «arm32+64» —
            // оставляем одну, отдельную: она вдвое легче
            entries.filter {
                it.minApi <= Build.VERSION.SDK_INT && archMatches(it.arch, instruction)
            }.groupBy { it.vendor to it.version }.values.map { same ->
                same.minByOrNull { if (it.arch == "arm32+64") 1 else 0 }!!
            } to (online != null)
        }

    private fun parseCatalog(json: String): List<CatalogEntry> {
        val vendors = JSONObject(json).getJSONArray("vendors")
        val result = mutableListOf<CatalogEntry>()
        for (i in 0 until vendors.length()) {
            val v = vendors.getJSONObject(i)
            val vendor = v.optString("vendor")
            if (vendor !in CATALOG_VENDORS) continue
            val packages = v.optJSONArray("packages") ?: continue
            for (j in 0 until packages.length()) {
                val p = packages.getJSONObject(j)
                val url = p.optString("url")
                if (!url.startsWith("https://")) continue
                result += CatalogEntry(
                    vendor = vendor,
                    vendorName = v.optString("name", vendor),
                    version = p.optString("version"),
                    minApi = p.optInt("min_api", Int.MAX_VALUE),
                    arch = p.optString("arch"),
                    url = url,
                )
            }
        }
        // Внутри производителя — от новых версий к старым
        return result.sortedWith(
            compareBy<CatalogEntry> { CATALOG_VENDORS.indexOf(it.vendor) }
                .thenByDescending { versionKey(it.version) }
        )
    }

    private fun versionKey(version: String): String =
        version.split('.', '-').joinToString(".") { it.padStart(6, '0') }

    /** Как CatalogArch в демо библиотеки: arch каталога против набора инструкций процесса. */
    private fun archMatches(arch: String, instruction: String?): Boolean = when (arch) {
        "arm32+64" -> instruction == "arm" || instruction == "arm64"
        "arm32" -> instruction == "arm"
        "arm64" -> instruction == "arm64"
        "x86" -> instruction == "x86" || instruction == "x86_64"
        else -> false
    }

    private fun enginesRoot(ctx: Context) = File(ctx.filesDir, ENGINES_DIR)

    private fun engineDir(ctx: Context, id: String) = File(enginesRoot(ctx), id)

    private fun isReady(dir: File) =
        File(dir, READY_MARK).exists() && File(dir, APK_NAME).length() > 0

    fun isDownloaded(ctx: Context, entry: CatalogEntry) = isReady(engineDir(ctx, entry.id))

    /**
     * Скачивает APK движка и раскладывает его так, как ждёт WebViewReplace: base.apk и
     * libs/<abi>/lib….so. [onProgress] — проценты 0..100, -1 — идёт распаковка.
     */
    suspend fun download(ctx: Context, entry: CatalogEntry, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val dir = engineDir(ctx, entry.id)
            FileUtils.delete(dir)
            dir.mkdirs()
            try {
                val apk = File(dir, APK_NAME)
                fetchFile(ctx, entry.url, apk, onProgress)
                onProgress(-1)
                checkApk(ctx, apk)
                extractLibs(apk, File(dir, LIBS_DIR))
                FileUtils.makeFileWorldReadable(ctx, apk)
                File(dir, READY_MARK).writeText(entry.label)
            } catch (t: Throwable) {
                FileUtils.delete(dir)
                throw t
            }
        }

    private suspend fun fetchFile(ctx: Context, link: String, out: File, onProgress: (Int) -> Unit) {
        val connection = open(link)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = connection.contentLength.toLong()
            // APK, плюс распакованные библиотеки — примерно столько же сверху
            if (total > 0) {
                val free = StatFs(ctx.filesDir.absolutePath).availableBytes
                if (free < total * 3) {
                    throw IOException(
                        ctx.getString(R.string.webview_engine_no_space, (total * 3) shr 20)
                    )
                }
            }
            connection.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPrc = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val prc = (done * 100 / total).toInt()
                            if (prc != lastPrc) {
                                lastPrc = prc
                                onProgress(prc)
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IOException("incomplete download")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetText(link: String): String {
        val connection = open(link)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(link: String): HttpURLConnection {
        val connection = URL(link).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        // Код движка исполняется в нашем процессе, поэтому проверка сертификата обязательна.
        // Updater на Android < 10 ставит HttpsURLConnection фабрику, которая верит любому
        // сертификату, — здесь берём системную явно.
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = SSLContext.getDefault().socketFactory
        }
        return connection
    }

    /** Тот же разбор, что сделает система: битый файл или APK не для этой версии Android. */
    private fun checkApk(ctx: Context, apk: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IOException(ctx.getString(R.string.webview_engine_bad_apk))
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            info.applicationInfo?.minSdkVersion ?: 0 else 0
        if (minSdk > Build.VERSION.SDK_INT) {
            throw IOException(ctx.getString(R.string.webview_engine_bad_apk))
        }
    }

    /**
     * Только библиотеки под разрядность нашего процесса: в APK «arm32+64» обе, и вторая
     * заняла бы место впустую (libwebviewchromium.so — около сотни мегабайт).
     */
    private fun extractLibs(apk: File, libsDir: File) {
        val abis = (if (ProcessUtils.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
        else Build.SUPPORTED_32_BIT_ABIS).toSet()
        var extracted = 0
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val parts = entry.name.split('/')
                if (entry.isDirectory || parts.size != 3 || parts[0] != "lib") continue
                if (parts[1] !in abis || !parts[2].endsWith(".so") || parts[2].contains("..")) continue
                val target = File(File(libsDir, parts[1]), parts[2])
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { input.copyTo(it, 64 * 1024) }
                }
                extracted++
            }
        }
        if (extracted == 0) {
            throw IOException("no native libraries for ${abis.joinToString()}")
        }
    }

    /** Удаляет скачанные движки, кроме выбранного. Возвращает освобождённые мегабайты. */
    fun deleteDownloads(ctx: Context): Long {
        val keep = selection(ctx).takeIf { it.kind == KIND_FILE }?.fileId
        var freed = 0L
        enginesRoot(ctx).listFiles()?.forEach { dir ->
            if (dir.name == keep) return@forEach
            freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            FileUtils.delete(dir)
        }
        return freed shr 20
    }

    /**
     * Холодный перезапуск: движок привязывается к процессу при первом WebView, поэтому
     * новый выбор вступит в силу только в новом процессе. Сам процесс себя не перезапустит
     * (после exitProcess система не обязана поднимать его снова), поэтому это делает
     * RestartActivity из отдельного процесса — как ProcessPhoenix.
     */
    fun restartApp(activity: Activity) {
        activity.startActivity(
            Intent(activity, RestartActivity::class.java)
                .putExtra(RestartActivity.EXTRA_PID, Process.myPid())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        activity.finishAffinity()
    }
}
