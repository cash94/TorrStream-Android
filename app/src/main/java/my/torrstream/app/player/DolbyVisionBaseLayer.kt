package my.torrstream.app.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import my.torrstream.app.App
import java.util.concurrent.ConcurrentHashMap

/**
 * Dolby Vision profile 7 (UHD BDRemux) там, где декодера Dolby Vision для него нет, — как HEVC.
 *
 * У profile 7 базовый слой — обычный HEVC Main10 HDR10, а слой улучшения и метаданные DV
 * лежат в NAL-блоках типов 62/63, которые декодер HEVC по стандарту пропускает. Media3 сама
 * так подменяет профили 4, 8, 9 и 10 (MediaCodecUtil.getAlternativeCodecMimeType), а 7 —
 * нет: «устаревший и не всегда совместимый». Из-за этого такие файлы на ТВ и приставках без
 * DV (и на телефонах) уходили в libVLC. Kodi и Plex в этом случае показывают HDR10 базового
 * слоя — так же и здесь.
 *
 * Подмена только для profile 7 и только если ни один декодер DV на устройстве этот формат
 * не берёт: где DV есть (NVIDIA SHIELD и т. п.), файл идёт как был. Profile 5 не трогаем —
 * его базовый слой в цветах IPT, через HEVC картинка будет зелёно-фиолетовой.
 */
@OptIn(UnstableApi::class)
object DolbyVisionBaseLayer {

    private const val TAG = "DolbyVisionBaseLayer"
    private const val PROFILE_DUAL_LAYER_BD = 7

    /** Ответ по строке codecs: декодеры за время жизни процесса не меняются. */
    private val decisions = ConcurrentHashMap<String, Boolean>()

    /** Формат как есть или он же как HEVC, если DV profile 7 на устройстве не декодируется. */
    fun adapt(format: Format): Format {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
        if (profileOf(format.codecs) != PROFILE_DUAL_LAYER_BD) return format
        val key = "${format.codecs}|${format.width}x${format.height}"
        val toHevc = decisions.getOrPut(key) { shouldPlayAsHevc(format) }
        if (!toHevc) return format
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            // Профиль и уровень в строке — от DV, декодеру HEVC они ничего не скажут
            .setCodecs(null)
            .build()
    }

    /** «dvhe.07.06» / «dvh1.07.06» → 7. */
    private fun profileOf(codecs: String?): Int? {
        val parts = codecs?.split('.') ?: return null
        if (parts.size < 2 || parts[0] !in setOf("dvhe", "dvh1")) return null
        return parts[1].toIntOrNull()
    }

    private fun shouldPlayAsHevc(format: Format): Boolean = try {
        val dv = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
        val hevc = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
        val dvSupported = dv.any { it.isFormatSupported(App.context, format) }
        Log.i(
            TAG,
            "${format.codecs}: DV decoders ${dv.map { it.name }} support=$dvSupported, " +
                    "HEVC decoders ${hevc.map { it.name }}",
        )
        !dvSupported && hevc.isNotEmpty()
    } catch (e: MediaCodecUtil.DecoderQueryException) {
        Log.w(TAG, "Decoder query failed: $e")
        false
    }
}

/** Разборщик, у которого форматы видео проходят через [DolbyVisionBaseLayer.adapt]. */
@OptIn(UnstableApi::class)
class DolbyVisionBaseLayerExtractor(private val delegate: Extractor) : Extractor {

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails

    override fun init(output: ExtractorOutput) = delegate.init(Output(output))

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private class Output(private val delegate: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = Track(delegate.track(id, type))
        override fun endTracks() = delegate.endTracks()
        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
    }

    private class Track(private val delegate: TrackOutput) : TrackOutput {
        override fun format(format: Format) = delegate.format(DolbyVisionBaseLayer.adapt(format))

        override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
