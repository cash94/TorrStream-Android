package my.torrstream.app.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.concurrent.ConcurrentHashMap

/**
 * Декодеры Dolby/DTS устройства, которые вместо звука отдают тишину или виснут.
 *
 * OMX.amlogic.audio.decoder.ac3/eac3/dtshd без лицензии Dolby/DTS в прошивке (Ugoos и прочие
 * несертифицированные боксы) работают «вхолостую»: кадры идут, AudioTrack играет, ошибок нет —
 * а на выходе нули. Отчёт Ugoos TOX3 (S905X4, Android 11): E-AC3 через этот декодер — тишина,
 * через FFmpeg — звук. Раз ошибки нет, откат на FFmpeg не срабатывает.
 *
 * На ТВ-приставках то же бывает и у других вендоров, а ещё такой декодер может зависнуть при
 * инициализации без исключения — загрузка просто не доходит до READY (Just+ Player, JPP-1005,
 * там на ТВ эти кодеки прячут от платформенных декодеров целиком). Поэтому:
 *  - на ТВ — AC3/E-AC3/DTS/TrueHD не отдаются ни одному декодеру устройства;
 *  - на прочих — только декодерам Amlogic.
 * Только когда FFmpeg эту дорожку умеет, иначе её взять будет некому. Прямой вывод (битстрим на
 * ресивер) декодер не использует — MediaCodecAudioRenderer сначала спрашивает выход, так что
 * Atmos/DTS на ресивер по-прежнему уходят битстримом.
 */
@OptIn(UnstableApi::class)
object MutedAudioDecoders {
    private const val TAG = "MutedAudioDecoders"

    private val MIMES = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_TRUEHD,
    )

    private val AMLOGIC = Regex("""^(omx|c2)\.amlogic\.audio\.decoder\.""", RegexOption.IGNORE_CASE)

    private val ffmpegMimes = ConcurrentHashMap<String, Boolean>()

    fun isAmlogic(codecName: String): Boolean = AMLOGIC.containsMatchIn(codecName)

    /** Эти дорожки на ТВ при включённом FFmpeg уходят в него — пусть рендерер FFmpeg будет в списке. */
    fun handles(mimeType: String): Boolean = mimeType in MIMES

    private fun ffmpegSupports(mime: String): Boolean = ffmpegMimes.getOrPut(mime) {
        try {
            FfmpegLibrary.supportsFormat(mime)
        } catch (e: Throwable) {
            false
        }
    }

    /** Тот же выбор, но без ненадёжных декодеров там, где их заменит FFmpeg. */
    fun skipping(selector: MediaCodecSelector, tv: Boolean) =
        MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val infos = selector.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            val skip: (String) -> Boolean = if (tv) { _ -> true } else ::isAmlogic
            if (mimeType !in MIMES || infos.none { skip(it.name) } || !ffmpegSupports(mimeType)) {
                infos
            } else {
                Log.i(TAG, "$mimeType: skipping ${infos.filter { skip(it.name) }.map { it.name }} for FFmpeg")
                infos.filterNot { skip(it.name) }
            }
        }
}
