package my.torrstream.app.player

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Программный AV1 (dav1d, tools/av1-decoder) — для устройств, где AV1 нет вовсе (Android до 10
 * без аппаратного декодера). Media3 отдал бы ему любой AV1, а мы — только:
 *  - до [maxWidth]×[maxHeight]: 4K на процессоре ТВ — слайд-шоу, такую дорожку пусть разбирает
 *    hardwareOnlyFormat в плеере («устройство не тянет»);
 *  - 8 бит: JNI dav1d из Media3 1.11.1 10-битный кадр не выводит (kJniStatusHighBitDepth…),
 *    а 10 бит у AV1-релизов частый случай. Глубина известна из av1C (MKV, MP4); если нет — пробуем.
 */
@OptIn(UnstableApi::class)
class SoftwareAv1Renderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
    private val maxWidth: Int,
    private val maxHeight: Int,
) : ForwardingRenderer(
    Libdav1dVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify),
) {
    override fun getCapabilities(): RendererCapabilities {
        val base = super.getCapabilities()
        return object : RendererCapabilities by base {
            override fun supportsFormat(format: Format): Int {
                val bitDepth = format.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
                if (format.width > maxWidth || format.height > maxHeight || bitDepth > 8) {
                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                }
                return base.supportsFormat(format)
            }
        }
    }
}
