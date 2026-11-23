package my.torrstream.app.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import my.torrstream.app.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Что на самом деле происходит со звуком и видео во встроенном плеере — для «Диагностики» в
 * настройках и ручного отчёта «Нет звука» и т. п.
 *
 * Отчёты об откатах приходят только на ошибку. А «звука нет, но с программным декодером
 * есть» ошибкой не бывает: аппаратный декодер устройства молча отдаёт тишину, или битстрим
 * уходит туда, где его не понимают. Чтобы такое разобрать, нужно знать, что выбрал плеер:
 * какой декодер у звука (или звук ушёл битстримом без декодера), что именно открыто на
 * выходе (кодировка, каналы, частота), пошёл ли звук вообще (onAudioPositionAdvancing) и
 * были ли обрывы. Это и собирается здесь из событий Media3 — по каждой сборке плеера заново.
 */
@OptIn(UnstableApi::class)
class PlaybackInfo : AnalyticsListener {

    private val startedAt = SystemClock.elapsedRealtime()

    var videoDecoder: String? = null
        private set
    private var videoFormat: Format? = null
    private var droppedFrames = 0
    private var firstFrameAtMs = -1L

    private var audioDecoder: String? = null
    private var audioFormat: Format? = null
    private var audioTrack: AudioSink.AudioTrackConfig? = null
    /** Звуковой выход начал проигрывать (не просто открыт) после последнего открытия. */
    private var audioAdvancing = false
    private var audioUnderruns = 0
    private var audioErrors = 0
    private var playerVolume = 1f

    /** Последние события по порядку — для отчёта. */
    private val events = ArrayDeque<String>()

    /** Плеер пересобран (откат, смена настроек): декодеры и выход будут выбраны заново. */
    fun onPlayerBuilt(reason: String) {
        Dv7Converter.resetStatus()
        videoDecoder = null
        videoFormat = null
        firstFrameAtMs = -1L
        audioDecoder = null
        audioFormat = null
        audioTrack = null
        audioAdvancing = false
        event("player built ($reason)")
    }

    fun event(text: String) {
        val t = (SystemClock.elapsedRealtime() - startedAt) / 100 / 10.0
        synchronized(events) {
            events.addLast("+${t}s $text")
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    // region события Media3

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
        Log.i(TAG, "Video decoder: $decoderName")
        event("video decoder $decoderName")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoFormat = format
        event("video format ${formatShort(format)}")
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        if (firstFrameAtMs < 0) {
            firstFrameAtMs = eventTime.currentPlaybackPositionMs
            event("first frame at ${eventTime.currentPlaybackPositionMs}ms")
        }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        this.droppedFrames += droppedFrames
    }

    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        decoderCounters.ensureUpdated()
        Log.i(
            TAG,
            "Video frames: rendered ${decoderCounters.renderedOutputBufferCount}, " +
                    "dropped ${decoderCounters.droppedBufferCount}, " +
                    "skipped ${decoderCounters.skippedOutputBufferCount}, " +
                    "max consecutive dropped ${decoderCounters.maxConsecutiveDroppedBufferCount}",
        )
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        event("video codec error ${videoCodecError.javaClass.simpleName}: ${videoCodecError.message?.take(120)}")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder = decoderName
        Log.i(TAG, "Audio decoder: $decoderName")
        event("audio decoder $decoderName")
    }

    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (audioDecoder == decoderName) audioDecoder = null
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        audioFormat = format
        event("audio format ${formatShort(format)}")
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        audioTrack = audioTrackConfig
        audioAdvancing = false
        Log.i(TAG, "Audio output: ${trackShort(audioTrackConfig)}")
        event("audio output ${trackShort(audioTrackConfig)}")
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        audioAdvancing = true
        event("audio playing")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        audioUnderruns++
        if (audioUnderruns <= 5) event("audio underrun (buffer ${bufferSizeMs}ms, fed ${elapsedSinceLastFeedMs}ms ago)")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        audioErrors++
        event("audio sink error ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message?.take(160)}")
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        audioErrors++
        event("audio codec error ${audioCodecError.javaClass.simpleName}: ${audioCodecError.message?.take(160)}")
    }

    override fun onVolumeChanged(eventTime: AnalyticsListener.EventTime, volume: Float) {
        playerVolume = volume
        event("player volume $volume")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        event("state ${stateName(state)} at ${eventTime.currentPlaybackPositionMs}ms")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        event("error ${error.errorCodeName} at ${eventTime.currentPlaybackPositionMs}ms")
    }

    // endregion

    // region вывод

    /** Звук ушёл на выход как есть (AC3/DTS ресиверу), без декодера на устройстве. */
    val passthrough: Boolean
        get() = audioTrack?.let { !isPcm(it.encoding) } == true

    /** Для отчёта: всё собранное, плюс громкость системы. */
    fun toJson(context: Context) = JSONObject().apply {
        videoFormat?.let { put("videoFormat", formatShort(it)) }
        videoDecoder?.let { put("videoDecoder", it) }
        // Dolby Vision profile 7: сконвертирован ли в 8.1 и если нет — почему
        Dv7Converter.status()?.let { put("dolbyVision7", it) }
        put("droppedFrames", droppedFrames)
        put("firstFrame", firstFrameAtMs >= 0)
        audioFormat?.let { put("audioFormat", formatShort(it)) }
        put("audioDecoder", audioDecoder ?: if (passthrough) "passthrough" else JSONObject.NULL)
        audioTrack?.let { t ->
            put("audioOutput", JSONObject().apply {
                put("encoding", encodingName(t.encoding))
                put("sampleRate", t.sampleRate)
                put("channelMask", t.channelConfig)
                put("channels", Integer.bitCount(t.channelConfig))
                put("tunneling", t.tunneling)
                put("offload", t.offload)
                put("bufferSize", t.bufferSize)
            })
        }
        put("audioPlaying", audioAdvancing)
        put("audioUnderruns", audioUnderruns)
        put("audioErrors", audioErrors)
        put("playerVolume", playerVolume.toDouble())
        systemVolume(context)?.let { (volume, max) ->
            put("systemVolume", volume)
            put("systemVolumeMax", max)
        }
        put("events", JSONArray(synchronized(events) { events.toList() }))
    }

    /** Строки для панели «Диагностика»: что играет и куда — понятным языком. */
    fun lines(context: Context, engine: String): List<String> {
        val out = mutableListOf(context.getString(R.string.player_diag_engine, engine))
        videoFormat?.let { f ->
            val decoder = videoDecoder?.let { "$it (${decoderKind(context, it)})" }
                ?: context.getString(R.string.player_diag_none)
            out += context.getString(R.string.player_diag_video, formatHuman(f), decoder)
        }
        if (droppedFrames > 0) out += context.getString(R.string.player_diag_dropped, droppedFrames)
        audioFormat?.let { f ->
            val decoder = when {
                audioDecoder != null -> "$audioDecoder (${decoderKind(context, audioDecoder!!)})"
                passthrough -> context.getString(R.string.player_diag_passthrough)
                else -> context.getString(R.string.player_diag_none)
            }
            out += context.getString(R.string.player_diag_audio, formatHuman(f), decoder)
        }
        audioTrack?.let { t ->
            out += context.getString(
                R.string.player_diag_output,
                encodingHuman(t.encoding),
                Integer.bitCount(t.channelConfig),
                t.sampleRate / 1000f,
            )
            out += context.getString(
                if (audioAdvancing) R.string.player_diag_audio_playing else R.string.player_diag_audio_silent,
            )
        }
        if (audioFormat != null && audioTrack == null) out += context.getString(R.string.player_diag_no_output)
        if (audioUnderruns > 0) out += context.getString(R.string.player_diag_underruns, audioUnderruns)
        systemVolume(context)?.let { (volume, max) ->
            out += context.getString(
                if (volume == 0) R.string.player_diag_volume_zero else R.string.player_diag_volume,
                volume, max,
            )
        }
        return out
    }

    // endregion

    companion object {
        private const val TAG = "PlaybackInfo"
        private const val MAX_EVENTS = 40

        private fun systemVolume(context: Context): Pair<Int, Int>? = try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } catch (e: Exception) {
            null
        }

        private fun isPcm(encoding: Int) = encoding == C.ENCODING_PCM_16BIT ||
                encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_24BIT ||
                encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_8BIT ||
                encoding == AudioFormat.ENCODING_PCM_16BIT

        /** c2.android.* / OMX.google.* и FFmpeg — программные, остальное — декодер устройства. */
        private fun decoderKind(context: Context, name: String): String {
            val lower = name.lowercase(Locale.US)
            val software = lower.startsWith("c2.android.") || lower.startsWith("omx.google.") ||
                    lower.contains("ffmpeg") || lower.contains("dav1d") || lower.contains("libvlc")
            return context.getString(if (software) R.string.player_diag_software else R.string.player_diag_hardware)
        }

        fun encodingName(e: Int) = when (e) {
            C.ENCODING_PCM_16BIT -> "PCM_16"
            C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            C.ENCODING_PCM_24BIT -> "PCM_24"
            C.ENCODING_PCM_32BIT -> "PCM_32"
            C.ENCODING_PCM_8BIT -> "PCM_8"
            C.ENCODING_AC3 -> "AC3"
            C.ENCODING_E_AC3 -> "E_AC3"
            C.ENCODING_E_AC3_JOC -> "E_AC3_JOC"
            C.ENCODING_AC4 -> "AC4"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS_HD"
            C.ENCODING_DOLBY_TRUEHD -> "TRUEHD"
            else -> "enc$e"
        }

        private fun encodingHuman(e: Int) = when (e) {
            C.ENCODING_PCM_16BIT -> "PCM 16 bit"
            C.ENCODING_PCM_FLOAT -> "PCM float"
            C.ENCODING_PCM_24BIT -> "PCM 24 bit"
            C.ENCODING_PCM_32BIT -> "PCM 32 bit"
            else -> encodingName(e).replace('_', '-')
        }

        private fun trackShort(t: AudioSink.AudioTrackConfig) =
            "${encodingName(t.encoding)} ${Integer.bitCount(t.channelConfig)}ch ${t.sampleRate}Hz" +
                    (if (t.tunneling) " tunneling" else "") + (if (t.offload) " offload" else "")

        private fun formatShort(f: Format) = listOfNotNull(
            f.sampleMimeType,
            f.codecs,
            if (f.width > 0) "${f.width}x${f.height}" else null,
            if (f.channelCount > 0) "${f.channelCount}ch" else null,
            if (f.sampleRate > 0) "${f.sampleRate}Hz" else null,
            f.language,
        ).joinToString(" ")

        private val MIME_NAMES = mapOf(
            MimeTypes.VIDEO_H264 to "H.264", MimeTypes.VIDEO_H265 to "HEVC", MimeTypes.VIDEO_AV1 to "AV1",
            MimeTypes.VIDEO_VP9 to "VP9", MimeTypes.VIDEO_DOLBY_VISION to "Dolby Vision",
            MimeTypes.VIDEO_MP4V to "MPEG-4", MimeTypes.VIDEO_MPEG2 to "MPEG-2", MimeTypes.VIDEO_VC1 to "VC-1",
            MimeTypes.AUDIO_AAC to "AAC", MimeTypes.AUDIO_AC3 to "AC3", MimeTypes.AUDIO_E_AC3 to "E-AC3",
            MimeTypes.AUDIO_E_AC3_JOC to "E-AC3 Atmos", MimeTypes.AUDIO_DTS to "DTS",
            MimeTypes.AUDIO_DTS_HD to "DTS-HD", MimeTypes.AUDIO_TRUEHD to "TrueHD",
            MimeTypes.AUDIO_MPEG to "MP3", MimeTypes.AUDIO_MPEG_L2 to "MP2", MimeTypes.AUDIO_OPUS to "Opus",
            MimeTypes.AUDIO_VORBIS to "Vorbis", MimeTypes.AUDIO_FLAC to "FLAC", MimeTypes.AUDIO_RAW to "PCM",
        )

        private fun formatHuman(f: Format): String {
            val name = MIME_NAMES[f.sampleMimeType] ?: f.sampleMimeType.orEmpty()
            return listOfNotNull(
                name,
                if (f.width > 0) "${f.width}×${f.height}" else null,
                if (f.channelCount > 0) "${f.channelCount} ch" else null,
                if (f.sampleRate > 0) "${f.sampleRate / 1000f} kHz" else null,
            ).joinToString(" · ")
        }

        private fun stateName(state: Int) = when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "idle"
            androidx.media3.common.Player.STATE_BUFFERING -> "buffering"
            androidx.media3.common.Player.STATE_READY -> "ready"
            androidx.media3.common.Player.STATE_ENDED -> "ended"
            else -> "state$state"
        }
    }
}
