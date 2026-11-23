package my.torrstream.app.player

import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * Битстрим на ресивер (прямой вывод) после паузы — снова со звуком.
 *
 * AudioTrack прямого вывода, поставленный на паузу и запущенный снова, на многих ТВ и ресиверах
 * молчит: битстрим из приставки идёт, но ресивер на него заново не «захватывается». Картинка
 * идёт, позиция растёт, ошибок нет — звук возвращает только перемотка. А перемотка лечит ровно
 * одним: выход звука закрывается, и следующий буфер открывает новый AudioTrack, чей старт и
 * заставляет ресивер захватиться. Остальное в перемотке лишнее и дорогое (с торрента — перечитать
 * весь запас вперёд), поэтому здесь просим только это: выключить тип дорожек «звук» и включить
 * обратно. Видео при этом не трогается, звук перечитывается из очереди в памяти.
 *
 * - Защёлка [pending] взводится паузой, перемоткой внутри серии, остановкой воспроизведения
 *   дольше [REBUFFER_ARM_MS] (подгрузка, чужой звук); тратится, когда воспроизведение пошло —
 *   только во время игры: выключение/включение на паузе оставляет новый AudioTrack незапущенным,
 *   он простаивает, выход уходит в standby, и звук не вернётся.
 * - Включение обратно — по onTracksChanged: два вызова подряд Media3 может слить и отменить.
 * - [settling] не даёт собственной остановке воспроизведения взвести защёлку снова.
 * - Если после выключения/включения плеер завис в BUFFERING при полном буфере (Realtek, примерно
 *   каждый третий раз) — толчок перемоткой на ту же точку, не больше [MAX_NUDGES] раз подряд.
 *
 * Из Just+ Player (PlayerActivity: audioRestartPending / restartPassthroughAudio, Unlicense).
 * Только для прямого вывода: PCM этим не страдает.
 */
@OptIn(UnstableApi::class)
class PassthroughRestart(
    private val player: ExoPlayer,
    private val handler: Handler,
    private val isPassthrough: () -> Boolean,
) : Player.Listener {

    private var pending = false
    private var inFlight = false
    private var settling = false
    private var everStarted = false
    private var retries = 0
    private var nudges = 0
    private var nudgedThisReselect = false
    private var released = false

    private val restartRunnable = Runnable { restart() }
    private val rebufferArm = Runnable { pending = true }
    private val wedgeRunnable = Runnable { nudgeWedged() }

    fun attach() = player.addListener(this)

    fun release() {
        released = true
        player.removeListener(this)
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(rebufferArm)
        handler.removeCallbacks(wedgeRunnable)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) return
        // Отложенное сработало бы уже на паузе — ровно то, чего нельзя
        handler.removeCallbacks(restartRunnable)
        handler.removeCallbacks(wedgeRunnable)
        pending = true
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Перемотка сбросила выход — ресиверу, может, надо захватиться заново. Только внутри серии:
        // у новой серии свой свежий выход
        if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
            pending = true
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        pending = false
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            handler.removeCallbacks(rebufferArm)
            handler.removeCallbacks(wedgeRunnable)
            settling = false
            if (!nudgedThisReselect) nudges = 0
            nudgedThisReselect = false
            if (everStarted) {
                if (pending) {
                    retries = 0
                    handler.removeCallbacks(restartRunnable)
                    handler.post(restartRunnable)
                }
            } else {
                everStarted = true
                pending = false
            }
        } else if (player.playWhenReady && !settling &&
            (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
        ) {
            // Не пауза, а остановка (подгрузка, чужой звук): короткую не лечим — каждое
            // пересоздание стоит ~0,1 с звука
            handler.postDelayed(rebufferArm, REBUFFER_ARM_MS)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING && settling) {
            handler.removeCallbacks(wedgeRunnable)
            handler.postDelayed(wedgeRunnable, WEDGE_MS)
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        // Вторая половина restart(): выключение дошло до потока воспроизведения — включаем обратно,
        // из текущих параметров, чтобы не затереть выбор дорожки, сделанный между делом
        if (!inFlight) return
        inFlight = false
        Log.i(TAG, "audio reselect: restore")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
    }

    private fun restart() {
        if (released || !isPassthrough() || !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return
        // Временные помехи — ждать, а не бросать: иначе защёлка потрачена, а повторить некому
        if (inFlight || !player.isPlaying) {
            if (retries < MAX_RETRIES) {
                retries++
                handler.postDelayed(restartRunnable, RETRY_MS)
            }
            return
        }
        pending = false
        inFlight = true
        settling = true
        Log.i(TAG, "audio reselect: disable")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
    }

    /** Выключение/включение увело в BUFFERING, а данных впереди полно — рендереры не вернутся сами. */
    private fun nudgeWedged() {
        if (released || !settling || !player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) return
        val position = player.currentPosition
        val ahead = player.bufferedPosition - position
        if (ahead < WEDGE_MIN_AHEAD_MS || nudges >= MAX_NUDGES) return
        nudges++
        nudgedThisReselect = true
        Log.w(TAG, "reselect wedged: $ahead ms ahead, nudge $nudges/$MAX_NUDGES")
        // Та же точка — пустая операция; без EXACT миллисекунда назад уйдёт к прошлому ключевому кадру
        val previous = player.seekParameters
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo((position - 1).coerceAtLeast(0))
        player.setSeekParameters(previous)
    }

    private companion object {
        const val TAG = "PassthroughRestart"
        const val REBUFFER_ARM_MS = 1_500L
        const val RETRY_MS = 100L
        const val MAX_RETRIES = 5
        const val WEDGE_MS = 500L
        const val WEDGE_MIN_AHEAD_MS = 5_000L
        const val MAX_NUDGES = 2
    }
}
