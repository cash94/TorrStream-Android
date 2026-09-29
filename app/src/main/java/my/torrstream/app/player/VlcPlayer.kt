package my.torrstream.app.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout

/**
 * libVLC под интерфейсом Media3 [Player] — запасной движок встроенного плеера.
 *
 * Декодеры устройства не справляются с частью старых форматов (AVI с XviD/DivX, WMV,
 * MPEG-4 Part 2 выше 352×288 у программного декодера Android), а внешние плееры играют их
 * своим FFmpeg. libVLC везёт тот же FFmpeg внутри приложения. Раз это обычный [Player],
 * [InternalPlayerActivity] со всем своим интерфейсом — ХУД, перемотка, серии, дорожки,
 * таймкоды, пропуск заставок — работает с ним без изменений, а PlayerView сам подгоняет
 * поверхность под пропорции кадра.
 *
 * Все вызовы — с главного потока: libVLC доставляет события туда же.
 */
@OptIn(UnstableApi::class)
class VlcPlayer(
    context: Context,
    private val headers: Map<String, String>,
    /** Внешние субтитры — только для элемента, с которого начали (как у ExoPlayer-ветки). */
    private val subtitleUrls: List<String>,
    private val subtitleIndex: Int,
    /** Сколько держать в сетевом кэше; libVLC не умеет сказать, сколько реально скачано. */
    private val cachingMs: Int = DEFAULT_CACHING_MS,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val libVlc = LibVLC(context.applicationContext, LIBVLC_OPTIONS)
    private val mediaPlayer = MediaPlayer(libVlc)

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var startPositionMs = 0L
    private var prepared = false
    private var released = false

    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var error: PlaybackException? = null
    private var durationMs = C.TIME_UNSET
    private var seekable = true
    private var volume = 1f
    private var videoSize = VideoSize.UNKNOWN

    /**
     * Первый кадр выведен — сообщить один раз. PlayerView до этого держит поверх видео
     * чёрную «шторку» и без сигнала так и не убрал бы её.
     */
    private var newFirstFrame = false

    /** Куда просили перемотать: VLC меняет getTime() не сразу, и ползунок прыгал бы назад. */
    private var pendingSeekMs: Long? = null

    private var tracks = Tracks.EMPTY
    private var trackParams = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private val audioGroups = mutableMapOf<TrackGroup, Int>()
    private val textGroups = mutableMapOf<TrackGroup, Int>()

    private var videoView: View? = null
    private val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
        if (v.width > 0 && v.height > 0) mediaPlayer.vlcVout.setWindowSize(v.width, v.height)
    }

    init {
        mediaPlayer.setEventListener { onVlcEvent(it) }
    }

    // region state

    override fun getState(): State {
        val playlist = items.mapIndexed { i, item ->
            val current = i == index
            MediaItemData.Builder(i)
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(if (current && durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(current && seekable)
                .setTracks(if (current) tracks else Tracks.EMPTY)
                .build()
        }
        val state = when {
            items.isEmpty() -> Player.STATE_IDLE
            error != null -> Player.STATE_IDLE
            else -> playbackState
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(if (items.isEmpty()) 0 else index)
            .setContentPositionMs { positionMs() }
            // Сколько скачано вперёд, libVLC не сообщает — показываем его сетевой кэш,
            // который он держит заполненным, пока идёт воспроизведение.
            .setContentBufferedPositionMs {
                positionMs() + if (playbackState == Player.STATE_READY) cachingMs else 0
            }
            .setVolume(volume)
            .setVideoSize(videoSize)
            .setTrackSelectionParameters(trackParams)
            .setNewlyRenderedFirstFrame(newFirstFrame)
            .build()
            .also { newFirstFrame = false }
    }

    private fun positionMs(): Long {
        pendingSeekMs?.let { return it }
        if (released) return 0L
        val t = mediaPlayer.time
        return if (t > 0) t else startPositionMs.coerceAtLeast(0L)
    }

    // endregion

    // region commands

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        this.startPositionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        if (prepared) loadCurrent()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        error = null
        loadCurrent()
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (prepared && error == null) {
            if (playWhenReady) {
                if (playbackState == Player.STATE_ENDED) {
                    startPositionMs = 0
                    loadCurrent()
                } else {
                    mediaPlayer.play()
                }
            } else {
                mediaPlayer.pause()
            }
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        if (mediaItemIndex != index && mediaItemIndex in items.indices) {
            index = mediaItemIndex
            startPositionMs = target
            loadCurrent()
        } else if (prepared) {
            pendingSeekMs = target
            if (playbackState == Player.STATE_ENDED) {
                startPositionMs = target
                loadCurrent()
            } else {
                mediaPlayer.setTime(target, false)
            }
        } else {
            startPositionMs = target
        }
        return done()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume
        mediaPlayer.setVolume((volume * 100).toInt())
        return done()
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        trackParams = trackSelectionParameters
        applyTrackSelection()
        return done()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val vout = mediaPlayer.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
        videoView?.removeOnLayoutChangeListener(layoutListener)
        when (videoOutput) {
            is SurfaceView -> vout.setVideoView(videoOutput)
            is TextureView -> vout.setVideoView(videoOutput)
            else -> {
                Log.w(TAG, "Unsupported video output $videoOutput")
                return done()
            }
        }
        videoView = (videoOutput as View).also { view ->
            view.addOnLayoutChangeListener(layoutListener)
            if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
        }
        vout.attachViews(onNewVideoLayout)
        return done()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        videoView?.removeOnLayoutChangeListener(layoutListener)
        videoView = null
        if (mediaPlayer.vlcVout.areViewsAttached()) mediaPlayer.vlcVout.detachViews()
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        mediaPlayer.stop()
        playbackState = Player.STATE_IDLE
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            released = true
            mediaPlayer.setEventListener(null as MediaPlayer.EventListener?)
            mediaPlayer.stop()
            if (mediaPlayer.vlcVout.areViewsAttached()) mediaPlayer.vlcVout.detachViews()
            videoView?.removeOnLayoutChangeListener(layoutListener)
            videoView = null
            mediaPlayer.release()
            libVlc.release()
        }
        return done()
    }

    // endregion

    // region playback

    private fun loadCurrent() {
        val item = items.getOrNull(index) ?: return
        val uri = item.localConfiguration?.uri ?: return
        error = null
        durationMs = C.TIME_UNSET
        seekable = true
        pendingSeekMs = null
        tracks = Tracks.EMPTY
        audioGroups.clear()
        textGroups.clear()
        playbackState = Player.STATE_BUFFERING

        val media = Media(libVlc, withCredentials(uri))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=$cachingMs")
        if (startPositionMs > 0) media.addOption(":start-time=${startPositionMs / 1000.0}")
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }
            ?.let { media.addOption(":http-user-agent=${it.value}") }
        mediaPlayer.setMedia(media)
        media.release()

        if (index == subtitleIndex) {
            subtitleUrls.forEach { mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it), false) }
        }
        mediaPlayer.play()
        invalidateState()
    }

    /**
     * libVLC 3 не умеет произвольные HTTP-заголовки. Единственный, который шлёт веб, —
     * Basic-авторизация TorrServer; её libVLC понимает, если логин и пароль стоят в адресе.
     */
    private fun withCredentials(uri: Uri): Uri {
        val auth = headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
            ?: return uri
        if (!auth.startsWith("Basic ", true) || uri.userInfo != null) return uri
        val userInfo = try {
            String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT))
        } catch (e: IllegalArgumentException) {
            return uri
        }
        val (user, pass) = userInfo.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val credentials = Uri.encode(user) + ":" + Uri.encode(pass)
        return uri.buildUpon().encodedAuthority(credentials + "@" + uri.encodedAuthority).build()
    }

    private val onNewVideoLayout =
        IVLCVout.OnNewVideoLayoutListener { _, _, _, visibleWidth, visibleHeight, sarNum, sarDen ->
            if (visibleWidth <= 0 || visibleHeight <= 0) return@OnNewVideoLayoutListener
            val ratio = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
            videoSize = VideoSize(visibleWidth, visibleHeight, 0, ratio)
            invalidateState()
        }

    private fun onVlcEvent(event: MediaPlayer.Event) {
        if (released) return
        when (event.type) {
            MediaPlayer.Event.Opening -> playbackState = Player.STATE_BUFFERING
            MediaPlayer.Event.Buffering ->
                if (playbackState != Player.STATE_ENDED) {
                    playbackState = if (event.buffering < 100f) Player.STATE_BUFFERING else Player.STATE_READY
                }
            MediaPlayer.Event.Playing -> {
                playbackState = Player.STATE_READY
                if (!playWhenReady) mediaPlayer.pause()
                refreshTracks()
            }
            MediaPlayer.Event.Paused -> if (playbackState != Player.STATE_BUFFERING) playbackState = Player.STATE_READY
            MediaPlayer.Event.TimeChanged -> {
                val target = pendingSeekMs
                if (target == null || kotlin.math.abs(event.timeChanged - target) < SEEK_SETTLE_MS) {
                    pendingSeekMs = null
                }
                if (playbackState == Player.STATE_BUFFERING && mediaPlayer.isPlaying) {
                    playbackState = Player.STATE_READY
                }
            }
            MediaPlayer.Event.LengthChanged -> durationMs = event.lengthChanged.takeIf { it > 0 } ?: C.TIME_UNSET
            MediaPlayer.Event.SeekableChanged -> seekable = event.seekable
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.Vout -> if (event.voutCount > 0) newFirstFrame = true
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError -> {
                Log.e(TAG, "libVLC could not play ${items.getOrNull(index)?.localConfiguration?.uri}")
                error = PlaybackException(
                    "libVLC: не удалось воспроизвести файл",
                    null,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
            }
            else -> return
        }
        invalidateState()
    }

    private fun onEnded() {
        pendingSeekMs = null
        if (index < items.size - 1) {
            // Следующая серия — как автопереход ExoPlayer
            index++
            startPositionMs = 0
            loadCurrent()
        } else {
            playbackState = Player.STATE_ENDED
        }
    }

    // endregion

    // region tracks

    /**
     * Дорожки libVLC → [Tracks]: по группе на дорожку, чтобы выбор в панели плеера
     * ([TrackSelectionOverride] с индексом 0) однозначно указывал на id дорожки VLC.
     */
    private fun refreshTracks() {
        val groups = mutableListOf<Tracks.Group>()
        audioGroups.clear()
        textGroups.clear()

        mediaPlayer.videoTracks?.filter { it.id >= 0 }?.forEach { t ->
            groups += group("v${t.id}", t.name, MimeTypes.VIDEO_UNKNOWN, t.id == mediaPlayer.videoTrack)
        }
        val currentAudio = mediaPlayer.audioTrack
        mediaPlayer.audioTracks?.filter { it.id >= 0 }?.forEach { t ->
            val g = group("a${t.id}", t.name, MimeTypes.AUDIO_UNKNOWN, t.id == currentAudio)
            audioGroups[g.mediaTrackGroup] = t.id
            groups += g
        }
        val currentSpu = mediaPlayer.spuTrack
        mediaPlayer.spuTracks?.filter { it.id >= 0 }?.forEach { t ->
            val g = group("s${t.id}", t.name, MimeTypes.TEXT_UNKNOWN, t.id == currentSpu)
            textGroups[g.mediaTrackGroup] = t.id
            groups += g
        }
        tracks = Tracks(groups)
    }

    private fun group(id: String, name: String?, mime: String, selected: Boolean): Tracks.Group {
        val format = Format.Builder()
            .setId(id)
            .setLabel(name?.takeIf { it.isNotBlank() })
            .setSampleMimeType(mime)
            .build()
        return Tracks.Group(
            TrackGroup(id, format),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(selected),
        )
    }

    private fun applyTrackSelection() {
        val params = trackParams
        params.overrides.forEach { (group, _) ->
            audioGroups[group]?.let { mediaPlayer.setAudioTrack(it) }
            textGroups[group]?.let { mediaPlayer.setSpuTrack(it) }
        }
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) mediaPlayer.setSpuTrack(-1)
        if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes) mediaPlayer.setAudioTrack(-1)
        refreshTracks()
    }

    // endregion

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        const val TAG = "VlcPlayer"
        const val DEFAULT_CACHING_MS = 3000
        const val SEEK_SETTLE_MS = 1500L

        val LIBVLC_OPTIONS = arrayListOf(
            "--http-reconnect",
            // OpenSL ES, а не AudioTrack: модуль AudioTrack libVLC 3.6 после перезапуска
            // аудиодекодера (смена формата потока в начале файла) не открывался снова
            // («module not functional»), и фильм шёл без звука. Проверено на Android 9.
            "--aout=opensles",
            // Кадр выводится даже с опозданием, а не выбрасывается: на слабой приставке
            // программный декодер лучше чуть отстаёт, чем показывает рваную картинку.
            "--no-drop-late-frames",
            "--no-skip-frames",
        )

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_VIDEO_SURFACE,
            )
            .build()
    }
}
