package my.torrstream.app.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import my.torrstream.app.BuildConfig
import my.torrstream.app.helpers.Prefs
import my.torrstream.app.helpers.Prefs.appPrefs

/**
 * Вывод звука встроенного плеера: декодировать в PCM или отдавать AC3/E-AC3/DTS ресиверу как
 * есть (passthrough, битстрим). Одна настройка на оба движка — ExoPlayer и libVLC.
 *
 * Откуда тишина с саундбаром. ExoPlayer по умолчанию верит тому, что сообщает выход: EDID
 * телевизора по HDMI, системная настройка «объёмный звук» (Amazon, Xiaomi). Телевизор часто
 * заявляет AC3/E-AC3/DTS, а дальше по ARC или оптике саундбар их не принимает (E-AC3 и DTS-HD
 * по ARC не проходят, оптика — только AC3/DTS) — битстрим уходит в пустоту. Без саундбара тот
 * же телевизор заявляет только PCM, и звук декодирует приставка. Поэтому PCM — по умолчанию.
 *
 * USB-ЦАП. Битстрим по USB Android не передаёт, а многоканальный PCM и форматы выше 16 бит /
 * 48 кГц ЦАП и аудиосистема телевизора принимают через раз: AudioTrack не создаётся, звука нет
 * или всё падает с ошибкой выхода. Поэтому на USB — только PCM, сведённый в стерео ([Limit.STEREO]);
 * если выход всё равно отказал — стерео 16 бит 48 кГц ([Limit.SAFE]).
 */
@OptIn(UnstableApi::class)
object AudioOutput {

    /** Что можно отдать ресиверу битстримом — порядок не важен. */
    private val SURROUND_ENCODINGS = intArrayOf(
        C.ENCODING_AC3,
        C.ENCODING_E_AC3,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_AC4,
        C.ENCODING_DTS,
        C.ENCODING_DTS_HD,
        C.ENCODING_DTS_UHD_P2,
        C.ENCODING_DOLBY_TRUEHD,
    )

    /**
     * Что «Прямой вывод» отдаёт битстримом, даже если устройство молчит о поддержке: то, что
     * принимает любой ресивер, и по оптике тоже (E-AC3 — почти любой с HDMI). DTS-HD при этом
     * уходит своим ядром DTS, Atmos в E-AC3 — как E-AC3. TrueHD и полный DTS-HD — только если
     * их заявило устройство: ядра у TrueHD нет, его лучше декодировать.
     */
    private val FORCED_ENCODINGS = intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS)

    /** Сколько каналов допускать битстриму, когда устройство о поддержке не сообщает. */
    private const val FORCED_MAX_CHANNELS = 8

    /** Частота «безопасного» выхода: её берёт любой ЦАП и любая аудиосистема. */
    private const val SAFE_SAMPLE_RATE = 48000

    /** Ограничения звукового выхода поверх режима «Вывод звука». */
    enum class Limit {
        NONE,

        /** Только PCM, сведённый в стерео — вывод на USB-ЦАП. */
        STEREO,

        /** Стерео 16 бит 48 кГц — откат, когда звуковой выход отказал. */
        SAFE,
    }

    private val USB_OUTPUT_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_USB_ACCESSORY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(AudioDeviceInfo.TYPE_USB_HEADSET)
    }

    /**
     * Звук уходит на USB-ЦАП (или USB-гарнитуру). Отдельно маршрут не спрашиваем: подключённое
     * USB-аудио система ставит для медиа выше HDMI и динамиков, так что «подключён» и значит
     * «играет туда».
     */
    fun isUsbOutput(context: Context): Boolean {
        // Проверка на эмуляторе, где USB-аудио нет: pref debug_force_usb_audio в settings.xml
        if (BuildConfig.DEBUG && context.appPrefs.getBoolean("debug_force_usb_audio", false)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in USB_OUTPUT_TYPES }
    }

    fun isUsbDevice(device: AudioDeviceInfo): Boolean = device.isSink && device.type in USB_OUTPUT_TYPES

    /** Устройство само заявляет битстрим хотя бы одного многоканального формата. */
    @Suppress("DEPRECATION")
    fun deviceSupportsPassthrough(context: Context): Boolean {
        val caps = AudioCapabilities.getCapabilities(context)
        return SURROUND_ENCODINGS.any { caps.supportsEncoding(it) }
    }

    /**
     * Звуковой выход ExoPlayer под режим; null — оставить выход по умолчанию (режим «Авто»:
     * он сам следит за HDMI и перестраивается, когда ресивер подключат или отключат).
     *
     * Для PCM и принудительного битстрима выход собирается без Context: иначе DefaultAudioSink
     * берёт возможности у системы и подписывается на их изменения, перекрывая заданные.
     */
    @Suppress("DEPRECATION")
    fun buildExoAudioSink(
        context: Context,
        mode: Int,
        limit: Limit,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val capabilities = when {
            // Ничего, кроме PCM: AC3/DTS декодирует MediaCodec устройства или FFmpeg.
            limit != Limit.NONE || mode == Prefs.AUDIO_PCM -> AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
            mode == Prefs.AUDIO_PASSTHROUGH -> forcedCapabilities(context)
            else -> return null
        }
        val builder = DefaultAudioSink.Builder()
            .setAudioCapabilities(capabilities)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        if (limit == Limit.NONE) return builder.setEnableFloatOutput(enableFloatOutput).build()

        // Сведение работает с 16-битным PCM — float на выход не пускаем, и до процессоров
        // DefaultAudioSink сам переводит 24/32 бита и float в 16
        val processors = mutableListOf<AudioProcessor>(stereoDownmix())
        if (limit == Limit.SAFE) {
            processors += SonicAudioProcessor().apply { setOutputSampleRateHz(SAFE_SAMPLE_RATE) }
        }
        return builder
            .setEnableFloatOutput(false)
            .setAudioProcessors(processors.toTypedArray())
            .build()
    }

    /**
     * Сведение 1–8 каналов в стерео. Порядок каналов — как у AudioTrack (по битам маски
     * CHANNEL_OUT_*): 3 — FL FR FC, 4 — FL FR BL BR, 5 — FL FR FC BL BR, 6 (5.1) — FL FR FC
     * LFE BL BR, 7 — ещё BC, 8 (7.1) — FL FR FC LFE BL BR SL SR. Центр и тылы — с −3 дБ,
     * сабвуфер не подмешивается. Матрица нормируется, чтобы сумма не клиппировала, — как
     * сведение FFmpeg по умолчанию. Стерео проходит как есть.
     */
    private fun stereoDownmix(): ChannelMixingAudioProcessor {
        val l = floatArrayOf(1f, 0f)
        val r = floatArrayOf(0f, 1f)
        val c = floatArrayOf(MINUS_3_DB, MINUS_3_DB)
        val sl = floatArrayOf(MINUS_3_DB, 0f)
        val sr = floatArrayOf(0f, MINUS_3_DB)
        val lfe = floatArrayOf(0f, 0f)
        val layouts = mapOf(
            3 to listOf(l, r, c),
            4 to listOf(l, r, sl, sr),
            5 to listOf(l, r, c, sl, sr),
            6 to listOf(l, r, c, lfe, sl, sr),
            7 to listOf(l, r, c, lfe, sl, sr, c),
            8 to listOf(l, r, c, lfe, sl, sr, sl, sr),
        )
        return ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
            putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
            layouts.forEach { (count, rows) ->
                val peak = maxOf(rows.sumOf { it[0].toDouble() }, rows.sumOf { it[1].toDouble() }).toFloat()
                val coefficients = rows.flatMap { row -> row.map { it / peak } }.toFloatArray()
                putChannelMixingMatrix(ChannelMixingMatrix(count, 2, coefficients))
            }
        }
    }

    private const val MINUS_3_DB = 0.7071f

    /**
     * Заявленное устройством плюс [FORCED_ENCODINGS]. На Android 10+ число каналов битстрима
     * всё равно спрашивается у системы (AudioTrack.isDirectPlaybackSupported): если она прямой
     * вывод формата не умеет вовсе, ExoPlayer его декодирует, а не молчит.
     */
    @Suppress("DEPRECATION")
    private fun forcedCapabilities(context: Context): AudioCapabilities {
        val device = AudioCapabilities.getCapabilities(context)
        val encodings = (
                intArrayOf(AudioFormat.ENCODING_PCM_16BIT) +
                        FORCED_ENCODINGS +
                        SURROUND_ENCODINGS.filter { device.supportsEncoding(it) }
                ).distinct().toIntArray()
        return AudioCapabilities(
            encodings,
            maxOf(device.maxChannelCount, FORCED_MAX_CHANNELS),
        )
    }

    /** Кодировки для libVLC в режиме «Прямой вывод» (MediaPlayer.forceAudioDigitalEncodings). */
    @Suppress("DEPRECATION")
    fun forcedVlcEncodings(context: Context): IntArray {
        val device = AudioCapabilities.getCapabilities(context)
        // libVLC понимает только эти (константы AudioFormat совпадают с C.ENCODING_*)
        val vlcKnown = intArrayOf(
            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_DTS, C.ENCODING_DTS_HD,
            C.ENCODING_DOLBY_TRUEHD,
        )
        return (FORCED_ENCODINGS + vlcKnown.filter { device.supportsEncoding(it) })
            .distinct().toIntArray()
    }
}
