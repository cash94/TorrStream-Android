package my.torrstream.app.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Звук через MediaCodec, который не берётся за AAC тех профилей, что декодер устройства не
 * заявляет: Main (mp4a.40.1), SSR (40.3), LTP (40.4).
 *
 * Media3 профиль AAC не проверяет (MediaCodecInfo — только xHE), и такая дорожка уходит в
 * c2.android.aac.decoder, который на первом же кадре падает с ошибкой 0xe (отчёт TCL на
 * Realtek: mp4a.40.1). Плеер потом откатывался на FFmpeg с перезапуском. Теперь рендерер
 * отвечает «не поддерживаю», и дорожку сразу берёт FFmpeg — его декодер AAC эти профили умеет.
 */
@OptIn(UnstableApi::class)
class AacProfileAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : MediaCodecAudioRenderer(
    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
    eventHandler, eventListener, audioSink,
) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val profile = aacProfile(format)
        if (profile != null && profile in RARE_PROFILES && !declared(profile)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        return super.supportsFormat(mediaCodecSelector, format)
    }

    private companion object {
        const val TAG = "AacProfileAudio"

        /** Профили AAC (Audio Object Type), которых у декодеров Android обычно нет. */
        val RARE_PROFILES = setOf(1, 3, 4)

        val declaredProfiles = ConcurrentHashMap<Int, Boolean>()

        /** «mp4a.40.1» → 1; для не-AAC — null. */
        fun aacProfile(format: Format): Int? {
            if (format.sampleMimeType != MimeTypes.AUDIO_AAC) return null
            val parts = format.codecs?.split('.') ?: return null
            if (parts.size < 3 || parts[0] != "mp4a" || parts[1] != "40") return null
            return parts[2].toIntOrNull()
        }

        /** Заявляет ли этот профиль хоть один декодер AAC на устройстве. */
        fun declared(profile: Int): Boolean = declaredProfiles.getOrPut(profile) {
            try {
                val decoders = MediaCodecUtil.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)
                val found = decoders.any { info -> info.profileLevels.any { it.profile == profile } }
                Log.i(TAG, "AAC profile $profile declared by ${if (found) "a decoder" else "no decoder"}: ${decoders.map { it.name }}")
                found
            } catch (e: MediaCodecUtil.DecoderQueryException) {
                true
            }
        }
    }
}
