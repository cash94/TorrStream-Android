package my.torrstream.app.player

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import my.torrstream.app.BuildConfig
import my.torrstream.app.helpers.ServerCookies
import my.torrstream.app.helpers.isGoogleTV
import my.torrstream.app.helpers.isTvBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Отчёты о сбоях встроенного плеера на сервер TorrStream (POST /api/diagnostics).
 *
 * Шлётся, когда ExoPlayer не справился и плеер откатился — программный декодер видео, PCM
 * вместо битстрима, «безопасный» звук, libVLC — или сдался совсем. В отчёте всё, что нужно,
 * чтобы понять и повторить сбой, не имея устройства в руках: модель и прошивка, декодеры
 * MediaCodec с профилями и пределами, звуковой выход и битстрим, что умеет FFmpeg-модуль,
 * дорожки файла с поддержкой по мнению ExoPlayer, сама ошибка с цепочкой причин, имя
 * декодера и шаги откатов до неё. Раздача (hash + номер файла) — чтобы открыть тот же файл.
 *
 * Отправка в своём потоке: после «сдался» плеер сразу закрывается, а отчёт должен уйти.
 * Не ушёл (нет сети) — ложится в файл и уходит со следующим. Одинаковые отчёты (тот же
 * вид, ошибка и файл) — не чаще раза в сутки, и всего не больше [DAILY_LIMIT] в сутки.
 */
@OptIn(UnstableApi::class)
object PlaybackDiagnostics {

    private const val TAG = "PlaybackDiagnostics"
    private const val SCHEMA = 1

    private const val PREFS = "playback_diagnostics"
    private const val KEY_SENT = "sent"           // JSON { ключ отчёта: время }
    private const val DAILY_LIMIT = 30
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private const val PENDING_DIR = "diagnostics"
    private const val PENDING_MAX = 10

    private const val MAX_STACK = 25
    private const val MAX_TEXT = 600

    // Виды отчётов — то же самое видит админка сервера
    const val KIND_SOFTWARE_VIDEO = "software_video_fallback"
    const val KIND_PCM_AUDIO = "pcm_audio_fallback"
    const val KIND_SAFE_AUDIO = "safe_audio_fallback"
    const val KIND_VLC = "vlc_fallback"
    const val KIND_VLC_UNSUPPORTED_TRACK = "vlc_unsupported_track"
    const val KIND_FAILED = "playback_failed"
    const val KIND_STREAM_RESTART = "stream_restart"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "playback-diagnostics").apply { isDaemon = true }
    }

    /** Декодеры не меняются, пока жив процесс, — собираем один раз. */
    @Volatile
    private var codecsCache: JSONArray? = null

    /**
     * Всё, что знает о сбое сам плеер: собирается на главном потоке (состояние плеера и
     * дорожки читаются только там) и отдаётся сюда готовым.
     */
    class Snapshot(
        val kind: String,
        val reason: String,
        val error: PlaybackException?,
        /** Мим дорожки, на которой сломалось, если ошибка его не несёт (нет поддерживаемой дорожки). */
        val failedMime: String?,
        val media: JSONObject,
        val player: JSONObject,
        /** Адрес TorrServer, если это его поток: у него спросим имя и размер файла. */
        val torrServer: String? = null,
        val torrHeaders: Map<String, String> = emptyMap(),
    )

    /**
     * Отправить отчёт. [endpoint] — адрес /api/diagnostics на сервере страницы; null —
     * сервера нет (плеер открыт не из приложения), отчёт только в журнал.
     */
    fun report(context: Context, endpoint: String?, clientId: String?, snapshot: Snapshot) {
        val app = context.applicationContext
        // Ошибка — до перехода в другой поток: из неё читаем только неизменяемые поля
        val errorJson = try {
            snapshot.error?.let { errorJson(it) } ?: JSONObject()
        } catch (e: Throwable) {
            JSONObject().put("collectError", e.toString())
        }
        snapshot.failedMime?.let { if (!errorJson.has("mime")) errorJson.put("mime", it) }

        executor.execute {
            try {
                val dedupKey = listOf(
                    snapshot.kind,
                    errorJson.optString("codeName"),
                    errorJson.optString("mime"),
                    snapshot.media.optString("hash"),
                    snapshot.media.optString("fileIndex"),
                ).joinToString("|")
                if (!allowed(app, dedupKey)) {
                    Log.i(TAG, "Skipped (already sent today or daily limit): $dedupKey")
                    return@execute
                }
                addTorrentFile(snapshot)

                val body = JSONObject().apply {
                    put("schema", SCHEMA)
                    put("kind", snapshot.kind)
                    put("reason", snapshot.reason.take(MAX_TEXT))
                    put("time", System.currentTimeMillis())
                    clientId?.let { put("clientId", it) }
                    put("app", appJson())
                    put("device", deviceJson(app))
                    put("error", errorJson)
                    put("media", snapshot.media)
                    put("player", snapshot.player)
                    put("audio", audioJson(app))
                    put("ffmpeg", ffmpegJson())
                    // Как ExoPlayer выбирает декодеры для сломавшегося формата — по порядку
                    errorJson.optString("mime").takeIf { it.isNotEmpty() }?.let {
                        put("exoDecoders", exoDecodersJson(it))
                    }
                    put("codecs", codecsJson())
                }
                Log.i(TAG, "Report ${snapshot.kind}: ${snapshot.reason}")

                if (endpoint == null) return@execute
                flushPending(app, endpoint)
                if (!post(endpoint, body.toString())) savePending(app, body.toString())
            } catch (e: Throwable) {
                // Диагностика не должна мешать ничему, даже самой себе
                Log.w(TAG, "Report failed: $e")
            }
        }
    }

    /**
     * Имя и размер файла из TorrServer (POST /torrents get): в ссылке /stream?link=… имени нет,
     * а по расширению видно контейнер — AVI, MKV, TS — и что за раздача.
     */
    private fun addTorrentFile(snapshot: Snapshot) {
        val base = snapshot.torrServer ?: return
        val media = snapshot.media
        // Какой сервер отдаёт поток: TorrServer (MatriX.…) или MonoServer — ошибки данных
        // посреди просмотра могут быть его
        torrServerVersion(base, snapshot.torrHeaders)?.let { media.put("torrServer", it) }
        val hash = media.optString("hash").takeIf { it.isNotEmpty() } ?: return
        val index = media.optString("fileIndex").toIntOrNull()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$base/torrents").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                snapshot.torrHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val request = JSONObject().put("action", "get").put("hash", hash).toString()
            connection.outputStream.use { it.write(request.toByteArray()) }
            if (connection.responseCode !in 200..299) return
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val torrent = root.optJSONObject("Torrent") ?: root
            torrent.optString("title").takeIf { it.isNotEmpty() }?.let { media.put("torrentTitle", it.take(300)) }
            torrent.optLong("torrent_size").takeIf { it > 0 }?.let { media.put("torrentSize", it) }
            val files = torrent.optJSONArray("file_stats") ?: return
            media.put("torrentFiles", files.length())
            for (i in 0 until files.length()) {
                val file = files.optJSONObject(i) ?: continue
                if (index != null && file.optInt("id") != index) continue
                val path = file.optString("path")
                if (path.isNotEmpty()) {
                    media.put("fileName", path.take(300))
                    media.put("container", path.substringAfterLast('.', "").lowercase())
                }
                file.optLong("length").takeIf { it > 0 }?.let { media.put("fileSize", it) }
                break
            }
        } catch (e: Exception) {
            Log.d(TAG, "TorrServer file info unavailable: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    /** Ответ /echo TorrServer — строка версии; null, если не ответил. */
    private fun torrServerVersion(base: String, headers: Map<String, String>): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/echo").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }.trim().take(100).ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // region ограничения

    @Synchronized
    private fun allowed(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val sent = try {
            JSONObject(prefs.getString(KEY_SENT, null) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        // Старше суток — забываем
        val fresh = JSONObject()
        sent.keys().forEach { k ->
            val t = sent.optLong(k)
            if (now - t < DAY_MS) fresh.put(k, t)
        }
        if (fresh.has(key) || fresh.length() >= DAILY_LIMIT) return false
        fresh.put(key, now)
        prefs.edit().putString(KEY_SENT, fresh.toString()).apply()
        return true
    }

    // endregion

    // region отправка

    private fun post(endpoint: String, body: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Вход на сервер (cookie WebView): при включённом входе без неё 401
                ServerCookies.forUrl(endpoint)?.let { setRequestProperty("Cookie", it) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            when {
                code in 200..299 -> {
                    Log.i(TAG, "Sent to $endpoint")
                    true
                }
                // Сервер старый, маршрута ещё нет — отчёт дождётся обновления сервера
                code == 404 -> {
                    Log.w(TAG, "Server has no /api/diagnostics yet, report kept for later")
                    false
                }
                // Отчёт не принят (неверный, лимит, нужен вход) — повторять бессмысленно
                code in 400..499 -> {
                    Log.w(TAG, "Server rejected report: HTTP $code")
                    true
                }
                else -> {
                    Log.w(TAG, "Server error HTTP $code, report kept for later")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Send failed, report kept for later: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun pendingDir(context: Context) = File(context.filesDir, PENDING_DIR)

    private fun savePending(context: Context, body: String) {
        try {
            val dir = pendingDir(context).apply { mkdirs() }
            File(dir, "${System.currentTimeMillis()}.json").writeText(body)
            dir.listFiles()?.sortedBy { it.name }?.dropLast(PENDING_MAX)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep report: $e")
        }
    }

    private fun flushPending(context: Context, endpoint: String) {
        val files = pendingDir(context).listFiles()?.sortedBy { it.name } ?: return
        for (file in files) {
            val body = try {
                file.readText()
            } catch (e: Exception) {
                file.delete()
                continue
            }
            if (!post(endpoint, body)) return
            file.delete()
        }
    }

    // endregion

    // region сбор

    private fun appJson() = JSONObject().apply {
        put("version", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("flavor", BuildConfig.FLAVOR)
        put("buildType", BuildConfig.BUILD_TYPE)
        put("media3", androidx.media3.common.MediaLibraryInfo.VERSION)
    }

    private fun deviceJson(context: Context) = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("brand", Build.BRAND)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("hardware", Build.HARDWARE)
        put("board", Build.BOARD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            put("socManufacturer", Build.SOC_MANUFACTURER)
            put("socModel", Build.SOC_MODEL)
        }
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("buildId", Build.DISPLAY)
        put("fingerprint", Build.FINGERPRINT)
        put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        put("tv", safe { context.isTvBox })
        put("googleTv", safe { context.isGoogleTV })
        safe {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            put("ramMb", mem.totalMem / (1024 * 1024))
            put("lowRam", am.isLowRamDevice)
            put("memoryClassMb", am.memoryClass)
        }
        safe { put("display", displayJson(context)) }
    }

    @Suppress("DEPRECATION")
    private fun displayJson(context: Context): JSONObject {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        return JSONObject().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val mode = display.mode
                put("mode", "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}")
                put("modes", JSONArray(display.supportedModes.map {
                    "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}"
                }.distinct()))
            } else {
                put("refreshRate", display.refreshRate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                put("hdr", JSONArray(display.hdrCapabilities?.supportedHdrTypes?.map { hdrName(it) }.orEmpty()))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                put("isHdr", display.isHdr)
                put("wideColorGamut", display.isWideColorGamut)
            }
        }
    }

    private fun hdrName(type: Int) = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DolbyVision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        4 -> "HDR10+" // HDR_TYPE_HDR10_PLUS, API 29
        else -> "type$type"
    }

    private val PASSTHROUGH_ENCODINGS = linkedMapOf(
        "AC3" to C.ENCODING_AC3,
        "E_AC3" to C.ENCODING_E_AC3,
        "E_AC3_JOC" to C.ENCODING_E_AC3_JOC,
        "AC4" to C.ENCODING_AC4,
        "DTS" to C.ENCODING_DTS,
        "DTS_HD" to C.ENCODING_DTS_HD,
        "DTS_UHD_P2" to C.ENCODING_DTS_UHD_P2,
        "TRUEHD" to C.ENCODING_DOLBY_TRUEHD,
    )

    @Suppress("DEPRECATION")
    private fun audioJson(context: Context) = JSONObject().apply {
        safe {
            val caps = AudioCapabilities.getCapabilities(context)
            put("passthrough", JSONArray(PASSTHROUGH_ENCODINGS.filter { caps.supportsEncoding(it.value) }.keys))
            put("maxChannels", caps.maxChannelCount)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) safe {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            put("outputs", JSONArray(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { audioDeviceJson(it) }))
        }
    }

    private fun audioDeviceJson(d: AudioDeviceInfo) = JSONObject().apply {
        put("type", d.type)
        put("name", d.productName?.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            put("encodings", JSONArray(d.encodings.map { encodingName(it) }))
            put("channels", JSONArray(d.channelCounts.toList()))
            put("sampleRates", JSONArray(d.sampleRates.toList()))
        }
    }

    private fun encodingName(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM_16"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM_8"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "E_AC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        AudioFormat.ENCODING_IEC61937 -> "IEC61937"
        14 -> "DOLBY_TRUEHD"
        18 -> "E_AC3_JOC"
        17 -> "AC4"
        21 -> "PCM_24_PACKED"
        22 -> "PCM_32"
        else -> "enc$e"
    }

    private val FFMPEG_MIMES = listOf(
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_MPEG,
        MimeTypes.AUDIO_MPEG_L2, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_ALAW, MimeTypes.AUDIO_MLAW,
        MimeTypes.AUDIO_AMR_NB, MimeTypes.AUDIO_AMR_WB,
    )

    private fun ffmpegJson() = JSONObject().apply {
        val available = safe { FfmpegLibrary.isAvailable() } == true
        put("available", available)
        if (available) {
            put("version", safe { FfmpegLibrary.getVersion() })
            put("mimes", JSONArray(FFMPEG_MIMES.filter { safe { FfmpegLibrary.supportsFormat(it) } == true }))
        }
    }

    private fun exoDecodersJson(mime: String) = JSONArray().apply {
        safe {
            MediaCodecUtil.getDecoderInfos(mime, false, false).forEach { info ->
                put(JSONObject().apply {
                    put("name", info.name)
                    put("hw", info.hardwareAccelerated)
                    put("vendor", info.vendor)
                    put("adaptive", info.adaptive)
                    put("tunneling", info.tunneling)
                })
            }
        }
    }

    /** Все декодеры устройства: имя, аппаратный ли, форматы с профилями и пределами. */
    private fun codecsJson(): JSONArray {
        codecsCache?.let { return it }
        val out = JSONArray()
        safe {
            for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (info.isEncoder) continue
                out.put(JSONObject().apply {
                    put("name", info.name)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put("hw", info.isHardwareAccelerated)
                        put("swOnly", info.isSoftwareOnly)
                        put("vendor", info.isVendor)
                        if (info.isAlias) put("aliasOf", info.canonicalName)
                    }
                    put("types", JSONArray(info.supportedTypes.mapNotNull { type ->
                        safe { codecTypeJson(info, type) }
                    }))
                })
            }
        }
        codecsCache = out
        return out
    }

    private fun codecTypeJson(info: MediaCodecInfo, type: String): JSONObject {
        val caps = info.getCapabilitiesForType(type)
        return JSONObject().apply {
            put("mime", type)
            if (caps.profileLevels.isNotEmpty()) {
                // [профиль, уровень] — числа MediaCodecInfo.CodecProfileLevel
                put("profiles", JSONArray(caps.profileLevels.map { JSONArray(listOf(it.profile, it.level)) }))
            }
            caps.videoCapabilities?.let { v ->
                put("maxW", v.supportedWidths.upper)
                put("maxH", v.supportedHeights.upper)
                put("maxFps", v.supportedFrameRates.upper)
                put("maxBitrate", v.bitrateRange.upper)
                // 4K60 — то, на чём обычно и упирается
                put("uhd60", safe { v.areSizeAndRateSupported(3840, 2160, 60.0) })
            }
            caps.audioCapabilities?.let { a ->
                put("maxChannels", a.maxInputChannelCount)
                put("sampleRates", a.supportedSampleRateRanges.joinToString(",") { "${it.lower}-${it.upper}" })
            }
            val features = listOf(
                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback,
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
                MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
            ).filter { safe { caps.isFeatureSupported(it) } == true }
            if (features.isNotEmpty()) put("features", JSONArray(features))
        }
    }

    // endregion

    // region ошибка

    private fun errorJson(error: PlaybackException) = JSONObject().apply {
        put("codeName", error.errorCodeName)
        put("code", error.errorCode)
        put("message", error.message?.take(MAX_TEXT))
        if (error is ExoPlaybackException) {
            put("type", when (error.type) {
                ExoPlaybackException.TYPE_SOURCE -> "source"
                ExoPlaybackException.TYPE_RENDERER -> "renderer"
                ExoPlaybackException.TYPE_UNEXPECTED -> "unexpected"
                ExoPlaybackException.TYPE_REMOTE -> "remote"
                else -> "type${error.type}"
            })
            error.rendererName?.let { put("renderer", it) }
            error.rendererFormat?.let { format ->
                put("format", formatJson(format))
                format.sampleMimeType?.let { put("mime", it) }
                put("formatSupport", Util.getFormatSupportString(error.rendererFormatSupport))
            }
        }

        // Подробности из причин: какой декодер, что сказал MediaCodec, состояние AudioTrack
        val causes = JSONArray()
        var cause: Throwable? = error.cause
        var depth = 0
        var root: Throwable = error
        while (cause != null && depth < 8) {
            val c: Throwable = cause
            causes.put(JSONObject().apply {
                put("class", c.javaClass.name)
                put("message", c.message?.take(MAX_TEXT))
                causeDetails(c, this)
            })
            root = c
            cause = c.cause?.takeIf { it !== c }
            depth++
        }
        if (causes.length() > 0) put("causes", causes)
        put("stack", JSONArray(root.stackTrace.take(MAX_STACK).map { it.toString() }))
    }

    private fun causeDetails(cause: Throwable, out: JSONObject) {
        when (cause) {
            is MediaCodecRenderer.DecoderInitializationException -> {
                out.put("decoder", cause.codecInfo?.name)
                out.put("decoderHw", cause.codecInfo?.hardwareAccelerated)
                out.put("mime", cause.mimeType)
                out.put("secure", cause.secureDecoderRequired)
                out.put("diagnostic", cause.diagnosticInfo)
                cause.fallbackDecoderInitializationException?.let {
                    out.put("fallbackDecoder", it.codecInfo?.name)
                    out.put("fallbackMessage", it.message?.take(MAX_TEXT))
                }
            }
            is MediaCodecDecoderException -> {
                out.put("decoder", cause.codecInfo?.name)
                out.put("decoderHw", cause.codecInfo?.hardwareAccelerated)
                out.put("diagnostic", cause.diagnosticInfo)
                out.put("codecError", cause.errorCode)
            }
            is AudioSink.InitializationException -> {
                out.put("audioTrackState", cause.audioTrackState)
                out.put("recoverable", cause.isRecoverable)
                out.put("format", formatJson(cause.format))
            }
            is AudioSink.WriteException -> {
                out.put("audioWriteError", cause.errorCode)
                out.put("recoverable", cause.isRecoverable)
                out.put("format", formatJson(cause.format))
            }
            is AudioSink.ConfigurationException -> out.put("format", formatJson(cause.format))
            is android.media.MediaCodec.CodecException -> {
                out.put("diagnostic", cause.diagnosticInfo)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) out.put("codecError", cause.errorCode)
                out.put("transient", cause.isTransient)
                out.put("recoverable", cause.isRecoverable)
            }
        }
    }

    // endregion

    // region дорожки — для Snapshot, вызывать на главном потоке

    fun formatJson(f: Format) = JSONObject().apply {
        f.sampleMimeType?.let { put("mime", it) }
        f.codecs?.let { put("codecs", it) }
        f.containerMimeType?.let { put("container", it) }
        if (f.width != Format.NO_VALUE) put("width", f.width)
        if (f.height != Format.NO_VALUE) put("height", f.height)
        if (f.frameRate != Format.NO_VALUE.toFloat()) put("fps", f.frameRate.toDouble())
        if (f.bitrate != Format.NO_VALUE) put("bitrate", f.bitrate)
        if (f.channelCount != Format.NO_VALUE) put("channels", f.channelCount)
        if (f.sampleRate != Format.NO_VALUE) put("sampleRate", f.sampleRate)
        if (f.pcmEncoding != Format.NO_VALUE) put("pcmEncoding", f.pcmEncoding)
        f.language?.let { put("lang", it) }
        f.label?.let { put("label", it.take(120)) }
        f.colorInfo?.let { c ->
            put("color", JSONObject().apply {
                put("space", c.colorSpace)
                put("transfer", c.colorTransfer)
                put("range", c.colorRange)
                put("hdrStatic", c.hdrStaticInfo != null)
            })
        }
        if (f.rotationDegrees != 0) put("rotation", f.rotationDegrees)
        if (f.maxInputSize != Format.NO_VALUE) put("maxInputSize", f.maxInputSize)
        if (f.initializationData.isNotEmpty()) {
            put("initData", JSONArray(f.initializationData.map { it.size }))
        }
    }

    fun tracksJson(tracks: Tracks) = JSONArray().apply {
        tracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                put(formatJson(group.getTrackFormat(i)).apply {
                    put("type", trackTypeName(group.type))
                    put("support", Util.getFormatSupportString(group.getTrackSupport(i)))
                    put("selected", group.isTrackSelected(i))
                })
            }
        }
    }

    private fun trackTypeName(type: Int) = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_IMAGE -> "image"
        C.TRACK_TYPE_METADATA -> "metadata"
        else -> "type$type"
    }

    // endregion

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        null
    }
}
