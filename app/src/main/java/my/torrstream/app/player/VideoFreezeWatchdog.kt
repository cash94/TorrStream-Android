package my.torrstream.app.player

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * Картинка встала, а звук идёт.
 *
 * Media3 судит о зависании по позиции, а позиция идёт от часов звука — застрявший видеорендерер
 * для неё «играет». Поэтому считаем сами: рендерер ничего не сделал ни с одним выходным буфером
 * (ни показал, ни отбросил, ни пропустил) за [FREEZE_MS] — и при этом часы ушли вперёд на
 * [FREEZE_POSITION_MS]. Порознь это норма (низкая частота кадров; перемотка), вместе — нет.
 * Окно для медленного видео растягивается до [FREEZE_FRAMES] кадров.
 *
 * Лечение по ступеням, не больше [MAX_RECOVERIES] на серию:
 * 1. перемотка на ту же точку (EXACT, на 1 мс назад — та же позиция Media3 пропускает): сброс
 *    рендерера, буфер остаётся — ни подгрузки, ни чёрного ожидания;
 * 2. stop() + prepare() — перечитать источник (элемент, позиция, дорожки сохраняются; одно
 *    prepare() вне IDLE ничего не делает).
 *
 * Опрос — только пока идёт воспроизведение. Из Just+ Player (videoFreezeTick, Unlicense).
 */
@OptIn(UnstableApi::class)
class VideoFreezeWatchdog(
    private val player: ExoPlayer,
    private val handler: Handler,
    private val onRecover: (String) -> Unit,
) : Player.Listener {

    private var outputSeen = -1
    private var seenAtMs = 0L
    private var seenAtPositionMs = 0L
    private var recoveries = 0
    private var released = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            check()
            if (player.isPlaying) handler.postDelayed(this, POLL_MS)
        }
    }

    fun attach() = player.addListener(this)

    fun release() {
        released = true
        player.removeListener(this)
        handler.removeCallbacks(tick)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(tick)
        // Свежая точка отсчёта: на паузе ничего не выводится, прошлое окно не в счёт
        outputSeen = -1
        if (isPlaying) handler.postDelayed(tick, POLL_MS)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        recoveries = 0
        outputSeen = -1
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        outputSeen = -1
    }

    /** Всё, что рендерер сделал с выходными буферами: показал, отбросил, пропустил. */
    private fun outputCount(): Int {
        val counters = player.videoDecoderCounters ?: return -1
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount + counters.droppedBufferCount + counters.skippedOutputBufferCount
    }

    private fun check() {
        if (player.videoFormat == null) return
        val output = outputCount()
        if (output < 0) return
        val now = SystemClock.elapsedRealtime()
        val position = player.currentPosition
        if (output != outputSeen) {
            outputSeen = output
            seenAtMs = now
            seenAtPositionMs = position
            return
        }
        val frameRate = player.videoFormat?.frameRate ?: -1f
        val window = if (frameRate > 0) maxOf(FREEZE_MS, (FREEZE_FRAMES * 1000 / frameRate).toLong()) else FREEZE_MS
        if (now - seenAtMs < window || position - seenAtPositionMs < FREEZE_POSITION_MS) return
        // Новое окно при любом исходе: лечению — то же окно на проверку
        outputSeen = -1
        if (recoveries >= MAX_RECOVERIES) return
        recoveries++
        if (recoveries == 1 && player.isCurrentMediaItemSeekable) {
            Log.w(TAG, "video frozen at $position ms, audio on: seek")
            onRecover("seek")
            val previous = player.seekParameters
            player.setSeekParameters(SeekParameters.EXACT)
            player.seekTo((position - 1).coerceAtLeast(0))
            player.setSeekParameters(previous)
        } else {
            Log.w(TAG, "video frozen at $position ms, audio on: prepare")
            onRecover("prepare")
            player.stop()
            player.prepare()
        }
    }

    private companion object {
        const val TAG = "VideoFreezeWatchdog"
        const val POLL_MS = 500L

        /** Не короче: пересоздание кодека на дешёвой приставке тоже до секунды без вывода. */
        const val FREEZE_MS = 1_500L
        const val FREEZE_POSITION_MS = 1_000L
        const val FREEZE_FRAMES = 20
        const val MAX_RECOVERIES = 2
    }
}
