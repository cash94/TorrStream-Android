package my.torrstream.app.player

import android.os.Build
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
 *  - 10 бит — с Android 10: JNI собран из main androidx/media (tools/av1-decoder/build.sh) и
 *    выводит 10-битный кадр как P010 в Surface, а окно P010 (AHARDWAREBUFFER_FORMAT_YCbCr_P010)
 *    есть только с API 29. Раньше — 8 бит, 10-битную дорожку пусть берёт другой путь.
 *    Глубина известна из av1C (MKV, MP4); если нет — пробуем.
 *
 * Конвейер шире стокового (из Just+ Player): у 4K/1080p-релиза AV1 обычно одна плитка, и dav1d
 * распараллеливает между кадрами, а не внутри кадра — maxFrameDelay 2 по умолчанию его душит.
 * 4 кадра в работе (замер Just+: 49 → 92 кадр/с на 8 потоках), буферов больше, чтобы конвейер
 * не простаивал. Потоков — сколько dav1d решит сам.
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
    Libdav1dVideoRenderer(
        allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify,
        Libdav1dVideoRenderer.THREAD_COUNT_DECODER_DEFAULT,
        /* maxFrameDelay = */ 4, /* numInputBuffers = */ 8, /* numOutputBuffers = */ 6,
        /* useCustomAllocator = */ false,
    ),
) {
    private val maxBitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 10 else 8

    override fun getCapabilities(): RendererCapabilities {
        val base = super.getCapabilities()
        return object : RendererCapabilities by base {
            override fun supportsFormat(format: Format): Int {
                val bitDepth = format.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
                if (format.width > maxWidth || format.height > maxHeight || bitDepth > maxBitDepth) {
                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
                }
                return base.supportsFormat(format)
            }
        }
    }
}
