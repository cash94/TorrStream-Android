package my.torrstream.app.player

import android.content.Context
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import my.torrstream.app.helpers.Prefs

/**
 * Вывод звука встроенного плеера: декодировать в PCM или отдавать AC3/E-AC3/DTS ресиверу как
 * есть (passthrough, битстрим). Одна настройка на оба движка — ExoPlayer и libVLC.
 *
 * Откуда тишина с саундбаром. ExoPlayer по умолчанию верит тому, что сообщает выход: EDID
 * телевизора по HDMI, системная настройка «объёмный звук» (Amazon, Xiaomi). Телевизор часто
 * заявляет AC3/E-AC3/DTS, а дальше по ARC или оптике саундбар их не принимает (E-AC3 и DTS-HD
 * по ARC не проходят, оптика — только AC3/DTS) — битстрим уходит в пустоту. Без саундбара тот
 * же телевизор заявляет только PCM, и звук декодирует приставка. Поэтому PCM — по умолчанию.
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
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val capabilities = when (mode) {
            // Ничего, кроме PCM: AC3/DTS декодирует MediaCodec устройства или FFmpeg.
            Prefs.AUDIO_PCM -> AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
            Prefs.AUDIO_PASSTHROUGH -> forcedCapabilities(context)
            else -> return null
        }
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(capabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

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
