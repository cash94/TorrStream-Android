package my.torrstream.app.helpers

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Spanned
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import my.torrstream.app.App
import my.torrstream.app.BuildConfig
import my.torrstream.app.R
import my.torrstream.app.helpers.Helpers.getJson
import my.torrstream.app.models.Assets
import my.torrstream.app.models.Release
import my.torrstream.app.models.Releases
import my.torrstream.app.net.TlsSocketFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory


object Updater {
    private const val RELEASE_LINK =
        "https://api.github.com/repos/cash94/TorrStream-Android/releases"
    private var releases: Releases? = null
    private var newVersion: Release? = null

    /**
     * Версия из тега («v1.0.2», «1.0.2-beta») числами: [1, 0, 2].
     *
     * Прежнее сравнение брало кусок до первой точки и кусок после неё как два
     * числа — на версиях вида 1.0.1 это давало «0.1», а 1.0.10 превращалось в
     * «0.10», то есть в то же самое 0.1, и обновление не предлагалось.
     */
    private fun versionParts(raw: String): List<Int> =
        raw.trim().removePrefix("v").substringBefore('-').split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /** Версия тега строго больше текущей (BuildConfig.VERSION_NAME). */
    private fun isNewerThanCurrent(tag: String): Boolean {
        val candidate = versionParts(tag)
        val current = versionParts(BuildConfig.VERSION_NAME)
        for (i in 0 until maxOf(candidate.size, current.size)) {
            val a = candidate.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    init {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                // Only TLSv1.2 and TLSv1.3 protocol available and trust all certs (insecure).
                val socketFactory: SSLSocketFactory = TlsSocketFactory()
                HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
            } catch (_: GeneralSecurityException) {
            }
        }
    }

    fun check(): Boolean {
        try {
            val url = URL(RELEASE_LINK)
            val connection = if (RELEASE_LINK.startsWith("https"))
                url.openConnection() as HttpsURLConnection? // NetCipher.getHttpsURLConnection(url)
            else
                url.openConnection() as HttpURLConnection? // NetCipher.getHttpURLConnection(url)
            connection?.connect()
            val body = connection?.inputStream?.use {
                it.bufferedReader(Charset.defaultCharset()).readText()
            } ?: return false
            releases = getJson(body, Releases::class.java)
            releases?.let {
                it.forEach { rel ->
                    if (isNewerThanCurrent(rel.tag_name)) {
                        newVersion = rel
                        connection.disconnect()
                        return true
                    }
                }
            }
            connection.disconnect()
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun getVersion(): String {
        if (newVersion == null)
            CoroutineScope(Dispatchers.IO).launch {
                check()
            }
        return newVersion?.tag_name?.replace("v", "") ?: ""
    }

    fun getOverview(): Spanned {
        var ret = ""

        releases?.forEach { rel ->
            if (isNewerThanCurrent(rel.tag_name)) {
                ret += "<font color='white'><b>${rel.tag_name}</b></font> <br>"
                ret += "<i>${rel.body.replace("\r\n", "<br/>")}</i><br/><br/>"
            } else {
                ret += "${rel.tag_name}<br>"
                ret += "<i>${rel.body.replace("\r\n", "<br/>")}</i><br/><br/>"
            }
        }
        return HtmlCompat.fromHtml(ret.trim(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /** GET с тем же обхождением TLS, что и check(). null — не получилось. */
    private fun fetchText(link: String): String? {
        return try {
            val url = URL(link)
            val connection = if (link.startsWith("https"))
                url.openConnection() as HttpsURLConnection?
            else
                url.openConnection() as HttpURLConnection?
            connection?.connect()
            val body = connection?.inputStream?.use {
                it.bufferedReader(Charset.defaultCharset()).readText()
            }
            connection?.disconnect()
            body
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Ссылка на APK релиза.
     *
     * Список релизов GitHub отдаёт с пустым assets (проверено на этом
     * репозитории: и в /releases, и по тегу), а сами вложения доступны только
     * по assets_url. Прежний код брал ссылку из assets списка — и скачивать
     * было нечего, обновление молча не устанавливалось.
     */
    private fun apkLink(rel: Release): String {
        rel.assets.lastOrNull { it.browser_download_url.endsWith(".apk", true) }
            ?.let { return it.browser_download_url }

        val body = fetchText(rel.assets_url) ?: return ""
        val assets = try {
            getJson(body, Assets::class.java)
        } catch (e: Exception) {
            null
        } ?: return ""
        return assets.lastOrNull { it.browser_download_url.endsWith(".apk", true) }
            ?.browser_download_url ?: ""
    }

    private val download = Any()

    private fun downloadApk(file: File, onProgress: ((prc: Int) -> Unit)?) {
        synchronized(download) {
            newVersion?.let { rel ->
                if (file.exists())
                    file.delete()
                val link = apkLink(rel)
                if (link.isNotEmpty()) {
                    try {
                        val url = URL(link)
                        val connection = if (link.startsWith("https"))
                            url.openConnection() as HttpsURLConnection? // NetCipher.getHttpsURLConnection(url)
                        else
                            url.openConnection() as HttpURLConnection? // NetCipher.getHttpURLConnection(url)
                        connection?.connect()
                        connection?.inputStream.use { input ->
                            FileOutputStream(file).use { fileOut ->
                                val contentLength = connection?.contentLength ?: 0
                                if (onProgress == null)
                                    input?.copyTo(fileOut)
                                else {
                                    val buffer = ByteArray(65535)
                                    val length = contentLength + 1
                                    var offset: Long = 0
                                    while (true) {
                                        val read = input?.read(buffer) ?: 0
                                        offset += read
                                        val prc = (offset * 100 / length).toInt()
                                        onProgress(prc)
                                        if (read <= 0)
                                            break
                                        fileOut.write(buffer, 0, read)
                                    }
                                    fileOut.flush()
                                }
                                fileOut.flush()
                                fileOut.close()
                            }
                        }
                        connection?.disconnect()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun installNewVersion(onProgress: ((prc: Int) -> Unit)?) {
        val ctx = App.context
        if (newVersion == null && !check())
            return

        newVersion?.let {
            val destination = File(
                ctx.getExternalFilesDir(null),
                "TorrStream.apk"
            ).apply {
                mkdirs()
                deleteOnExit()
            }

            downloadApk(destination, onProgress)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val uri = Uri.fromFile(destination)
                val install = Intent(Intent.ACTION_VIEW)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.setDataAndType(uri, "application/vnd.android.package-archive")
                if (install.resolveActivity(ctx.packageManager) != null)
                    App.context.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            } else {
                val fileUri =
                    FileProvider.getUriForFile(
                        ctx,
                        BuildConfig.APPLICATION_ID + ".update_provider",
                        destination
                    )
                val install = Intent(Intent.ACTION_VIEW, fileUri)
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (install.resolveActivity(ctx.packageManager) != null)
                    ctx.startActivity(install)
                else
                    App.toast(R.string.error_app_not_found)
            }
        }
    }
}