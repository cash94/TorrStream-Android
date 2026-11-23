package my.torrstream.app.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import my.torrstream.app.helpers.RemoteKeys
import my.torrstream.app.helpers.ServerCookies
import my.torrstream.app.helpers.coverDisplayCutout
import my.torrstream.app.helpers.isTvBox
import my.torrstream.app.helpers.hideSystemUI
import java.net.URL
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.decoder.av1.Libdav1dVideoRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import my.torrstream.app.App
import my.torrstream.app.R
import my.torrstream.app.helpers.Prefs
import my.torrstream.app.helpers.Prefs.playerAudioDecoder
import my.torrstream.app.helpers.Prefs.playerAudioMode
import my.torrstream.app.helpers.Prefs.playerBufferMb
import my.torrstream.app.helpers.Prefs.playerDecoderMode
import my.torrstream.app.helpers.Prefs.playerEngine
import my.torrstream.app.helpers.Prefs.playerShowClock
import my.torrstream.app.helpers.Prefs.playerNightMode
import my.torrstream.app.helpers.Prefs.playerSubtitleSize
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Built-in video player (Media3/ExoPlayer) with a remote-friendly control bar.
 *
 * It deliberately speaks the same Intent dialect as the supported external players, so the rest of
 * the app (state saving, timeline updates, Android TV "Continue Watching") treats it like any other
 * player and needs no special handling beyond one branch in MainActivity.handlePlayerResult().
 *
 * In: [Intent.getData] plus the extras listed in [Extras].
 * Out: RESULT_OK with action [RESULT_ACTION] and `position` / `duration` / `end_by` extras.
 */
@OptIn(UnstableApi::class)
class InternalPlayerActivity : AppCompatActivity() {

    object Extras {
        const val TITLE = "title"
        const val POSITION = "position"
        const val HEADERS = "headers"          // flat [key, value, key, value] like the other players
        const val PLAYLIST_URLS = "playlist_urls"
        const val PLAYLIST_TITLES = "playlist_titles"
        // String[] parallel to PLAYLIST_URLS: "Серия 1 - Название" from the web UI, "" where unknown
        const val PLAYLIST_EPISODE_TITLES = "playlist_episode_titles"
        const val PLAYLIST_INDEX = "playlist_index"
        const val SUBS = "subs"                // String[] of subtitle urls for the current item
        const val SUBS_NAME = "subs.name"
        const val SKIP_API = "skip_api"        // base url of the intro/credits timing service
        const val TMDB_ID = "tmdb_id"
        const val SEASON = "season"
        const val TIMECODE_API = "timecode_api"  // POST endpoint that stores playback progress
        const val CLIENT_ID = "client_id"
        const val FOCUS_COLOR = "focus_color"  // «#rrggbb» из «Внешнего вида» веб-интерфейса
        // String[] parallel to the queue (one item without a playlist): what «Онлайн субтитры»
        // search for, JSON {tmdb_id, type, season, episode}; "" — the web UI doesn't know
        const val ONLINE_SUBS = "online_subs"
        // Base of the online-subtitles proxy (apiproxy.json: subsApi); absent — straight to the services
        const val ONLINE_SUBS_PROXY = "online_subs_proxy"
        // Заголовок как у веб-плеера: адрес логотипа TMDB, название TMDB (вместо логотипа, пока он
        // грузится или его нет) и String[] строк серии «Сезон 1 · Серия 5 · Название» параллельно
        // очереди. Нет ни логотипа, ни названия — прежний текст: TITLE и «Серия N» после него
        const val TITLE_LOGO = "title_logo"
        const val TITLE_NAME = "title_name"
        const val EPISODE_LINES = "episode_lines"
        // «Переключать серии автоматически» веб-интерфейса. false — список серий в плеере есть,
        // но по концу серии плеер закрывается, как после фильма. Нет extra — переключать.
        const val AUTO_NEXT = "auto_next"
        // POST-адрес отчётов о сбоях плеера (/api/diagnostics сервера), см. PlaybackDiagnostics
        const val DIAGNOSTICS_API = "diagnostics_api"
        // «Автопропуск заставки и титров» веб-интерфейса: кнопка пропуска отсчитывает
        // и пропускает сама, ОК — наоборот, отменяет. Нет extra — обычная кнопка
        const val AUTO_SKIP = "auto_skip"
    }

    private data class SkipRange(val type: String, val startMs: Long, val endMs: Long) {
        val key: String get() = "${type}_${startMs}_$endMs"
    }

    companion object {
        private const val TAG = "InternalPlayer"

        /** Сколько раз за запуск плеер продолжает с того же места после порченых данных потока. */
        private const val MAX_STREAM_RESTARTS = 3

        /** Сколько ждать логотип названия, прежде чем показать вместо него текст (как веб-плеер). */
        private const val TITLE_LOGO_WAIT_MS = 2_500L

        /** Перезапуск после порчи не ушёл дальше этого — значит, упёрлись в то же место. */
        private const val STREAM_PROGRESS_MS = 5_000L

        /** Шаг, на который перезапуск перепрыгивает место, где поток портится раз за разом. */
        private const val STREAM_SKIP_STEP_MS = 10_000L

        /** Порча данных ближе этого к концу файла — конец серии. */
        private const val END_DATA_ERROR_SLACK_MS = 15_000L

        /** Сколько ждать, пока декодер отпустит картинку при сворачивании (по умолчанию 2 с). */
        private const val DETACH_SURFACE_TIMEOUT_MS = 6_000L
        private const val SURFACE_RESTART_DELAY_MS = 1_000L
        const val RESULT_ACTION = "my.torrstream.app.player.result.VIEW"
        const val END_BY_COMPLETION = "completion"
        const val END_BY_USER = "user"

        private const val TYPE_INTRO = "intro"
        private const val TYPE_CREDITS = "credits"
        private const val SKIP_BUTTON_TIMEOUT_MS = 10_000L
        /** Отсчёт автопропуска, секунд — как AUTO_SKIP_INTRO_SEC веб-плеера. */
        private const val AUTO_SKIP_SEC = 5
        private const val STATS_INTERVAL_MS = 1_000L
        private val HASH_RE = Regex("^[0-9a-fA-F]{40}$")
        private const val SCREEN_SAVER_DELAY_MS = 15_000L
        private const val SCREEN_SAVER_DIM = 0.75f
        private val SUBTITLE_STYLE = CaptionStyleCompat(
            Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null,
        )
        /**
         * Пауза с репликой субтитров на экране — её, скорее всего, читают: затемнение
         * откладывается на столько (всего минута, как заставка веб-плеера на паузе).
         */
        private const val SCREEN_SAVER_SUBS_EXTRA_MS = 45_000L
        /** Онлайн-субтитры: как часто сверять реплику с позицией и шаг «раньше / позже». */
        private const val ONLINE_SUBS_TICK_MS = 150L
        private const val ONLINE_SUBS_STEP_MS = 500L
        private const val NEW_LINE = "\n"

        /**
         * ХУД в стиле веб-плеера (hudStat в public/js/player.js): подпись капсом, мельче и
         * приглушённее значения, пары разделены тонкой чертой.
         */
        private const val HUD_LABEL_SIZE = 0.72f
        private const val HUD_LABEL_COLOR = 0x99FFFFFF.toInt()
        private const val HUD_DIVIDER = "\u2003|\u2003"
        private const val HUD_DIVIDER_COLOR = 0x47FFFFFF
        private const val HUD_VALUE_COLOR = 0xFFFFFFFF.toInt()
        /** Запас буфера: красный — вот-вот встанет, жёлтый — впритык, зелёный — с запасом. */
        private const val HUD_LOW_COLOR = 0xFFFF8A80.toInt()
        private const val HUD_MID_COLOR = 0xFFFFD166.toInt()
        private const val HUD_OK_COLOR = 0xFF7EE2A8.toInt()
        private const val BUFFER_LOW_SEC = 10L
        private const val BUFFER_MID_SEC = 20L
        /** Появление и угасание ХУДа — как opacity 0.3s у веб-плеера. */
        private const val HUD_FADE_MS = 250L
        private const val CLOCK_ALPHA_HUD = 0.9f
        private const val CLOCK_ALPHA_IDLE = 0.55f

        /** Как у веб-плеера (startTimecodeSaving): раз в 10 секунд. */
        private const val TIMECODE_SAVE_INTERVAL_MS = 10_000L
        /** Below this the position isn't worth resuming from — matches the web player. */
        private const val TIMECODE_MIN_SEC = 5
        /** This close to the end, resuming would drop the viewer back into the credits. */
        private const val TIMECODE_END_GUARD_SEC = 10

        /** Кнопки и клавиши перемотки — как в веб-плеере, «10» нарисовано на значках. */
        private const val SEEK_STEP_MS = 10_000L

        /**
         * Hold-to-seek acceleration, mirroring the web player: the longer a direction is held,
         * the bigger each jump gets. Pairs of (held for at least N ms) to (step in seconds).
         */
        private val SEEK_ACCELERATION = arrayOf(
            0L to 5, 500L to 10, 1000L to 20, 1500L to 30,
            2000L to 45, 2500L to 60, 3000L to 90, 4000L to 120,
        )

        /** A pause longer than this ends the hold, so the next press starts from the small step. */
        private const val SEEK_HOLD_RESET_MS = 260L
        private const val SEEK_OVERLAY_HIDE_MS = 800L
        private const val PROGRESS_TICK_MS = 500L
        private const val CONTROLS_TIMEOUT_MS = 5_000L

        /** После перемотки панель живёт дольше: промахнулся — поправишь, не вызывая её заново. */
        private const val CONTROLS_AFTER_SEEK_MS = 10_000L
        private const val SEEK_DEBOUNCE_MS = 250L

        /**
         * Пока кнопку держат, перемотка ждёт её отпускания. Это запас на потерянное отпускание:
         * он обязан быть больше паузы перед автоповтором (около 500 мс), иначе первый шаг
         * зажатия уходил в плеер отдельной маленькой перемоткой.
         */
        private const val SEEK_HOLD_COMMIT_MS = 1_000L
        private const val SEEK_BAR_STEPS = 1000
        private const val DIMMED_ALPHA = 0.3f  // .control-btn.is-disabled

        /** Окно второго «Назад» для выхода и время на экране предупреждения (PLAYER_BACK_EXIT_MS). */
        private const val BACK_EXIT_MS = 2_000L

        private val MPEG4_PART2_MIMES = setOf(MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_DIVX, MimeTypes.VIDEO_H263)

        /**
         * Программное видео FFmpeg — только до 1080p: больше на процессоре ТВ не успевает
         * (см. [hardwareOnlyFormat]), такую дорожку рендерер FFmpeg объявляет неподдерживаемой.
         */
        private const val SOFTWARE_VIDEO_MAX_WIDTH = 1920
        private const val SOFTWARE_VIDEO_MAX_HEIGHT = 1088

        /** Кодеки, которые больше 1080p без аппаратного декодера не идут ни в чём, см. [hardwareOnlyFormat]. */
        private val HARDWARE_ONLY_CODECS = mapOf(
            MimeTypes.VIDEO_AV1 to "AV1",
            MimeTypes.VIDEO_H265 to "HEVC",
            MimeTypes.VIDEO_VP9 to "VP9",
        )

        /** Звуковой выход не открылся или отказал — так ломается битстрим, который не принят. */
        private val AUDIO_OUTPUT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        )

        /**
         * «Увеличить»: кадр больше «С полосами» во столько раз, чтобы фильм 2,40:1 с впечатанными
         * в кадр 16:9 чёрными полосами заполнил экран по высоте, — как ZOOM_FACTOR в app.js.
         */
        private const val ZOOM_FACTOR = 2.4f / (16f / 9f)
        private const val SCALE_TOAST_MS = 1500L

        private const val MAX_CONTAINER_RETRIES = 2
        private const val CONTAINER_RETRY_DELAY_MS = 2_000L
        private const val AUDIO_OUTPUT_RETRY_DELAY_MS = 2_500L
    }

    /** ExoPlayer, а для того, что он не может декодировать, — libVLC ([VlcPlayer]). */
    private var player: Player? = null

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var titleLogoView: ImageView
    private lateinit var episodeView: TextView
    private var titleLogoState = LogoState.NONE
    private var titleLogoWaitOver = false
    private lateinit var headerView: View
    private lateinit var statsView: TextView
    private var statsJob: Job? = null
    private lateinit var controlsPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView

    private lateinit var btnPrevEpisode: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnNextEpisode: ImageButton
    private lateinit var btnEpisodes: ImageButton
    private lateinit var btnTracks: ImageButton
    private lateinit var btnResize: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var clockView: TextView
    private lateinit var remainingText: TextView
    private lateinit var dimOverlay: View

    private var screenSaverOn = false

    /**
     * Set after a hardware decoder fails mid-stream. Deliberately not written to preferences: it
     * rescues this file, it should not quietly change what every later file uses.
     */
    private var softwareVideoFallback = false
    private var videoFallbackTried = false

    /**
     * Битстрим на ресивер не открылся (AudioTrack с AC3/DTS не создаётся) — этот файл
     * доигрываем с декодированием в PCM. В настройки не пишется, как и откаты выше.
     */
    private var pcmAudioFallback = false

    /**
     * Звуковой выход отказал и в PCM (так бывает с USB-ЦАП) или звук не декодировался —
     * доигрываем стерео 16 бит 48 кГц, звук декодирует FFmpeg. Только для этого файла.
     */
    private var safeAudioFallback = false

    /** Звук уходит на USB-ЦАП — снимается при каждой сборке плеера, см. [AudioOutput.isUsbOutput]. */
    private var usbAudio = false

    /** Режим вывода звука с учётом отката и USB: одна из констант Prefs.AUDIO_*. */
    private val audioMode: Int
        get() = if (pcmAudioFallback || safeAudioFallback || usbAudio) Prefs.AUDIO_PCM else playerAudioMode

    private val audioLimit: AudioOutput.Limit
        get() = when {
            safeAudioFallback -> AudioOutput.Limit.SAFE
            usbAudio -> AudioOutput.Limit.STEREO
            else -> AudioOutput.Limit.NONE
        }

    /** USB-ЦАП подключили или отключили посреди фильма — пересобрать выход под него. */
    private val audioDeviceCallback by lazy {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
                onAudioDevicesChanged(addedDevices)

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
                onAudioDevicesChanged(removedDevices)
        }
    }

    /**
     * Играет ли сейчас libVLC. Включается настройкой «Движок» или сам, когда ExoPlayer не
     * смог декодировать файл: как и программный декодер, спасает этот файл, в настройки
     * не пишется.
     */
    private var usingVlc = false

    /** Откаты и сбои этого запуска по порядку — уходят в каждый отчёт диагностики. */
    private val fallbackSteps = JSONArray()

    /** Перезапуски с того же места после ошибки данных посреди просмотра, см. onPlayerError. */
    private var streamRestarts = 0

    /** С какой позиции был последний такой перезапуск (-1 — не было): продвинулись ли после него. */
    private var lastStreamRestartAt = -1L

    /**
     * Повторы открытия, когда контейнер файла «не распознан» ни одним разборщиком: в отчётах
     * это обычные MKV серий (8 отчётов 1.1.34–1.1.38), то есть TorrServer отдал не те байты
     * (пусто, страницу ошибки). Повтор — до перехода в libVLC.
     */
    private var containerRetries = 0

    /** Звуковой выход не создался и в «безопасном» режиме — ещё одна попытка с паузой. */
    private var audioOutputRetried = false

    /** Что выбрал плеер для звука и видео и что с ними было — «Диагностика» и отчёты. */
    private val playbackInfo = PlaybackInfo()
    private val startedAt = SystemClock.elapsedRealtime()

    /** Set when the player is rebuilt (buffer change) so playback resumes where it left off. */
    private var resumeIndex: Int? = null
    private var resumePositionMs: Long? = null
    private var resumeTrackParams: TrackSelectionParameters? = null

    /**
     * Озвучка, выбранная руками, — по признакам дорожки. Выбор в ExoPlayer
     * (TrackSelectionOverride) привязан к группе дорожек и переживает смену серии, только если
     * группа в новом файле совпадает с прежней до последнего поля формата; стоит серии отличаться
     * хоть битрейтом — звук возвращается на дорожку по умолчанию. Поэтому выбор помним ещё и
     * сами: язык, название, каналы, кодек и порядковый номер, а на новой серии ищем похожую.
     */
    private data class AudioChoice(
        val language: String?,
        val label: String?,
        val channelCount: Int,
        val mimeType: String?,
        val ordinal: Int,
    )

    private var audioChoice: AudioChoice? = null

    /** Какую дорожку уже пробовали включить из [audioChoice] — чтобы не повторять без конца. */
    private var audioRestoreKey: String? = null

    /**
     * Приложение свернули: в onStop плеер освобождается, в onStart собирается заново с
     * того же места. Пауза перед сворачиванием (playWhenReady до onPause) сохраняется.
     */
    private var stoppedInBackground = false
    private var playingBeforePause = true

    private lateinit var seekOverlay: View
    private lateinit var seekOverlayTime: TextView
    private lateinit var seekOverlayDirection: TextView
    private lateinit var seekOverlayStep: TextView

    // Hold tracking for seek acceleration.
    private var seekHoldStartMs = 0L
    private var lastSeekEventAt = 0L
    private var lastSeekProgress = 0
    private var lastSeekDirection = 1

    private lateinit var skipContainer: View
    private lateinit var skipButton: Button
    private lateinit var skipProgress: View
    private var skipRanges: List<SkipRange> = emptyList()
    private var activeSkipRange: SkipRange? = null
    /** Ranges already offered once, so the button doesn't reappear while inside the same range. */
    private val offeredSkipKeys = mutableSetOf<String>()
    private var skipFetchJob: Job? = null
    private var skipFetchedForIndex = -1

    private var timecodeJob: Job? = null

    /** Отрисовка ASS через libass — только у ExoPlayer, у libVLC она своя. */
    private var assSubtitles: AssSubtitles? = null

    /** Битстрим после паузы — пересоздать выход, чтобы ресивер захватился снова. */
    private var passthroughRestart: PassthroughRestart? = null

    /** Картинка встала, а звук идёт — сбросить рендерер. */
    private var freezeWatchdog: VideoFreezeWatchdog? = null

    // «Онлайн субтитры» (OnlineSubtitles): реплики рисует свой SubtitleView поверх кадра по
    // позиции плеера — одинаково для ExoPlayer и libVLC, и встроенные дорожки ему не мешают
    private lateinit var onlineSubsView: SubtitleView
    private val onlineStore by lazy { OnlineSubtitles.Store(this) }
    private var onlineFileKey: String? = null      // «hash_index» файла, для которого всё ниже
    private var onlineHash: String? = null
    private var onlineTarget: OnlineSubtitles.Target? = null
    private var onlineList: List<OnlineSubtitles.Sub>? = null
    private var onlineAfterSearch: ((List<OnlineSubtitles.Sub>) -> Unit)? = null
    private var onlineSearchJob: Job? = null
    private var onlineLoadJob: Job? = null
    private var onlineActive: OnlineSubtitles.Sub? = null
    private var onlineCues: List<OnlineSubtitles.Cue> = emptyList()
    private var onlineLoadingUrl: String? = null
    private var onlineFailedUrl: String? = null
    private var onlineOffsetMs = 0L
    private var onlineLastText: String? = null
    private var onlinePanelActions: List<() -> Unit>? = null  // список открытой панели онлайн-субтитров

    private lateinit var sidePanel: View
    private lateinit var sidePanelTitle: TextView
    private lateinit var sidePanelList: ListView
    private var sidePanelActions: List<() -> Unit> = emptyList()
    private var sidePanelOpener: View? = null

    /** Куда ведут «Назад» и «влево» из вложенного списка (дорожки → «Аудио и субтитры»). */
    private var sidePanelBack: (() -> Unit)? = null

    /** Set while an item's action swaps in another panel, so the click does not close it. */
    private var sidePanelReplaced = false

    private val handler = Handler(Looper.getMainLooper())

    /** Режимы кнопки масштаба — те же и в том же порядке, что в веб-плеере (app.js). */
    private enum class VideoScale(val label: Int) {
        CONTAIN(R.string.player_scale_contain),
        FILL(R.string.player_scale_fill),
        COVER(R.string.player_scale_cover),
        ZOOM(R.string.player_scale_zoom),
        ORIGINAL(R.string.player_scale_original),
    }

    private var videoScale = VideoScale.CONTAIN
    private lateinit var scaleToast: TextView
    private val hideScaleToast = Runnable { scaleToast.visibility = View.GONE }

    private lateinit var playerHint: View
    private lateinit var exitAlert: View

    /** Когда нажали «Назад» в первый раз; второе нажатие в течение BACK_EXIT_MS — выход. */
    private var lastBackPressAt = 0L
    private val hideExitAlert = Runnable { exitAlert.visibility = View.GONE }

    /** Цвет фокуса из веб-интерфейса (Extras.FOCUS_COLOR), см. HudStyle. */
    private var focusColor = HudStyle.DEFAULT_FOCUS_COLOR

    /** Where "down" from the seek bar returns to — the button the user came up from. */
    private var lastFocusedButton: View? = null

    // Mirrored continuously: after the player is released its position is no longer readable,
    // but finish() still has to report where the user stopped.
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var endedByCompletion = false

    /** Конец серии — следующая серия ([Extras.AUTO_NEXT]); иначе плеер закрывается. */
    private val autoNext: Boolean
        get() = intent.getBooleanExtra(Extras.AUTO_NEXT, true)

    /**
     * Автопропуск заставки и титров ([Extras.AUTO_SKIP]). Переключается и в шестерёнке
     * плеера — тогда новое значение уходит в результат ([Extras.AUTO_SKIP]), и веб
     * сохраняет его у себя (setAutoSkip), как свою галку «Автопропуск заставки и титров».
     */
    private var autoSkipOverride: Boolean? = null
    private val autoSkip: Boolean
        get() = autoSkipOverride ?: intent.getBooleanExtra(Extras.AUTO_SKIP, false)
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        usingVlc = playerEngine == Prefs.ENGINE_VLC
        // Откат на базовый слой DV — только для того фильма, на котором сломался декодер
        DolbyVisionBaseLayer.forceBaseLayer = false
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Видео — на весь экран, как и интерфейс: без этого у телефона с вырезом под
        // камеру сбоку оставалась чёрная полоса, а системные панели поверх видео
        coverDisplayCutout()
        setContentView(R.layout.activity_internal_player)
        hideSystemUI()
        bindViews()
        HudScale.apply(findViewById(R.id.playerRoot))
        padControlsForCutout()
        setupControls()

        try {
            initPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            App.toast(R.string.no_launch_player, true)
            finish()
        }
    }

    // Панели прячутся после диалогов (дорожки, серии) и свайпа от края — прячем снова
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    /**
     * Видео занимает и область выреза, а шапка (название, статистика), часы, нижняя
     * панель и кнопка «Пропустить» отодвигаются от камеры на её ширину — иначе их
     * край оказывался под вырезом. Отступы из разметки — база, к ней прибавляем
     * отступ выреза с той стороны, где он сейчас (меняется при повороте).
     */
    private fun padControlsForCutout() {
        val root = findViewById<View>(R.id.playerRoot)
        val header = intArrayOf(headerView.paddingLeft, headerView.paddingTop, headerView.paddingRight)
        val panel = intArrayOf(controlsPanel.paddingLeft, controlsPanel.paddingRight, controlsPanel.paddingBottom)
        val clockLp = clockView.layoutParams as FrameLayout.LayoutParams
        val clock = intArrayOf(clockLp.topMargin, clockLp.marginEnd)
        val skipLp = skipContainer.layoutParams as FrameLayout.LayoutParams
        val skip = intArrayOf(skipLp.marginEnd, skipLp.bottomMargin)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            headerView.updatePadding(left = header[0] + cut.left, top = header[1] + cut.top, right = header[2] + cut.right)
            controlsPanel.updatePadding(left = panel[0] + cut.left, right = panel[1] + cut.right, bottom = panel[2] + cut.bottom)
            clockView.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = clock[0] + cut.top
                marginEnd = clock[1] + cut.right
            }
            skipContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                marginEnd = skip[0] + cut.right
                bottomMargin = skip[1] + cut.bottom
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        onlineSubsView = findViewById(R.id.onlineSubtitles)
        // Рамка видео зависит от размера экрана: поворот, мультиоконный режим. Из onLayout
        // менять разметку нельзя — через post
        playerView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) playerView.post { applyVideoScale() }
        }
        scaleToast = findViewById(R.id.scaleToast)
        progressBar = findViewById(R.id.playerProgress)
        titleView = findViewById(R.id.playerTitle)
        titleLogoView = findViewById(R.id.playerTitleLogo)
        episodeView = findViewById(R.id.playerEpisode)
        loadTitleLogo()
        headerView = findViewById(R.id.playerHeader)
        statsView = findViewById(R.id.playerStats)
        controlsPanel = findViewById(R.id.controlsPanel)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)

        btnPrevEpisode = findViewById(R.id.btnPrevEpisode)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnNextEpisode = findViewById(R.id.btnNextEpisode)
        btnEpisodes = findViewById(R.id.btnEpisodes)
        btnTracks = findViewById(R.id.btnTracks)
        playerHint = findViewById(R.id.playerHint)
        exitAlert = findViewById(R.id.exitAlert)
        btnResize = findViewById(R.id.btnResize)
        btnSettings = findViewById(R.id.btnSettings)
        clockView = findViewById(R.id.playerClock)
        remainingText = findViewById(R.id.remainingText)
        dimOverlay = findViewById(R.id.dimOverlay)

        seekOverlay = findViewById(R.id.seekOverlay)
        seekOverlayTime = findViewById(R.id.seekOverlayTime)
        seekOverlayDirection = findViewById(R.id.seekOverlayDirection)
        seekOverlayStep = findViewById(R.id.seekOverlayStep)

        skipContainer = findViewById(R.id.skipContainer)
        skipButton = findViewById(R.id.skipButton)
        skipProgress = findViewById(R.id.skipProgress)
        // Drain the bar from its left edge rather than from the middle.
        skipProgress.pivotX = 0f

        sidePanel = findViewById(R.id.sidePanel)
        sidePanelTitle = findViewById(R.id.sidePanelTitle)
        sidePanelList = findViewById(R.id.sidePanelList)
        // Park it just off the right edge once its width is known.
        sidePanel.post { sidePanel.translationX = sidePanel.width.toFloat() }
    }

    // region controls

    private fun setupControls() {
        btnPrevEpisode.setOnClickListener { player?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem() }
        btnNextEpisode.setOnClickListener { player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }
        btnRewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        btnForward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnEpisodes.setOnClickListener { showEpisodesDialog() }
        btnTracks.setOnClickListener { showTracksMenu() }
        btnResize.setOnClickListener { cycleResizeMode() }
        btnSettings.setOnClickListener { showSettingsPanel() }
        applyHudStyle()

        // Remember which button "up" was pressed from, so "down" comes back to the same place.
        val focusTracker = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                lastFocusedButton = view
                seekBar.nextFocusDownId = view.id
                showControls()
            }
        }
        controlButtons().forEach { it.onFocusChangeListener = focusTracker }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration ?: return
                if (duration <= 0) return
                val target = duration * progress / SEEK_BAR_STEPS
                // Show the target instantly, but do the actual seek only once the user settles,
                // otherwise holding the D-pad fires a seek per keypress and the picture stutters.
                positionText.text = formatTime(target)
                handler.removeCallbacks(seekCommit)
                scrubbing = true
                pendingSeekMs = target
                handler.postDelayed(seekCommit, if (seekKeyHeld) SEEK_HOLD_COMMIT_MS else SEEK_DEBOUNCE_MS)
                onScrubbed(progress, target, duration)
                showControls()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                handler.removeCallbacks(seekCommit)
                seekCommit.run()
            }
        })

        skipButton.setOnClickListener {
            // Идёт отсчёт автопропуска — нажатие значит «не пропускай, хочу посмотреть»
            if (autoSkipLeft > 0) hideSkipButton() else executeSkip()
        }

        // Touch: tapping the picture toggles the HUD, or dismisses the slide-out list.
        findViewById<View>(R.id.playerRoot).setOnClickListener {
            when {
                wakeFromScreenSaver() -> Unit
                sidePanelOpen -> hideSidePanel()
                controlsVisible -> {
                    handler.removeCallbacks(hideControls)
                    setControlsVisible(false)
                }

                else -> showControls()
            }
        }

        sidePanelList.setOnItemClickListener { _, _, position, _ ->
            val action = sidePanelActions.getOrNull(position)
            sidePanelReplaced = false
            action?.invoke()
            // Settings drill into further panels (buffer sizes) and reopen themselves after a
            // toggle. Those actions have already put new contents on screen, so closing here
            // would slam the panel shut the moment it opened.
            if (!sidePanelReplaced) hideSidePanel()
        }

        lastFocusedButton = btnPlayPause
        registerAudioDeviceCallback()
    }

    /** Фокус цвета из «Внешнего вида» веба — на кнопках, полосе перемотки и «Пропустить». */
    private fun applyHudStyle() {
        focusColor = HudStyle.parseColor(intent.getStringExtra(Extras.FOCUS_COLOR))
        controlButtons().forEach { it.setBackgroundKeepingPadding(HudStyle.button(this, focusColor, it === btnPlayPause)) }
        seekBar.background = null
        seekBar.progressTintList = null
        seekBar.progressBackgroundTintList = null
        seekBar.thumbTintList = null
        seekBar.progressDrawable = HudStyle.seekTrack(this, focusColor)
        seekBar.thumb = HudStyle.seekThumb(this)
        skipButton.setBackgroundKeepingPadding(HudStyle.skipButton(this, focusColor))
        sidePanelList.selector = HudStyle.listSelector(this, focusColor)
    }

    /** Фон с вписанным кругом (InsetDrawable) иначе подменил бы отступы, и значок вырос бы. */
    private fun View.setBackgroundKeepingPadding(drawable: Drawable) {
        val left = paddingLeft
        val top = paddingTop
        val right = paddingRight
        val bottom = paddingBottom
        background = drawable
        setPadding(left, top, right, bottom)
    }

    // region pause screen saver

    /**
     * A paused frame left on screen for long enough is what burns an OLED panel, so a pause that
     * outlasts the delay drops the HUD and dims everything. Any key or tap brings it back, and the
     * press that wakes is swallowed so it does nothing else.
     */
    private val enterScreenSaver = Runnable {
        if (player?.isPlaying == true) return@Runnable
        // Подгрузка — не пауза: isPlaying и тут false, но человек ждёт картинку. Ждём вместе
        // с ним; нажми он паузу посреди подгрузки, onIsPlayingChanged об этом не сообщит,
        // поэтому проверяем снова, а не бросаем
        if (player?.playWhenReady == true) {
            handler.postDelayed(enterScreenSaverRef, SCREEN_SAVER_DELAY_MS)
            return@Runnable
        }
        // Пелена гасила и реплику, ради которой поставили паузу (так было и в веб-плеере)
        if (!screenSaverDeferred && (player?.currentCues?.cues?.isNotEmpty() == true ||
                    assSubtitles?.hasSelectedTrack == true || !onlineLastText.isNullOrEmpty())
        ) {
            screenSaverDeferred = true
            handler.postDelayed(enterScreenSaverRef, SCREEN_SAVER_SUBS_EXTRA_MS)
            return@Runnable
        }
        screenSaverOn = true
        setControlsVisible(false)
        hideSidePanel()
        clockView.visibility = View.GONE
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.animate().alpha(SCREEN_SAVER_DIM).setDuration(600).start()
    }

    private val enterScreenSaverRef: Runnable get() = enterScreenSaver
    private var screenSaverDeferred = false

    private fun scheduleScreenSaver(isPlaying: Boolean) {
        handler.removeCallbacks(enterScreenSaver)
        screenSaverDeferred = false
        if (!isPlaying) handler.postDelayed(enterScreenSaver, SCREEN_SAVER_DELAY_MS)
    }

    /** True when it actually woke something, so callers can swallow the event that did it. */
    private fun wakeFromScreenSaver(): Boolean {
        handler.removeCallbacks(enterScreenSaver)
        if (!screenSaverOn) return false
        screenSaverOn = false
        dimOverlay.animate().alpha(0f).setDuration(200)
            .withEndAction { dimOverlay.visibility = View.GONE }.start()
        applyClockVisibility()
        showControls()
        scheduleScreenSaver(player?.isPlaying == true)
        return true
    }

    // endregion

    // region settings

    /**
     * Renderers wired to the user's decoder choice.
     *
     * Worth knowing what "software" can and cannot reach: software video is Android's own
     * `c2.android.*` MediaCodec decoders (H.264, HEVC, VP8/9, AV1) plus the bundled FFmpeg
     * extension for MPEG-4 Part 2 (DivX/Xvid), H.263, MS-MPEG4, VC-1/WMV3, MPEG-1/2 and MJPEG
     * up to 1080p — the Android MPEG-4 decoder stops at 352×288.
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val audioMode = audioMode
        val audioLimit = audioLimit
        val videoMode = if (softwareVideoFallback) Prefs.DECODER_SOFTWARE else playerDecoderMode
        val tvBox = isTvBox
        NightMode.level = playerNightMode
        val factory = object : DefaultRenderersFactory(this) {
            // Режим «Вывод звука»: PCM или битстрим решает выход, а не рендерер — рендерер
            // сам переходит на декодер, когда выход формат как есть не берёт.
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = AudioOutput.buildExoAudioSink(
                context, audioMode, audioLimit, enableFloatOutput, enableAudioTrackPlaybackParams,
            ) ?: super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)

            // Программное видео FFmpeg (MPEG-4 Part 2, VC-1, MPEG-2, MJPEG — см.
            // tools/ffmpeg-decoder): место в списке задаёт режим декодера видео, а не режим
            // расширений — тот откат звука переключает на PREFER, и видео бы ушло с железа
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
                )
                out.removeAll { it is ExperimentalFfmpegVideoRenderer || it is Libdav1dVideoRenderer }
                if (videoMode == Prefs.DECODER_HARDWARE) return
                val dropped = DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                val software = listOf(
                    ExperimentalFfmpegVideoRenderer(
                        allowedVideoJoiningTimeMs, eventHandler, eventListener, dropped,
                        Runtime.getRuntime().availableProcessors(),
                        SOFTWARE_VIDEO_MAX_WIDTH, SOFTWARE_VIDEO_MAX_HEIGHT,
                    ),
                    // AV1 — dav1d (tools/av1-decoder), до 1080p; 10 бит — с Android 10
                    SoftwareAv1Renderer(
                        allowedVideoJoiningTimeMs, eventHandler, eventListener, dropped,
                        SOFTWARE_VIDEO_MAX_WIDTH, SOFTWARE_VIDEO_MAX_HEIGHT,
                    ),
                )
                val mediaCodec = out.indexOfFirst { it is MediaCodecVideoRenderer }
                    .takeIf { it >= 0 } ?: out.size
                // «Программный»: FFmpeg раньше MediaCodec (у c2.android.mpeg4 предел 352×288);
                // «Совмещённый»: только когда MediaCodec формат не берёт
                out.addAll(if (videoMode == Prefs.DECODER_SOFTWARE) mediaCodec else mediaCodec + 1, software)
            }

            // Тот же рендерер MediaCodec, но AAC Main/SSR/LTP, которых декодер устройства не
            // заявляет, он отдаёт FFmpeg (AacProfileAudioRenderer), а AC3/E-AC3/DTS — мимо
            // ненадёжных декодеров Dolby/DTS устройства (MutedAudioDecoders). На ТВ FFmpeg в
            // списке всегда, и в «Аппаратном» режиме, — позади платформенного (ON), так что
            // битстрим и декодер устройства для прочего по-прежнему первые. Вне ТВ в «Аппаратном»
            // FFmpeg нет — там и декодеры не убираем, взять дорожку было бы некому
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>,
            ) {
                val mode = if (tvBox && extensionRendererMode == EXTENSION_RENDERER_MODE_OFF)
                    EXTENSION_RENDERER_MODE_ON else extensionRendererMode
                super.buildAudioRenderers(
                    context, mode, mediaCodecSelector, enableDecoderFallback,
                    audioSink, eventHandler, eventListener, out,
                )
                val index = out.indexOfFirst { it.javaClass == MediaCodecAudioRenderer::class.java }
                if (index >= 0) {
                    val selector = if (mode == EXTENSION_RENDERER_MODE_OFF) mediaCodecSelector
                    else MutedAudioDecoders.skipping(mediaCodecSelector, tvBox)
                    out[index] = AacProfileAudioRenderer(
                        context, codecAdapterFactory, selector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink,
                    )
                }
            }
        }
        Log.i(TAG, "Audio output mode $audioMode, limit $audioLimit")
        val configured = when (videoMode) {
            Prefs.DECODER_HARDWARE -> factory
                .setEnableDecoderFallback(false)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setMediaCodecSelector(decoderSelector(preferHardware = true))

            Prefs.DECODER_SOFTWARE -> factory
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setMediaCodecSelector(decoderSelector(preferHardware = false))

            // Combined, the default. EXTENSION_RENDERER_MODE_ON, not _PREFER: the platform
            // renderer keeps first refusal, so HDMI passthrough of AC3/DTS to a receiver still
            // wins where it is available and allowed (see AudioOutput). The bundled FFmpeg
            // decoders only step in when nothing else can handle the track — e.g. a DTS-only
            // file on a phone with no DTS decoder.
            else -> factory
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        // Откат звука: FFmpeg первым — у него нет своих причуд с выходом, как у декодеров
        // Dolby/DTS в прошивках. Видео это не касается: его FFmpeg ставит buildVideoRenderers.
        // Так же — «Декодер звука: FFmpeg»: аппаратный декодер звука молчит, видео — на железе
        return if (safeAudioFallback || playerAudioDecoder == Prefs.AUDIO_DECODER_FFMPEG) {
            configured
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        } else configured
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    /**
     * Сразу после регистрации система сообщает обо всех уже подключённых устройствах — тогда
     * USB совпадает с тем, под что собран плеер, и пересобирать нечего.
     */
    private fun onAudioDevicesChanged(devices: Array<out AudioDeviceInfo>) {
        if (devices.none { AudioOutput.isUsbDevice(it) }) return
        val usb = AudioOutput.isUsbOutput(this)
        if (usb == usbAudio || player == null) return
        Log.i(TAG, "USB audio ${if (usb) "connected" else "disconnected"}, rebuilding audio output")
        // Откаты были под прежний выход
        pcmAudioFallback = false
        safeAudioFallback = false
        restartPlayer("USB audio change")
    }

    /**
     * Reorders the candidate decoders rather than filtering them: a device that reports no
     * decoder of the requested kind should still play the file with the other kind instead of
     * failing outright.
     */
    private fun decoderSelector(preferHardware: Boolean) =
        MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val infos: List<MediaCodecInfo> =
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            infos.sortedByDescending { it.hardwareAccelerated == preferHardware }
        }


    private fun showSettingsPanel() {
        val clockState = getString(if (playerShowClock) R.string.player_on else R.string.player_off)
        val autoSkipState = getString(if (autoSkip) R.string.player_on else R.string.player_off)
        val labels = listOf(
            withDescription(
                "${getString(R.string.player_auto_skip)}: $autoSkipState",
                R.string.player_auto_skip_desc,
            ),
            withDescription(
                "${getString(R.string.player_clock)}: $clockState",
                R.string.player_clock_desc,
            ),
            withDescription(
                "${getString(R.string.player_subtitle_size)}: " +
                        subtitleSizeLabel(playerSubtitleSize),
                R.string.player_subtitle_size_desc,
            ),
            withDescription(
                "${getString(R.string.player_buffer)}: " +
                        getString(R.string.player_buffer_mb, playerBufferMb),
                R.string.player_buffer_desc,
            ),
            withDescription(
                "${getString(R.string.player_decoder)}: " +
                        getString(decoderLabelRes(playerDecoderMode)),
                R.string.player_decoder_desc,
            ),
            withDescription(
                "${getString(R.string.player_engine)}: " +
                        getString(engineLabelRes(playerEngine)),
                R.string.player_engine_desc,
            ),
            withDescription(
                "${getString(R.string.player_audio)}: " +
                        getString(audioLabelRes(playerAudioMode)),
                R.string.player_audio_desc,
            ),
            withDescription(
                "${getString(R.string.player_audio_decoder)}: " +
                        getString(audioDecoderLabelRes(playerAudioDecoder)),
                R.string.player_audio_decoder_desc,
            ),
            withDescription(
                "${getString(R.string.player_night_mode)}: " +
                        getString(nightModeLabelRes(playerNightMode)),
                R.string.player_night_mode_desc,
            ),
            withDescription(getString(R.string.player_diagnostics), R.string.player_diagnostics_desc),
        )
        val actions = listOf<() -> Unit>(
            {
                autoSkipOverride = !autoSkip
                syncAutoSkip()
                showSettingsPanel()
            },
            {
                playerShowClock = !playerShowClock
                applyClockVisibility()
                // Reopen so the row shows the value it now holds.
                showSettingsPanel()
            },
            { showSubtitleSizePanel() },
            { showBufferPanel() },
            { showDecoderPanel() },
            { showEnginePanel() },
            { showAudioPanel() },
            { showAudioDecoderPanel() },
            { showNightModePanel() },
            { showDiagnosticsPanel() },
        )
        showSidePanel(
            titleRes = R.string.player_settings,
            labels = labels,
            checkedIndex = -1,
            actions = actions,
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
        )
    }

    private fun subtitleSizeLabel(level: Int): String =
        resources.getStringArray(R.array.player_subtitle_sizes)[level]

    private fun showSubtitleSizePanel() {
        val levels = Prefs.PLAYER_SUBTITLE_SCALES.indices.toList()
        showSidePanel(
            titleRes = R.string.player_subtitle_size,
            labels = levels.map { subtitleSizeLabel(it) },
            checkedIndex = playerSubtitleSize,
            actions = levels.map { level -> { applySubtitleSizeSetting(level) } },
            opener = btnSettings,
            back = { showSettingsPanel() },
        )
    }

    /**
     * ExoPlayer рисует субтитры SubtitleView — меняем на ходу. libVLC берёт масштаб текста
     * только при создании (--sub-text-scale), поэтому его пересобираем с той же позиции.
     */
    private fun applySubtitleSizeSetting(level: Int) {
        if (level == playerSubtitleSize) return
        playerSubtitleSize = level
        if (usingVlc) restartPlayer("subtitle size $level") else applySubtitleSize()
    }

    /**
     * Размер текста ExoPlayer: доля высоты кадра по умолчанию × множитель уровня. Размеры,
     * зашитые в сами субтитры (стили ASS/SSA), при «Обычном» остаются, иначе — наш размер,
     * а то настройка на таких файлах ничего бы не меняла.
     */
    private fun applySubtitleSize() {
        val scale = Prefs.PLAYER_SUBTITLE_SCALES[playerSubtitleSize]
        playerView.subtitleView?.apply {
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            setApplyEmbeddedFontSizes(scale == 1f)
            // Без плашки: белый текст с чёрной обводкой поверх кадра, как в веб-плеере
            setStyle(SUBTITLE_STYLE)
        }
        assSubtitles?.setFontScale(scale)
        onlineSubsView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
        onlineSubsView.setStyle(SUBTITLE_STYLE)
    }

    private fun showBufferPanel() {
        val options = Prefs.PLAYER_BUFFER_OPTIONS.toList()
        showSidePanel(
            titleRes = R.string.player_buffer,
            labels = options.map { getString(R.string.player_buffer_mb, it) },
            checkedIndex = options.indexOf(playerBufferMb),
            actions = options.map { mb -> { applyBufferSize(mb) } },
            opener = btnSettings,
            back = { showSettingsPanel() },
        )
    }

    private fun decoderDescriptionRes(mode: Int) = when (mode) {
        Prefs.DECODER_HARDWARE -> R.string.player_decoder_hardware_desc
        Prefs.DECODER_SOFTWARE -> R.string.player_decoder_software_desc
        else -> R.string.player_decoder_combined_desc
    }

    /** Title with an explanatory second line, dimmer and smaller so the choice still reads first. */
    private fun withDescription(title: String, descriptionRes: Int): CharSequence =
        withDescription(title, getString(descriptionRes))

    private fun withDescription(title: String, description: String): CharSequence {
        val builder = SpannableStringBuilder(title)
            .append(NEW_LINE)
            .append(description)
        val from = title.length + 1
        builder.setSpan(
            // 0.88 — подпись к названию в панелях веб-плеера (18.5px к 21px)
            RelativeSizeSpan(0.88f), from, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ForegroundColorSpan(0xB3FFFFFF.toInt()), from, builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun decoderLabelRes(mode: Int) = when (mode) {
        Prefs.DECODER_HARDWARE -> R.string.player_decoder_hardware
        Prefs.DECODER_SOFTWARE -> R.string.player_decoder_software
        else -> R.string.player_decoder_combined
    }

    private fun showDecoderPanel() {
        val modes = listOf(Prefs.DECODER_COMBINED, Prefs.DECODER_HARDWARE, Prefs.DECODER_SOFTWARE)
        showSidePanel(
            titleRes = R.string.player_decoder,
            labels = modes.map {
                withDescription(getString(decoderLabelRes(it)), decoderDescriptionRes(it))
            },
            checkedIndex = modes.indexOf(playerDecoderMode),
            actions = modes.map { mode -> { applyDecoderMode(mode) } },
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
        )
    }

    private fun engineLabelRes(engine: Int) = when (engine) {
        Prefs.ENGINE_VLC -> R.string.player_engine_vlc
        else -> R.string.player_engine_auto
    }

    private fun showEnginePanel() {
        val engines = listOf(Prefs.ENGINE_AUTO, Prefs.ENGINE_VLC)
        showSidePanel(
            titleRes = R.string.player_engine,
            labels = engines.map {
                withDescription(
                    getString(engineLabelRes(it)),
                    if (it == Prefs.ENGINE_VLC) R.string.player_engine_vlc_desc
                    else R.string.player_engine_auto_desc,
                )
            },
            checkedIndex = engines.indexOf(playerEngine),
            actions = engines.map { engine -> { applyEngine(engine) } },
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
        )
    }

    private fun audioLabelRes(mode: Int) = when (mode) {
        Prefs.AUDIO_AUTO -> R.string.player_audio_auto
        Prefs.AUDIO_PASSTHROUGH -> R.string.player_audio_passthrough
        else -> R.string.player_audio_pcm
    }

    private fun audioDescriptionRes(mode: Int) = when (mode) {
        Prefs.AUDIO_AUTO -> R.string.player_audio_auto_desc
        Prefs.AUDIO_PASSTHROUGH -> R.string.player_audio_passthrough_desc
        else -> R.string.player_audio_pcm_desc
    }

    private fun showAudioPanel() {
        val modes = listOf(Prefs.AUDIO_PCM, Prefs.AUDIO_AUTO, Prefs.AUDIO_PASSTHROUGH)
        showSidePanel(
            titleRes = R.string.player_audio,
            labels = modes.map {
                withDescription(getString(audioLabelRes(it)), audioDescriptionRes(it))
            },
            checkedIndex = modes.indexOf(playerAudioMode),
            actions = modes.map { mode -> { applyAudioMode(mode) } },
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
        )
    }

    private fun nightModeLabelRes(level: Int) = when (level) {
        NightMode.GENTLE -> R.string.player_night_mode_gentle
        NightMode.STRONG -> R.string.player_night_mode_strong
        else -> R.string.player_night_mode_off
    }

    private fun showNightModePanel() {
        val levels = listOf(NightMode.OFF, NightMode.GENTLE, NightMode.STRONG)
        showSidePanel(
            titleRes = R.string.player_night_mode,
            labels = levels.map {
                withDescription(
                    getString(nightModeLabelRes(it)),
                    when (it) {
                        NightMode.GENTLE -> R.string.player_night_mode_gentle_desc
                        NightMode.STRONG -> R.string.player_night_mode_strong_desc
                        else -> R.string.player_night_mode_off_desc
                    },
                )
            },
            checkedIndex = levels.indexOf(playerNightMode),
            actions = levels.map { level -> { applyNightMode(level) } },
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
        )
    }

    /** Процессор читает уровень на каждом буфере — плеер не пересобираем. */
    private fun applyNightMode(level: Int) {
        playerNightMode = level
        NightMode.level = level
        if (level != NightMode.OFF && playbackInfo.passthrough && !usingVlc) {
            App.toast(R.string.player_night_mode_passthrough, true)
        }
        showSettingsPanel()
    }

    private fun audioDecoderLabelRes(mode: Int) = when (mode) {
        Prefs.AUDIO_DECODER_FFMPEG -> R.string.player_audio_decoder_ffmpeg
        else -> R.string.player_audio_decoder_auto
    }

    private fun showAudioDecoderPanel() {
        val modes = listOf(Prefs.AUDIO_DECODER_AUTO, Prefs.AUDIO_DECODER_FFMPEG)
        showSidePanel(
            titleRes = R.string.player_audio_decoder,
            labels = modes.map {
                withDescription(
                    getString(audioDecoderLabelRes(it)),
                    if (it == Prefs.AUDIO_DECODER_FFMPEG) R.string.player_audio_decoder_ffmpeg_desc
                    else R.string.player_audio_decoder_auto_desc,
                )
            },
            checkedIndex = modes.indexOf(playerAudioDecoder),
            actions = modes.map { mode -> { applyAudioDecoder(mode) } },
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
        )
    }

    /** Декодеры выбираются при сборке плеера — пересобираем с той же позиции. */
    private fun applyAudioDecoder(mode: Int) {
        if (mode == playerAudioDecoder) return
        playerAudioDecoder = mode
        restartPlayer("audio decoder $mode")
    }

    /**
     * «Диагностика»: что сейчас играет и чем — и отчёт разработчику, когда что-то не так, а
     * ошибки нет (звука нет, но с программным декодером есть). Строки сведений — по нажатию
     * обновляются; ниже — «Сообщить: …», отчёт уходит на сервер с описанием проблемы.
     */
    private fun showDiagnosticsPanel(focusIndex: Int = -1) {
        val info = if (usingVlc) listOf(getString(R.string.player_diag_engine, "libVLC"))
        else playbackInfo.lines(this, "ExoPlayer · Media3 ${androidx.media3.common.MediaLibraryInfo.VERSION}")
        val problems = listOf(
            "no_audio" to R.string.player_report_no_audio,
            "no_video" to R.string.player_report_no_video,
            "stutter" to R.string.player_report_stutter,
            "other" to R.string.player_report_other,
        )
        val labels = info.map<String, CharSequence> { it } + problems.map { (_, res) ->
            withDescription(getString(res), R.string.player_report_desc)
        }
        val actions = info.indices.map { index -> { showDiagnosticsPanel(index) } } +
                problems.map { (code, _) -> { sendUserReport(code) } }
        showSidePanel(
            titleRes = R.string.player_diagnostics,
            labels = labels,
            checkedIndex = -1,
            actions = actions,
            opener = btnSettings,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showSettingsPanel() },
            focusIndex = if (focusIndex >= 0) focusIndex else info.size,
        )
    }

    /** Ручной отчёт из «Диагностики»: проблема со слов зрителя и всё, что знает плеер. */
    private fun sendUserReport(problem: String) {
        val exo = player as? ExoPlayer
        val mime = when (problem) {
            "no_audio" -> exo?.audioFormat?.sampleMimeType
            else -> exo?.videoFormat?.sampleMimeType
        }
        App.toast(R.string.player_report_sending, false)
        reportDiagnostics(
            PlaybackDiagnostics.KIND_USER_REPORT,
            "user: $problem",
            null,
            mime,
            problem = problem,
        ) { result ->
            if (isFinishing || isDestroyed) return@reportDiagnostics
            val message = when (result) {
                PlaybackDiagnostics.Result.SENT ->
                    if (problem == "no_audio" && playerAudioDecoder != Prefs.AUDIO_DECODER_FFMPEG && !usingVlc)
                        R.string.player_report_sent_no_audio else R.string.player_report_sent
                else -> R.string.player_report_queued
            }
            App.toast(message, true)
        }
    }

    /** Выход звука собирается вместе с плеером — пересобираем с той же позиции. */
    private fun applyAudioMode(mode: Int) {
        if (mode == playerAudioMode && !pcmAudioFallback && !safeAudioFallback) return
        playerAudioMode = mode
        pcmAudioFallback = false
        safeAudioFallback = false
        restartPlayer("audio mode $mode")
    }

    private fun applyEngine(engine: Int) {
        if (engine == playerEngine) return
        playerEngine = engine
        usingVlc = engine == Prefs.ENGINE_VLC
        restartPlayer("engine $engine")
    }

    /** ExoPlayer не справился с файлом — пересобираем плеер на libVLC с той же позиции. */
    private fun switchToVlc(reason: String) {
        if (usingVlc) return
        usingVlc = true
        Log.w(TAG, "Switching to libVLC: $reason")
        App.toast(R.string.player_switching_vlc, true)
        // Posted: зовётся из колбэков самого плеера
        handler.post { restartPlayer("libVLC fallback") }
    }

    private fun applyDecoderMode(mode: Int) {
        if (mode == playerDecoderMode) return
        playerDecoderMode = mode
        restartPlayer("decoder mode $mode")
    }

    private fun applyBufferSize(megabytes: Int) {
        if (megabytes == playerBufferMb) return
        playerBufferMb = megabytes
        restartPlayer("${megabytes}MB buffer")
    }

    /**
     * Both the LoadControl and the renderers are fixed when the ExoPlayer instance is built, so
     * changing either setting means a new player. Rebuilding keeps the current episode and
     * position, so the change lands on what is playing rather than waiting for the next file —
     * which is the point of reaching for these mid-film.
     */
    /** Почему собирается плеер — первой строкой в журнале [PlaybackInfo]. */
    private var lastBuildReason = "start"

    private fun restartPlayer(what: String, startAtMs: Long? = null) {
        lastBuildReason = what
        rememberResumePoint()
        if (startAtMs != null) resumePositionMs = startAtMs

        // Отсчёт автопропуска и автоскрытие кнопки живут на handler — без этого
        // кнопка осталась бы висеть с замершим отсчётом
        hideSkipButton()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try {
            initPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart with $what", e)
            App.toast(R.string.no_launch_player, true)
            finish()
        }
    }

    /**
     * Часы: включены — видны всегда (с ХУДом ярче). Выключены — появляются вместе с ХУДом и
     * гаснут с ним: время на экране управления нужно и тем, кто не хочет его поверх фильма.
     */
    private fun applyClockVisibility() {
        clockView.animate().cancel()
        if (playerShowClock || hudShown) {
            clockView.alpha = if (hudShown) CLOCK_ALPHA_HUD else CLOCK_ALPHA_IDLE
            clockView.visibility = View.VISIBLE
            updateClock()
        } else {
            clockView.visibility = View.GONE
        }
    }

    private fun fadeClock(hudVisible: Boolean) {
        clockView.animate().cancel()
        if (playerShowClock) {
            clockView.animate().alpha(if (hudVisible) CLOCK_ALPHA_HUD else CLOCK_ALPHA_IDLE)
                .setDuration(HUD_FADE_MS).start()
        } else if (hudVisible) {
            if (clockView.visibility != View.VISIBLE) {
                clockView.alpha = 0f
                clockView.visibility = View.VISIBLE
            }
            updateClock()
            clockView.animate().alpha(CLOCK_ALPHA_HUD).setDuration(HUD_FADE_MS).start()
        } else {
            clockView.animate().alpha(0f).setDuration(HUD_FADE_MS)
                .withEndAction { if (!hudShown && !playerShowClock) clockView.visibility = View.GONE }
                .start()
        }
    }

    private fun updateClock() {
        if (clockView.visibility != View.VISIBLE) return
        clockView.text = android.text.format.DateFormat.getTimeFormat(this).format(Date())
    }

    /** Последняя отрисованная строка буфера — чтобы не перекладывать текст на каждом тике. */
    private var lastStatusKey: String? = null

    /**
     * «БУФЕР 25 сек | ДО КОНЦА 46 мин 49 сек | КОНЕЦ В 23:47» — как строка буфера веб-плеера.
     * Буфер — сколько секунд видео загружено впереди позиции: столько и продержится показ,
     * если сеть пропадёт, поэтому значение окрашено по уровню. Время окончания — то, по чему
     * реально планируют вечер, высчитывать его из «осталось» неудобно.
     */
    private fun updateStatusLine(positionMs: Long, durationMs: Long) {
        val p = player ?: return
        val aheadSec = ((p.bufferedPosition - positionMs) / 1000L).coerceAtLeast(0L)
        val stats = mutableListOf(
            HudStat(getString(R.string.player_hud_buffer), formatBufferAhead(aheadSec), bufferColor(aheadSec)),
        )
        if (durationMs > 0) {
            val leftMs = (durationMs - positionMs).coerceAtLeast(0L)
            val endsAt = Date(System.currentTimeMillis() + leftMs)
            stats += HudStat(getString(R.string.player_hud_left), formatDurationWords(leftMs / 1000L))
            stats += HudStat(
                getString(R.string.player_hud_ends),
                android.text.format.DateFormat.getTimeFormat(this).format(endsAt),
            )
        }
        val key = stats.joinToString("|") { it.value + it.color }
        if (key == lastStatusKey) return
        lastStatusKey = key
        remainingText.text = hudLine(stats)
    }

    private data class HudStat(val label: String, val value: String, val color: Int = HUD_VALUE_COLOR)

    /** Пары «подпись значение» через тонкую черту — вид строк ХУДа веб-плеера. */
    private fun hudLine(stats: List<HudStat>): CharSequence {
        val sb = SpannableStringBuilder()
        stats.forEachIndexed { i, stat ->
            if (i > 0) {
                val from = sb.length
                sb.append(HUD_DIVIDER)
                sb.setSpan(ForegroundColorSpan(HUD_DIVIDER_COLOR), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            var from = sb.length
            sb.append(stat.label.uppercase(Locale.getDefault()))
            sb.setSpan(RelativeSizeSpan(HUD_LABEL_SIZE), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(HUD_LABEL_COLOR), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Полукруглая шпация — зазор между подписью и значением, как 0.45em в вебе
            sb.append('\u2002')
            from = sb.length
            sb.append(stat.value)
            sb.setSpan(ForegroundColorSpan(stat.color), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun bufferColor(aheadSec: Long) = when {
        aheadSec < BUFFER_LOW_SEC -> HUD_LOW_COLOR
        aheadSec < BUFFER_MID_SEC -> HUD_MID_COLOR
        else -> HUD_OK_COLOR
    }

    /** Как в вебе: до минуты — секунды, до часа — минуты, дальше — часы. */
    private fun formatBufferAhead(sec: Long): String = when {
        sec < 60 -> getString(R.string.player_unit_sec, sec.toInt())
        sec < 3600 -> getString(R.string.player_unit_min, (sec / 60).toInt())
        else -> getString(R.string.player_unit_hour, (sec / 3600).toInt())
    }

    /** «1 ч 23 мин», «46 мин 49 сек», «12 сек» — как «До конца» в веб-плеере. */
    private fun formatDurationWords(sec: Long): String {
        val h = (sec / 3600).toInt()
        val m = ((sec % 3600) / 60).toInt()
        val s = (sec % 60).toInt()
        val parts = mutableListOf<String>()
        when {
            h > 0 -> {
                parts += getString(R.string.player_unit_hour, h)
                if (m > 0) parts += getString(R.string.player_unit_min, m)
            }
            m > 0 -> {
                parts += getString(R.string.player_unit_min, m)
                if (s > 0) parts += getString(R.string.player_unit_sec, s)
            }
            else -> parts += getString(R.string.player_unit_sec, s)
        }
        return parts.joinToString(" ")
    }

    // endregion

    // region side panel

    private val sidePanelOpen: Boolean
        get() = sidePanel.visibility == View.VISIBLE

    /**
     * Slides the list in from the right. While it is open the control bar steps aside, so the
     * remote only ever has one place to be.
     */
    private fun showSidePanel(
        titleRes: Int,
        labels: List<CharSequence>,
        checkedIndex: Int,
        actions: List<() -> Unit>,
        opener: View?,
        itemLayout: Int = R.layout.item_side_panel,
        back: (() -> Unit)? = null,
        focusIndex: Int = checkedIndex,
    ) {
        sidePanelActions = actions
        sidePanelOpener = opener
        sidePanelBack = back
        sidePanelReplaced = true
        sidePanelTitle.setText(titleRes)
        // Without this the tick from a previous panel (say episode 3) survives into the next one.
        sidePanelList.clearChoices()
        sidePanelList.adapter = sidePanelAdapter(itemLayout, labels)
        if (checkedIndex in labels.indices) sidePanelList.setItemChecked(checkedIndex, true)
        if (focusIndex in labels.indices) sidePanelList.setSelection(focusIndex)

        handler.removeCallbacks(hideControls)
        setControlsVisible(false)

        sidePanel.visibility = View.VISIBLE
        sidePanel.animate().translationX(0f).setDuration(200).start()
        sidePanelList.requestFocus()
    }

    /** Строки списка — с фокусом цвета из веба, как и кнопки ХУДа. */
    private fun sidePanelAdapter(itemLayout: Int, labels: List<CharSequence>) =
        object : ArrayAdapter<CharSequence>(this, itemLayout, labels) {
            private val textColors = HudStyle.listItemText(focusColor)

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { row ->
                    if (convertView == null) {
                        row.background = HudStyle.listItem(focusColor)
                        (row as? TextView)?.let {
                            it.setTextColor(textColors)
                            HudScale.sideRow(it)
                        }
                    }
                }
        }

    private fun hideSidePanel() {
        if (!sidePanelOpen) return
        sidePanelBack = null
        sidePanel.animate()
            .translationX(sidePanel.width.toFloat())
            .setDuration(200)
            .withEndAction { sidePanel.visibility = View.INVISIBLE }
            .start()
        sidePanelActions = emptyList()
        // Hand the remote back to the button the panel was opened from.
        lastFocusedButton = sidePanelOpener ?: lastFocusedButton
        showControls()
    }

    // endregion

    private fun controlButtons(): List<View> = listOf(
        btnPrevEpisode, btnRewind, btnPlayPause, btnForward, btnNextEpisode,
        btnEpisodes, btnTracks, btnSettings, btnResize
    )

    private var pendingSeekMs = 0L

    /** True only between a scrub keypress and the debounced seek landing. */
    private var scrubbing = false

    /** Стрелку на полосе перемотки держат: реальная перемотка — по отпусканию. */
    private var seekKeyHeld = false

    /** Только что перемотали полосой — панель держим дольше (CONTROLS_AFTER_SEEK_MS). */
    private var seekSettling = false

    private val seekCommit = Runnable {
        player?.seekTo(pendingSeekMs)
        scrubbing = false
        seekSettling = true
        if (controlsVisible) showControls()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = if (p.duration > 0) p.duration else Long.MAX_VALUE
        p.seekTo(min(max(0L, p.currentPosition + deltaMs), duration))
        showControls()
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        showControls()
    }

    private fun cycleResizeMode() {
        val modes = VideoScale.values()
        videoScale = modes[(videoScale.ordinal + 1) % modes.size]
        applyVideoScale()
        scaleToast.setText(videoScale.label)
        scaleToast.visibility = View.VISIBLE
        handler.removeCallbacks(hideScaleToast)
        handler.postDelayed(hideScaleToast, SCALE_TOAST_MS)
        showControls()
    }

    /**
     * Масштаб задаётся рамкой самой поверхности видео, как applyVideoBox в app.js: рамка
     * PlayerView растянута на весь экран (resize_mode="fill"), а поверхность получает по
     * центру прямоугольник нужного размера — и больше экрана, лишнее срезает край окна.
     *
     * Режимы AspectRatioFrameLayout (вписать / обрезать / растянуть) для этого не годились:
     * у кадра 16:9 на экране 16:9 все три дают одну и ту же картинку, и впечатанные в кадр
     * полосы фильма 2,40:1 (например, 3840×2160) убрать было нечем — кнопка ничего не меняла.
     * Субтитры остаются в рамке экрана, поэтому и в «Увеличить» не уезжают за край. Кроме ASS:
     * его надписи привязаны к кадру, так что слой libass идёт за рамкой поверхности.
     */
    private fun applyVideoScale() {
        val surface = playerView.videoSurfaceView ?: return
        val screenW = playerView.width
        val screenH = playerView.height
        val size = player?.videoSize
        var w = ViewGroup.LayoutParams.MATCH_PARENT
        var h = ViewGroup.LayoutParams.MATCH_PARENT
        if (size != null && size.width > 0 && size.height > 0 && screenW > 0 && screenH > 0 &&
            videoScale != VideoScale.FILL
        ) {
            // Рендерер FFmpeg (DecoderVideoRenderer) сообщает размер без формы пикселя, а у
            // DVD-MPEG-2 и части XviD пиксель не квадратный — она есть в формате дорожки
            val formatRatio = (player as? ExoPlayer)?.videoFormat
                ?.takeIf { it.rotationDegrees == 0 }?.pixelWidthHeightRatio
            val pixelRatio = size.pixelWidthHeightRatio.takeIf { it > 0f && it != 1f }
                ?: formatRatio?.takeIf { it > 0f } ?: 1f
            val videoW = size.width * pixelRatio
            val videoH = size.height.toFloat()
            val fit = min(screenW / videoW, screenH / videoH)
            val r = when (videoScale) {
                VideoScale.COVER -> max(screenW / videoW, screenH / videoH)
                VideoScale.ZOOM -> fit * ZOOM_FACTOR
                VideoScale.ORIGINAL -> 1f // пиксель кадра — пиксель экрана
                else -> fit
            }
            w = (videoW * r).roundToInt()
            h = (videoH * r).roundToInt()
        }
        assSubtitles?.fitTo(w, h)
        val lp = surface.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
        surface.layoutParams = (lp ?: FrameLayout.LayoutParams(w, h)).apply {
            width = w
            height = h
            gravity = Gravity.CENTER
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_hud_pause else R.drawable.ic_hud_play)
    }

    /**
     * Как в веб-плеере: у фильма кнопок серий нет вовсе, у сериала крайняя серия гасит свою
     * кнопку, но та остаётся в ряду, чтобы порядок обхода стрелками не менялся.
     */
    private fun updateEpisodeButtons() {
        val p = player
        val series = (p?.mediaItemCount ?: 0) > 1
        val visibility = if (series) View.VISIBLE else View.GONE
        btnPrevEpisode.visibility = visibility
        btnNextEpisode.visibility = visibility
        btnEpisodes.visibility = visibility
        btnPrevEpisode.alpha = if (p?.hasPreviousMediaItem() == true) 1f else DIMMED_ALPHA
        btnNextEpisode.alpha = if (p?.hasNextMediaItem() == true) 1f else DIMMED_ALPHA
    }

    // endregion

    // region controls visibility

    private val hideControls = Runnable {
        seekSettling = false
        setControlsVisible(false)
    }

    private fun showControls() {
        setControlsVisible(true)
        handler.removeCallbacks(hideControls)
        // Only auto-hide while something is actually playing; a paused picture keeps its controls.
        // Перемотка гасит isPlaying на время подгрузки, поэтому отсчёт «после перемотки» идёт
        // с момента, когда картинка снова пошла (onIsPlayingChanged), а не с отпускания кнопки.
        if (player?.isPlaying == true) {
            handler.postDelayed(hideControls, if (seekSettling) CONTROLS_AFTER_SEEK_MS else CONTROLS_TIMEOUT_MS)
        }
    }

    /**
     * Показан ли ХУД по смыслу. Не visibility панели: пока она гаснет, она ещё VISIBLE, а
     * нажатие в это время должно будить ХУД, а не срабатывать на гаснущих кнопках.
     */
    private var hudShown = true

    private fun setControlsVisible(visible: Boolean) {
        val wasVisible = hudShown
        hudShown = visible
        fadeHud(controlsPanel, visible)
        fadeHud(headerView, visible)
        fadeHud(playerHint, visible)
        raiseSubtitles(visible)
        fadeClock(visible)
        // Статистику TorrServer опрашиваем, только пока её видно
        if (visible) startTorrStats() else stopTorrStats()
        // The read-out belongs to the seek bar; without the bar there is nothing to read.
        if (!visible) {
            handler.removeCallbacks(hideSeekOverlay)
            seekOverlay.visibility = View.GONE
        }
        if (skipVisible) skipContainer.post { positionSkipButton() }
        if (!visible && wasVisible) {
            // ХУД снова откроется на воспроизведении/паузе, как в веб-плеере: иначе двойное ОК
            // «показать и поставить паузу» нажимало кнопку, оставшуюся с прошлого раза, —
            // например, перемотку на 10 секунд. Фокус переносится при показе, а не сейчас:
            // фокус на кнопке сам показывает ХУД (focusTracker).
            lastFocusedButton = btnPlayPause
            seekBar.nextFocusDownId = btnPlayPause.id
        }
        if (visible && !wasVisible) {
            // A live skip offer keeps the remote — it expires, the control bar doesn't.
            if (!skipButtonFocused) (lastFocusedButton ?: btnPlayPause).requestFocus()
        }
    }

    private val controlsVisible: Boolean
        get() = hudShown

    /**
     * Реплики ExoPlayer — над панелью управления, пока она видна, как в веб-плеере (класс
     * cues-raised): иначе крупные субтитры ложились под кнопки и полосу перемотки. libVLC
     * рисует их в самом кадре — там не сдвинуть.
     */
    private fun raiseSubtitles(hudVisible: Boolean) {
        val panel = controlsPanel.height
        val screen = playerView.height
        val fraction = if (hudVisible && panel > 0 && screen > 0) panel.toFloat() / screen
        else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION
        playerView.subtitleView?.setBottomPaddingFraction(fraction)
        // Онлайн-субтитры рисуем сами — их поднимаем и при libVLC
        onlineSubsView.setBottomPaddingFraction(fraction)
    }

    /** Плавное появление и угасание, как у веб-плеера; когда угасание закончилось — GONE. */
    private fun fadeHud(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(HUD_FADE_MS).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(HUD_FADE_MS)
                .withEndAction { if (!hudShown) view.visibility = View.GONE }
                .start()
        }
    }

    /**
     * Remote navigation, as specified: the button row runs left to right, "up" from it lands on
     * the seek bar (where left/right scrub), "up" again hides the panel, and "down" returns.
     */
    override fun dispatchKeyEvent(original: KeyEvent): Boolean {
        // RETURN пультов Tanix/X96 (ESCAPE, «B» геймпада, назначенная кнопка) — как «Назад»
        val event = RemoteKeys.toBack(this, original) ?: original
        val seekKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        // Отпустили стрелку на полосе: теперь перематываем, короткая пауза склеит частые нажатия
        if (event.action == KeyEvent.ACTION_UP && seekKey && seekKeyHeld) {
            seekKeyHeld = false
            if (scrubbing) {
                handler.removeCallbacks(seekCommit)
                handler.postDelayed(seekCommit, SEEK_DEBOUNCE_MS)
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // While dimmed, the first press only wakes: it should not also seek or open a panel.
            if (wakeFromScreenSaver()) return true
            // The slide-out list owns the remote while it is open: up/down walk the list, and
            // left/back close it (left, because the panel sits against the right edge).
            if (sidePanelOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                ) {
                    if (event.repeatCount == 0) sidePanelBack?.invoke() ?: hideSidePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Повтор от ИК-драйвера при коротком нажатии — не второе «Назад»: иначе
                    // одно нажатие сразу закрывало бы плеер
                    if (event.repeatCount == 0) onBackInPlayer()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (controlsVisible && seekBar.hasFocus()) {
                        handler.removeCallbacks(hideControls)
                        setControlsVisible(false)
                        return true
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Как в веб-плеере (control.js): вниз при скрытой панели — сразу на полосу
                    // перемотки, дальше влево/вправо перематывают без шага с кнопки паузы
                    if (!controlsVisible && !skipButtonFocused) {
                        showControls()
                        seekBar.requestFocus()
                        return true
                    }
                    // Автоповтор того же нажатия не должен сразу уводить с полосы на кнопки
                    if (event.repeatCount > 0 && seekBar.hasFocus()) return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Горячие клавиши: при скрытой панели влево/вправо сразу перематывают.
                    // Панель открывается на полосе, и это же нажатие уходит в неё — дальше
                    // как обычно: удержание ускоряет, перемотка по отпусканию
                    if (!controlsVisible && !skipButtonFocused) {
                        showControls()
                        seekBar.requestFocus()
                        seekKeyHeld = true
                        return super.dispatchKeyEvent(event)
                    }
                }

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Let OK reach the skip button when it is the thing holding focus,
                    // instead of merely summoning the control bar.
                    if (!controlsVisible && !skipButtonFocused) {
                        showControls()
                        return true
                    }
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    player?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-SEEK_STEP_MS)
                    return true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(SEEK_STEP_MS)
                    return true
                }
            }

            // Any other D-pad press with the panel hidden just brings it back, without also
            // acting on whatever happens to be focused underneath.
            if (!controlsVisible && !skipButtonFocused && event.isDirectionalOrEnter()) {
                showControls()
                return true
            }
            if (controlsVisible) {
                if (seekKey) {
                    if (seekBar.hasFocus()) seekKeyHeld = true
                } else {
                    seekSettling = false
                }
                showControls() // keep it alive while the user is navigating
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Как в веб-плеере (control.js: playerBackPress): первое «Назад» прячет ХУД и предупреждает,
     * второе в течение BACK_EXIT_MS — выход. Второе выходит, даже если ХУД снова на экране.
     */
    private fun onBackInPlayer() {
        val now = SystemClock.uptimeMillis()
        if (lastBackPressAt != 0L && now - lastBackPressAt < BACK_EXIT_MS) {
            lastBackPressAt = 0L
            finish()
            return
        }
        lastBackPressAt = now
        handler.removeCallbacks(hideControls)
        setControlsVisible(false)
        exitAlert.visibility = View.VISIBLE
        handler.removeCallbacks(hideExitAlert)
        handler.postDelayed(hideExitAlert, BACK_EXIT_MS)
    }

    private fun KeyEvent.isDirectionalOrEnter(): Boolean = keyCode in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    // endregion

    // region skip intro / credits

    private val hideSkip = Runnable { hideSkipButton() }

    /** Сколько секунд осталось до автопропуска; 0 — отсчёта нет. */
    private var autoSkipLeft = 0

    /**
     * Тик отсчёта автопропуска. На паузе отсчёт стоит, как в веб-плеере: поставив
     * на паузу в начале заставки, человек вернулся бы уже после неё.
     */
    private val autoSkipTick = object : Runnable {
        override fun run() {
            val range = activeSkipRange
            if (range == null || autoSkipLeft <= 0) return
            if (player?.isPlaying == true) {
                autoSkipLeft--
                if (autoSkipLeft <= 0) {
                    Log.d(TAG, "Auto-skip: ${range.type}")
                    executeSkip()
                    return
                }
                skipButton.text = autoSkipLabel(range)
                animateSkipProgress(autoSkipLeft.toFloat() / AUTO_SKIP_SEC, 1_000L)
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    /** Пропуск титров — это следующая серия, сам он идёт, только если она есть и автопереход включён. */
    private fun shouldAutoSkip(range: SkipRange): Boolean {
        if (!autoSkip) return false
        return range.type == TYPE_INTRO ||
                (range.type == TYPE_CREDITS && autoNext && player?.hasNextMediaItem() == true)
    }

    private fun autoSkipLabel(range: SkipRange): String = getString(
        if (range.type == TYPE_INTRO) R.string.player_auto_skip_intro
        else R.string.player_auto_skip_credits,
        autoSkipLeft
    )

    private fun animateSkipProgress(to: Float, durationMs: Long) {
        skipProgress.animate().cancel()
        skipProgress.animate()
            .scaleX(to)
            .setDuration(durationMs)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    /**
     * Автопропуск переключили, пока кнопка пропуска на экране (как syncAutoSkipCountdown
     * веб-плеера): включили — заводим отсчёт, выключили — обычная кнопка на своё время.
     */
    private fun syncAutoSkip() {
        val range = activeSkipRange ?: return
        if (!skipVisible) return
        val want = shouldAutoSkip(range)
        if (want == (autoSkipLeft > 0)) return
        showSkipButton(range)
    }

    private fun stopAutoSkip() {
        handler.removeCallbacks(autoSkipTick)
        autoSkipLeft = 0
    }

    private val skipVisible: Boolean
        get() = skipContainer.visibility == View.VISIBLE

    private val skipButtonFocused: Boolean
        get() = skipVisible && skipButton.hasFocus()

    /**
     * The timing service is keyed by episode, so data is pulled per playlist item. The episode
     * number is the TorrServer file index carried in the stream url — the same value the web
     * player sends, so both agree on what "episode 3" means.
     */
    private fun currentEpisodeNumber(): Int? =
        player?.currentMediaItem?.localConfiguration?.uri?.getQueryParameter("index")?.toIntOrNull()

    private fun fetchSkipDataForCurrentItem() {
        val p = player ?: return
        if (p.currentMediaItemIndex == skipFetchedForIndex) return

        val api = intent.getStringExtra(Extras.SKIP_API)?.takeIf { it.isNotBlank() } ?: return
        val tmdbId = intent.getStringExtra(Extras.TMDB_ID)?.takeIf { it.isNotBlank() } ?: return
        val episode = currentEpisodeNumber() ?: return
        val season = intent.getIntExtra(Extras.SEASON, 0)

        skipFetchedForIndex = p.currentMediaItemIndex
        skipRanges = emptyList()
        skipFetchJob?.cancel()
        skipFetchJob = lifecycleScope.launch {
            val ranges = withContext(Dispatchers.IO) {
                loadSkipRanges(api, tmdbId, season, episode)
            }
            skipRanges = ranges
            debugSkip(ranges, episode)
        }
    }

    private fun debugSkip(ranges: List<SkipRange>, episode: Int) {
        if (ranges.isEmpty()) Log.d(TAG, "No skip ranges for episode $episode")
        else Log.d(TAG, "Skip ranges for episode $episode: $ranges")
    }

    private fun loadSkipRanges(
        api: String,
        tmdbId: String,
        season: Int,
        episode: Int
    ): List<SkipRange> {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$api?tmdb_id=$tmdbId&season=$season&episode=$episode")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                ServerCookies.forUrl(url.toString())?.let { setRequestProperty("Cookie", it) }
            }
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optBoolean("error", false)) return emptyList()
            parseRanges(json.optJSONArray(TYPE_INTRO), TYPE_INTRO) +
                    parseRanges(json.optJSONArray(TYPE_CREDITS), TYPE_CREDITS)
        } catch (e: Exception) {
            // A missing/unreachable timing service must never disturb playback.
            Log.w(TAG, "Skip data unavailable: ${e.message}")
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseRanges(array: JSONArray?, type: String): List<SkipRange> {
        if (array == null) return emptyList()
        val ranges = mutableListOf<SkipRange>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = item.optLong("start_ms", 0L)
            // Credits often come back open-ended; 0 here means "until the end of the file".
            val end = item.optLong("end_ms", 0L)
            if (end > start || type == TYPE_CREDITS) ranges += SkipRange(type, start, end)
        }
        return ranges
    }

    private fun checkSkip(positionMs: Long) {
        if (skipRanges.isEmpty()) {
            if (skipVisible) hideSkipButton()
            return
        }
        val duration = player?.duration ?: 0L
        val range = skipRanges.firstOrNull { r ->
            val end = if (r.endMs > r.startMs) r.endMs else duration
            end > r.startMs && positionMs >= r.startMs && positionMs <= end
        }

        if (range == null) {
            if (skipVisible) hideSkipButton()
            return
        }
        if (range.key == activeSkipRange?.key) return   // already on screen
        if (range.key in offeredSkipKeys) return        // offered once, user let it pass
        showSkipButton(range)
    }

    private fun showSkipButton(range: SkipRange) {
        stopAutoSkip()
        activeSkipRange = range
        offeredSkipKeys += range.key
        skipContainer.visibility = View.VISIBLE
        positionSkipButton()
        // The offer is short-lived, so it takes the remote: one OK press and it's done.
        skipButton.requestFocus()
        handler.removeCallbacks(hideSkip)
        skipProgress.animate().cancel()
        skipProgress.scaleX = 1f

        if (shouldAutoSkip(range)) {
            // Своё время жизни у кнопки здесь — отсчёт; на паузе он стоит, так что
            // десятисекундного автоскрытия нет. Полоска убывает по секундам отсчёта.
            autoSkipLeft = AUTO_SKIP_SEC
            skipButton.text = autoSkipLabel(range)
            animateSkipProgress((AUTO_SKIP_SEC - 1).toFloat() / AUTO_SKIP_SEC, 1_000L)
            handler.postDelayed(autoSkipTick, 1_000L)
            return
        }

        skipButton.setText(
            if (range.type == TYPE_INTRO) R.string.player_skip_intro
            else R.string.player_skip_credits
        )
        // Bar drains left-to-right over exactly the window the offer stays up.
        animateSkipProgress(0f, SKIP_BUTTON_TIMEOUT_MS)
        handler.postDelayed(hideSkip, SKIP_BUTTON_TIMEOUT_MS)
    }

    private fun hideSkipButton() {
        handler.removeCallbacks(hideSkip)
        stopAutoSkip()
        skipProgress.animate().cancel()
        val hadFocus = skipButton.hasFocus()
        skipContainer.visibility = View.GONE
        activeSkipRange = null
        // Don't leave the remote pointing at nothing when the offer expires.
        if (hadFocus && controlsVisible) (lastFocusedButton ?: btnPlayPause).requestFocus()
    }

    /** Keeps the offer clear of the control bar whenever that bar is on screen. */
    private fun positionSkipButton() {
        skipContainer.translationY =
            if (controlsVisible) -controlsPanel.height.toFloat() else 0f
    }

    private fun executeSkip() {
        val range = activeSkipRange ?: return
        val p = player ?: return
        if (range.type == TYPE_INTRO) {
            p.seekTo(range.endMs)
        } else if (autoNext && p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        } else {
            endedByCompletion = true
            finish()
            return
        }
        hideSkipButton()
    }

    /**
     * Серия досмотрена, хоть плеер и остановился на ошибке (порча в самом конце файла): как
     * обычное окончание — следующая серия или выход «досмотрено».
     */
    private fun finishAsCompleted() {
        val p = player
        if (p != null && autoNext && p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.prepare()
        } else {
            endedByCompletion = true
            finish()
        }
    }

    // endregion

    // region timecode reporting

    private data class TimecodeSnapshot(
        val hash: String,
        val fileId: Int,
        val timeSec: Int,
        val durationSec: Int,
    )

    /**
     * Posts playback progress every 10 s, like the web player.
     *
     * The exit path already reports through MainActivity's result contract, but that only fires
     * on a clean exit: a killed process, a crash or a lost battery took the whole session's
     * progress with it. The web player has always saved periodically; this brings the built-in
     * one in line.
     */
    private fun startTimecodeReporting() {
        val endpoint = intent.getStringExtra(Extras.TIMECODE_API)?.takeIf { it.isNotBlank() }
        val clientId = intent.getStringExtra(Extras.CLIENT_ID)?.takeIf { it.isNotBlank() }
        if (endpoint == null || clientId == null) {
            // Loud on purpose: a silent return here is indistinguishable in the log from a
            // request that failed, and both look like "progress just isn't saved".
            Log.w(
                TAG,
                "Periodic timecode saving is OFF — the web app sent no " +
                        (if (endpoint == null) "timecode_api" else "client_id") +
                        ". Deploy the current player.js."
            )
            return
        }

        timecodeJob?.cancel()
        timecodeJob = lifecycleScope.launch {
            while (isActive) {
                delay(TIMECODE_SAVE_INTERVAL_MS)
                // Read player state on the main thread, then hand the plain data to IO.
                val snapshot = currentTimecodeSnapshot()
                if (snapshot == null) {
                    Log.d(TAG, "Timecode tick skipped (too early, near the end, or no hash in url)")
                    continue
                }
                withContext(Dispatchers.IO) { postTimecode(endpoint, clientId, snapshot) }
            }
        }
    }

    /**
     * Null whenever the current position isn't worth storing: too early to be a resume point, or
     * so close to the end that resuming would drop the viewer straight back into the credits.
     * Same two guards the web player applies.
     */
    private fun currentTimecodeSnapshot(): TimecodeSnapshot? {
        val p = player ?: return null
        val uri = p.currentMediaItem?.localConfiguration?.uri ?: return null
        val link = uri.getQueryParameter("link")?.takeIf { it.isNotBlank() } ?: return null
        val hash = infoHashFrom(link) ?: return null
        val fileId = uri.getQueryParameter("index")?.toIntOrNull() ?: return null

        val timeSec = (p.currentPosition.coerceAtLeast(0L) / 1000).toInt()
        val durationSec =
            if (p.duration > 0 && p.duration != C.TIME_UNSET) (p.duration / 1000).toInt() else 0

        if (timeSec < TIMECODE_MIN_SEC) return null
        if (durationSec > 0 && timeSec > durationSec - TIMECODE_END_GUARD_SEC) return null
        return TimecodeSnapshot(hash, fileId, timeSec, durationSec)
    }

    /**
     * TorrServer's `link` parameter takes either a bare info-hash or a whole magnet URI. Every
     * stream url the web app builds today carries a bare hash, but the API accepts a magnet, and
     * both must key the same stored timecode — so normalise to the info-hash either way.
     *
     * A bare hash is passed through untouched rather than lower-cased, so it keys byte-identically
     * to what the web player already stores for the same file.
     */
    private fun infoHashFrom(link: String): String? {
        if (link.matches(Regex("[a-fA-F0-9]{40}"))) return link
        Regex("xt=urn:btih:([a-fA-F0-9]{40})", RegexOption.IGNORE_CASE).find(link)
            ?.let { return it.groupValues[1].lowercase() }
        return Regex("[a-fA-F0-9]{40}").find(link)?.value?.lowercase()
    }

    private fun postTimecode(endpoint: String, clientId: String, snapshot: TimecodeSnapshot) {
        var connection: HttpURLConnection? = null
        try {
            val body = JSONObject().apply {
                put("clientId", clientId)
                put("hash", snapshot.hash)
                // Строкой, как шлёт веб: сервер проверяет тело схемой (timecodeSaveSchema,
                // fileId: z.string()) и на число отвечал 400 «expected string, received
                // number». Из-за этого ни одно периодическое сохранение отсюда не доходило.
                put("fileId", snapshot.fileId.toString())
                put("timecode", snapshot.timeSec)
                put("duration", snapshot.durationSec)
            }.toString()

            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                // Вход на сервер (cookie WebView): при включённом входе без неё 401
                ServerCookies.forUrl(endpoint)?.let { setRequestProperty("Cookie", it) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code in 200..299) {
                Log.d(TAG, "Timecode saved: ${snapshot.timeSec}s of ${snapshot.durationSec}s")
            } else {
                Log.w(TAG, "Timecode save returned HTTP $code")
            }
        } catch (e: Exception) {
            // Losing a periodic save is not worth disturbing playback over.
            Log.w(TAG, "Timecode save failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    // endregion

    // region seek read-out

    private val hideSeekOverlay = Runnable { seekOverlay.visibility = View.GONE }

    /**
     * Runs on every scrub keypress: works out the direction, how long that direction has been
     * held, grows the jump accordingly and shows where the scrub will land.
     */
    private fun onScrubbed(progress: Int, targetMs: Long, durationMs: Long) {
        val now = SystemClock.uptimeMillis()
        val direction = when {
            progress > lastSeekProgress -> 1
            progress < lastSeekProgress -> -1
            // Pinned at either end: the bar stops moving but the intent hasn't changed.
            else -> lastSeekDirection
        }
        // Changing your mind, or pausing, starts the acceleration over from the small step.
        if (direction != lastSeekDirection || now - lastSeekEventAt > SEEK_HOLD_RESET_MS) {
            seekHoldStartMs = now
        }
        lastSeekEventAt = now
        lastSeekDirection = direction
        lastSeekProgress = progress

        val stepSec = accelerationStepSec(now - seekHoldStartMs)
        // Takes effect on the next keypress — which is exactly the jump being advertised.
        seekBar.keyProgressIncrement =
            max(1, (stepSec * 1000L * SEEK_BAR_STEPS / durationMs).toInt())

        seekOverlayTime.text = formatTime(targetMs)
        seekOverlayDirection.text = if (direction > 0) "▶▶" else "◀◀"
        seekOverlayStep.text = getString(R.string.player_seek_step, stepSec)
        seekOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekOverlay)
        handler.postDelayed(hideSeekOverlay, SEEK_OVERLAY_HIDE_MS)
    }

    private fun accelerationStepSec(heldMs: Long): Int {
        var step = SEEK_ACCELERATION.first().second
        for ((threshold, value) in SEEK_ACCELERATION) {
            if (heldMs < threshold) break
            step = value
        }
        return step
    }

    // endregion

    // region progress

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)

        if (duration > 0 && duration != C.TIME_UNSET) {
            lastDuration = duration
            durationText.text = formatTime(duration)
            if (!scrubbing && !seekBar.isPressed) {
                seekBar.progress = (position * SEEK_BAR_STEPS / duration).toInt()
            }
            // Base step for a fresh press, sized so it means the same number of seconds on a
            // short episode and a long film. While a direction is being held, onScrubbed() owns
            // this value and grows it — hence the guard, or acceleration would be wiped every tick.
            if (!scrubbing) {
                val baseStepSec = SEEK_ACCELERATION.first().second
                seekBar.keyProgressIncrement =
                    max(1, (baseStepSec * 1000L * SEEK_BAR_STEPS / duration).toInt())
            }
        }
        lastPosition = position
        if (!scrubbing) positionText.text = formatTime(position)

        updateClock()
        updateStatusLine(position, duration)
        fetchSkipDataForCurrentItem()
        checkSkip(position)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // endregion

    // region dialogs

    /**
     * «Аудио и субтитры», как в веб-плеере: два пункта с текущей дорожкой под названием,
     * каждый открывает свой список, «Назад» из списка возвращает сюда.
     */
    private fun showTracksMenu() {
        if (player == null) return
        val audio = selectedTrackName(C.TRACK_TYPE_AUDIO) ?: "—"
        val subtitles = selectedTrackName(C.TRACK_TYPE_TEXT) ?: getString(R.string.player_subtitles_off)
        showSidePanel(
            titleRes = R.string.player_tracks,
            labels = listOf(
                withDescription(getString(R.string.player_track_audio), audio),
                withDescription(getString(R.string.player_subtitles), subtitles),
                withDescription(getString(R.string.player_online_subs), onlineSummary()),
            ),
            checkedIndex = -1,
            actions = listOf(
                { showTrackDialog(C.TRACK_TYPE_AUDIO, fromMenu = true) },
                { showTrackDialog(C.TRACK_TYPE_TEXT, fromMenu = true) },
                { showOnlineSubsPanel() },
            ),
            opener = btnTracks,
            itemLayout = R.layout.item_side_panel_desc,
        )
    }

    /** Название выбранной дорожки; null — не выбрана (субтитры выключены). */
    private fun selectedTrackName(trackType: @C.TrackType Int): String? {
        val p = player ?: return null
        if (trackType in p.trackSelectionParameters.disabledTrackTypes) return null
        var index = 0
        p.currentTracks.groups.filter { it.type == trackType && it.isSupported }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                if (group.isTrackSelected(i)) return describeTrack(group, i, index)
                index++
            }
        }
        return null
    }

    private fun showTrackDialog(trackType: @C.TrackType Int, fromMenu: Boolean = false) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == trackType && it.isSupported }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var checked = -1

        // Subtitles can be turned off entirely; an audio track cannot.
        if (trackType == C.TRACK_TYPE_TEXT) {
            val textDisabled = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes ||
                    groups.none { group -> (0 until group.length).any { group.isTrackSelected(it) } }
            labels += getString(R.string.player_subtitles_off)
            actions += {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            if (textDisabled) checked = 0
        }

        groups.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                if (group.isTrackSelected(i)) checked = labels.size
                labels += describeTrack(group, i, labels.size)
                val mediaGroup = group.mediaTrackGroup
                val ordinal = labels.size - 1
                actions += {
                    // Обычные и онлайн-субтитры взаимоисключающие
                    if (trackType == C.TRACK_TYPE_TEXT) onlineOff()
                    // Озвучка выбрана руками — запоминаем по признакам, а не по группе
                    // дорожек: у следующей серии группа другая (см. restoreAudioChoice)
                    if (trackType == C.TRACK_TYPE_AUDIO) {
                        rememberAudioChoice(group.getTrackFormat(i), ordinal)
                        saveAudioPrefToServer(audioIndexInFile(group, i))
                    }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .setOverrideForType(TrackSelectionOverride(mediaGroup, i))
                        .build()
                }
            }
        }

        if (labels.isEmpty()) {
            App.toast(R.string.player_no_tracks, false)
            return
        }

        val audio = trackType == C.TRACK_TYPE_AUDIO
        showSidePanel(
            titleRes = if (audio) R.string.player_audio_tracks else R.string.player_subtitles,
            labels = labels,
            checkedIndex = checked,
            actions = actions,
            opener = btnTracks,
            back = if (fromMenu) ({ showTracksMenu() }) else null,
        )
    }

    // region online subtitles

    /** Ключ файла «hash_index» и hash раздачи текущего элемента; null — не ссылка TorrServer. */
    private fun onlineFileKeyOf(p: Player): Pair<String, String>? {
        val uri = p.currentMediaItem?.localConfiguration?.uri ?: return null
        val hash = uri.getQueryParameter("link")?.let { infoHashFrom(it) }?.lowercase() ?: return null
        val index = uri.getQueryParameter("index") ?: return null
        return "${hash}_$index" to hash
    }

    private fun onlineTargetFor(p: Player): OnlineSubtitles.Target? {
        val all = intent.getStringArrayExtra(Extras.ONLINE_SUBS) ?: return null
        val json = if (all.size == p.mediaItemCount) all.getOrNull(p.currentMediaItemIndex) else all.singleOrNull()
        return OnlineSubtitles.Target.fromJson(json)
    }

    /**
     * Новый элемент очереди (или пересборка плеера). Тот же файл — всё остаётся; иначе
     * восстанавливаем выбор файла, а нет его — первые субтитры языка, выбранного в раздаче.
     */
    private fun onlineOnItem() {
        val p = player ?: return
        val (key, hash) = onlineFileKeyOf(p) ?: run { onlineReset(); return }
        if (key == onlineFileKey) {
            if (onlineActive != null) startOnlineTick()
            return
        }
        onlineReset()
        onlineFileKey = key
        onlineHash = hash
        onlineTarget = onlineTargetFor(p)
        Log.d(TAG, "Online subtitles for $key: $onlineTarget")
        onlineStore.fileChoice(key)?.let { (sub, offset) ->
            onlineOffsetMs = offset
            onlineLoad(sub, quiet = true)
            return
        }
        val lang = onlineStore.torrentLang(hash) ?: return
        onlineEnsureList { list ->
            if (onlineActive != null || onlineLoadingUrl != null || selectedTrackName(C.TRACK_TYPE_TEXT) != null) return@onlineEnsureList
            list.firstOrNull { it.lang == lang }?.let { onlineLoad(it, quiet = true) }
        }
    }

    private fun onlineReset() {
        onlineSearchJob?.cancel()
        onlineLoadJob?.cancel()
        stopOnlineTick()
        onlineFileKey = null
        onlineHash = null
        onlineTarget = null
        onlineList = null
        onlineAfterSearch = null
        onlineActive = null
        onlineCues = emptyList()
        onlineLoadingUrl = null
        onlineFailedUrl = null
        onlineOffsetMs = 0L
    }

    private fun onlineEnsureList(then: ((List<OnlineSubtitles.Sub>) -> Unit)? = null) {
        onlineList?.let { list -> then?.invoke(list); return }
        if (then != null) onlineAfterSearch = then
        if (onlineSearchJob?.isActive == true) return
        val target = onlineTarget ?: run {
            onlineList = emptyList()
            refreshOnlinePanel()
            onlineAfterSearch?.invoke(emptyList())
            onlineAfterSearch = null
            return
        }
        val key = onlineFileKey
        onlineSearchJob = lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    OnlineSubtitles.search(target)
                } catch (e: Exception) {
                    Log.w(TAG, "Online subtitles search failed: ${e.message}")
                    emptyList()
                }
            }
            if (key != onlineFileKey) return@launch
            onlineList = list
            refreshOnlinePanel()
            onlineAfterSearch?.invoke(list)
            onlineAfterSearch = null
        }
    }

    /** Скачать и включить. quiet — восстановление выбора: панель и тосты не трогаем. */
    private fun onlineLoad(sub: OnlineSubtitles.Sub, quiet: Boolean) {
        val key = onlineFileKey ?: return
        onlineLoadingUrl = sub.url
        onlineFailedUrl = null
        refreshOnlinePanel()
        onlineLoadJob?.cancel()
        onlineLoadJob = lifecycleScope.launch {
            val cues = withContext(Dispatchers.IO) {
                try {
                    OnlineSubtitles.download(sub)
                } catch (e: Exception) {
                    Log.w(TAG, "Online subtitles ${sub.url}: ${e.message}")
                    null
                }
            }
            if (key != onlineFileKey || onlineLoadingUrl != sub.url) return@launch
            onlineLoadingUrl = null
            if (cues.isNullOrEmpty()) {
                onlineFailedUrl = sub.url
                refreshOnlinePanel()
                if (!quiet) App.toast(R.string.player_online_subs_failed, false)
                return@launch
            }
            disableRegularSubtitles()
            onlineActive = sub
            onlineCues = cues
            onlineLastText = null
            onlineRemember()
            startOnlineTick()
            if (!quiet && onlinePanelShown) hideSidePanel() else refreshOnlinePanel()
        }
    }

    /** Онлайн включились — встроенные выключаем (у libVLC это setSpuTrack(-1)). */
    private fun disableRegularSubtitles() {
        val p = player ?: return
        if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** Выключить: и для файла, и язык раздачи — следующие серии тоже без них. */
    private fun onlineOff() {
        if (onlineActive == null && onlineLoadingUrl == null) return
        onlineLoadJob?.cancel()
        onlineActive = null
        onlineCues = emptyList()
        onlineLoadingUrl = null
        stopOnlineTick()
        onlineRemember()
    }

    private fun onlineRemember() {
        val key = onlineFileKey ?: return
        val hash = onlineHash ?: return
        onlineStore.save(key, hash, onlineActive, onlineOffsetMs)
    }

    private fun onlineShift(deltaMs: Long) {
        onlineOffsetMs += deltaMs
        onlineLastText = null
        onlineRemember()
        showOnlineText()
    }

    private val onlineTick = object : Runnable {
        override fun run() {
            showOnlineText()
            handler.postDelayed(this, ONLINE_SUBS_TICK_MS)
        }
    }

    private fun startOnlineTick() {
        handler.removeCallbacks(onlineTick)
        handler.post(onlineTick)
    }

    private fun stopOnlineTick() {
        handler.removeCallbacks(onlineTick)
        onlineLastText = null
        onlineSubsView.setCues(emptyList())
    }

    private fun showOnlineText() {
        val p = player
        val text = if (p == null || onlineActive == null) ""
        else OnlineSubtitles.textAt(onlineCues, p.currentPosition - onlineOffsetMs)
        if (text == onlineLastText) return
        onlineLastText = text
        onlineSubsView.setCues(if (text.isEmpty()) emptyList() else listOf(Cue.Builder().setText(text).build()))
    }

    private fun onlineOffsetLabel(): String {
        val sec = String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(onlineOffsetMs) / 1000.0)
        val sign = if (onlineOffsetMs > 0) "+" else if (onlineOffsetMs < 0) "−" else ""
        return getString(R.string.player_online_subs_sec, sign + sec)
    }

    private fun onlineSubTitle(sub: OnlineSubtitles.Sub): String =
        languageName(sub.lang) + (if (sub.name.isNotBlank()) " · ${sub.name}" else "")

    /** Подпись в меню «Аудио и субтитры». */
    private fun onlineSummary(): String {
        val sub = onlineActive ?: return getString(
            if (onlineLoadingUrl != null) R.string.player_online_subs_loading else R.string.player_online_subs_disabled
        )
        return onlineSubTitle(sub) + (if (onlineOffsetMs != 0L) " · ${onlineOffsetLabel()}" else "")
    }

    private val onlinePanelShown: Boolean
        get() = sidePanelOpen && onlinePanelActions != null && sidePanelActions === onlinePanelActions

    /** Список пришёл или файл загрузился — перерисовать открытую панель на той же строке. */
    private fun refreshOnlinePanel() {
        if (onlinePanelShown) showOnlineSubsPanel(focusIndex = sidePanelList.selectedItemPosition)
    }

    /**
     * Панель «Онлайн субтитры»: выключить, сдвиг раньше/позже (пока включены), найденные
     * субтитры. Поиск — при первом открытии для файла.
     */
    private fun showOnlineSubsPanel(focusIndex: Int? = null) {
        if (player == null) return
        if (onlineFileKey == null) onlineOnItem()
        val labels = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()
        val stay = { showOnlineSubsPanel(focusIndex = sidePanelList.selectedItemPosition) }
        var checked = -1

        labels += getString(R.string.player_online_subs_off)
        actions += { onlineOff() }
        if (onlineActive == null && onlineLoadingUrl == null) checked = 0
        if (onlineActive != null) {
            labels += withDescription(
                getString(R.string.player_online_subs_earlier),
                getString(R.string.player_online_subs_offset, onlineOffsetLabel())
            )
            actions += { onlineShift(-ONLINE_SUBS_STEP_MS); stay() }
            labels += withDescription(
                getString(R.string.player_online_subs_later), getString(R.string.player_online_subs_step)
            )
            actions += { onlineShift(ONLINE_SUBS_STEP_MS); stay() }
        }

        val list = onlineList
        val info = when {
            onlineTarget == null && list != null -> R.string.player_online_subs_unknown
            list == null -> R.string.player_online_subs_searching
            list.isEmpty() -> R.string.player_online_subs_none
            else -> 0
        }
        if (info != 0) {
            labels += getString(info)
            actions += stay
        }
        list?.forEach { sub ->
            val details = mutableListOf(sub.lang.uppercase(Locale.ROOT), sub.source)
            if (sub.hi) details += getString(R.string.player_online_subs_hi)
            when (sub.url) {
                onlineLoadingUrl -> details += getString(R.string.player_online_subs_loading)
                onlineFailedUrl -> details += getString(R.string.player_online_subs_failed)
            }
            if (sub.url == onlineActive?.url) checked = labels.size
            labels += withDescription(onlineSubTitle(sub), details.joinToString(" · "))
            actions += {
                if (sub.url != onlineActive?.url) {
                    onlineOffsetMs = 0L
                    onlineLoad(sub, quiet = false)
                    stay()
                }
            }
        }

        onlinePanelActions = actions
        showSidePanel(
            titleRes = R.string.player_online_subs,
            labels = labels,
            checkedIndex = checked,
            actions = actions,
            opener = btnTracks,
            itemLayout = R.layout.item_side_panel_desc,
            back = { showTracksMenu() },
            focusIndex = focusIndex?.takeIf { it in labels.indices } ?: checked,
        )
        if (list == null) onlineEnsureList()
    }

    // endregion

    private fun describeTrack(group: Tracks.Group, index: Int, fallbackIndex: Int): String {
        val format = group.getTrackFormat(index)
        val parts = mutableListOf<String>()
        format.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { parts += languageName(it) }
        if (format.channelCount > 0) {
            parts += when (format.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> "${format.channelCount}ch"
            }
        }
        format.codecs?.takeIf { it.isNotBlank() }?.let { parts += it }
        format.sampleMimeType?.takeIf { parts.isEmpty() }?.let { parts += it }
        return parts.joinToString(" · ").ifBlank { "#${fallbackIndex + 1}" }
    }

    // region audio choice on the server

    /**
     * Озвучка, сохранённая на сервере для файла (как у веб-плеера: /api/audio/pref/save и
     * /api/media/prefs/get). Плеер раньше её не отправлял и не читал: выбор жил только до
     * выхода из плеера, а запуск заново или другой серии начинал с первой дорожки.
     */
    private class FileRef(val hash: String, val fileId: Int) {
        val key get() = "${hash}_$fileId"
    }

    /** Файл раздачи текущего элемента очереди (ссылка TorrServer /stream?link=…&index=…). */
    private fun currentFileRef(): FileRef? {
        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: return null
        val link = uri.getQueryParameter("link")?.takeIf { it.isNotBlank() } ?: return null
        val hash = infoHashFrom(link) ?: return null
        val fileId = uri.getQueryParameter("index")?.toIntOrNull() ?: return null
        return FileRef(hash, fileId)
    }

    /** Адрес API дорожек: сервер тот же, что у таймкода (…/api/timecode/save → …/api/<path>). */
    private fun prefsEndpoint(path: String): String? =
        intent.getStringExtra(Extras.TIMECODE_API)?.takeIf { it.contains("/timecode/save") }
            ?.replace("/timecode/save", path)

    /** Номер звуковой дорожки среди всех звуковых дорожек файла — как у ffprobe и веб-плеера. */
    private fun audioIndexInFile(group: Tracks.Group, trackIndex: Int): Int {
        var n = 0
        for (g in player?.currentTracks?.groups ?: return -1) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            if (g.mediaTrackGroup == group.mediaTrackGroup) return n + trackIndex
            n += g.length
        }
        return -1
    }

    /** Дорожка серии с сервера: ключ файла → номер; ждёт, пока плеер разберёт файл. */
    private var serverAudio: Pair<String, Int>? = null

    /** Файл, для которого сохранённую дорожку уже применили (или выбрали руками). */
    private var serverAudioDoneKey: String? = null

    private fun saveAudioPrefToServer(audioIndex: Int) {
        val ref = currentFileRef() ?: return
        serverAudio = null
        serverAudioDoneKey = ref.key
        val endpoint = prefsEndpoint("/audio/pref/save") ?: return
        val clientId = intent.getStringExtra(Extras.CLIENT_ID)?.takeIf { it.isNotBlank() } ?: return
        if (audioIndex < 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val body = JSONObject().apply {
                    put("hash", ref.hash)
                    put("fileId", ref.fileId.toString())
                    put("audioTrack", audioIndex)
                    put("clientId", clientId)
                }.toString()
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    ServerCookies.forUrl(endpoint)?.let { setRequestProperty("Cookie", it) }
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                Log.d(TAG, "Audio pref saved: track $audioIndex of ${ref.key} (HTTP ${connection.responseCode})")
            } catch (e: Exception) {
                Log.w(TAG, "Audio pref save failed: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
    }

    /** Новая серия: спросить у сервера, какую озвучку выбирали для этого файла (или раздачи). */
    private fun loadServerAudioPref() {
        if (audioChoice != null) return   // выбрано руками в этом запуске — действует оно
        val ref = currentFileRef() ?: return
        if (ref.key == serverAudioDoneKey) return
        val endpoint = prefsEndpoint("/media/prefs/get") ?: return
        val clientId = intent.getStringExtra(Extras.CLIENT_ID)?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            val index = withContext(Dispatchers.IO) { fetchAudioPref(endpoint, clientId, ref) }
            if (index == null || audioChoice != null) return@launch
            // Пока ждали, серия могла смениться
            if (currentFileRef()?.key != ref.key) return@launch
            serverAudio = ref.key to index
            applyServerAudio(player?.currentTracks)
        }
    }

    private fun fetchAudioPref(endpoint: String, clientId: String, ref: FileRef): Int? {
        val query = "?hash=${ref.hash}&fileId=${ref.fileId}&clientId=${java.net.URLEncoder.encode(clientId, "UTF-8")}"
        // Старый сервер без /media/prefs/get отвечает 404 — тогда прежний маршрут только аудио
        for (url in listOf(endpoint + query, endpoint.replace("/media/prefs/get", "/audio/pref/get") + query)) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    ServerCookies.forUrl(url)?.let { setRequestProperty("Cookie", it) }
                }
                if (connection.responseCode !in 200..299) continue
                val json = JSONObject(connection.inputStream.use { it.bufferedReader().readText() })
                if (!json.has("audioTrack") || json.isNull("audioTrack")) return null
                return json.optInt("audioTrack", -1).takeIf { it >= 0 }
            } catch (e: Exception) {
                Log.w(TAG, "Audio pref fetch failed: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    /** Включить дорожку с сервера, как только плеер отдал список звуковых дорожек файла. */
    private fun applyServerAudio(tracks: Tracks?) {
        val (key, index) = serverAudio ?: return
        val p = player ?: return
        if (tracks == null || currentFileRef()?.key != key || key == serverAudioDoneKey) return

        var n = 0
        var anyAudio = false
        var target: Pair<Tracks.Group, Int>? = null
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            anyAudio = true
            if (index < n + group.length) {
                target = group to (index - n)
                break
            }
            n += group.length
        }
        if (!anyAudio) return   // список ещё не пришёл — onTracksChanged вернётся сюда
        serverAudio = null
        serverAudioDoneKey = key
        val (group, i) = target ?: return
        if (!group.isTrackSupported(i) || group.isTrackSelected(i)) return
        Log.i(TAG, "Audio pref from server: track $index of $key")
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
            .build()
    }

    // endregion

    private fun rememberAudioChoice(format: Format, ordinal: Int) {
        audioChoice = AudioChoice(
            language = format.language?.takeIf { it.isNotBlank() && it != "und" },
            label = format.label?.takeIf { it.isNotBlank() },
            channelCount = format.channelCount,
            mimeType = format.sampleMimeType,
            ordinal = ordinal,
        )
        audioRestoreKey = null
    }

    /**
     * Новая серия (или пересборка плеера): если выбранной руками озвучки в списке нет под
     * выбором — включить самую похожую. Язык другой — не подходит вовсе (лучше дорожка по
     * умолчанию, чем чужой язык); название и язык важнее каналов, кодека и номера по порядку.
     */
    private fun restoreAudioChoice(tracks: Tracks) {
        val choice = audioChoice ?: return
        val p = player ?: return
        if (C.TRACK_TYPE_AUDIO in p.trackSelectionParameters.disabledTrackTypes) return

        var ordinal = 0
        var bestScore = -1
        var bestGroup: Tracks.Group? = null
        var bestIndex = -1
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSupported) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                val position = ordinal++
                if (choice.language != null && language != null && !language.equals(choice.language, true)) continue
                var score = 0
                if (choice.language != null && language != null) score += 8
                if (choice.label != null && choice.label == format.label) score += 16
                if (choice.mimeType != null && choice.mimeType == format.sampleMimeType) score += 2
                if (choice.channelCount > 0 && choice.channelCount == format.channelCount) score += 2
                if (choice.ordinal == position) score += 1
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                    bestIndex = i
                }
            }
        }
        val group = bestGroup ?: return
        if (bestScore <= 0 || group.isTrackSelected(bestIndex)) return
        val key = "${p.currentMediaItemIndex}:${group.mediaTrackGroup.id}:$bestIndex"
        if (key == audioRestoreKey) return
        audioRestoreKey = key
        Log.i(TAG, "Restoring audio choice $choice -> ${describeTrack(group, bestIndex, bestIndex)}")
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, bestIndex))
            .build()
    }

    /** Turns a track's language tag ("rus", "en") into something readable, or leaves it as is. */
    private fun languageName(code: String): String {
        val display = try {
            Locale.forLanguageTag(code.replace('_', '-')).getDisplayLanguage(Locale.getDefault())
        } catch (_: Exception) {
            ""
        }
        return display.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            ?: code
    }

    private fun showEpisodesDialog() {
        val p = player ?: return
        if (p.mediaItemCount <= 1) {
            App.toast(R.string.player_no_tracks, false)
            return
        }
        // Plain "Episode N" rather than the raw TorrServer file name: release names are long,
        // noisy and near-identical between episodes, which made the list hard to scan.
        // When the web UI knows the episode from TMDB it sends "Серия N - Название" instead.
        val titles = (0 until p.mediaItemCount).map { index -> episodeLabel(index) }
        showSidePanel(
            titleRes = R.string.player_episodes,
            labels = titles,
            checkedIndex = p.currentMediaItemIndex,
            actions = (0 until p.mediaItemCount).map { index ->
                { p.seekTo(index, 0L); p.play() }
            },
            opener = btnEpisodes
        )
    }

    // endregion

    // region TorrServer stats

    /**
     * Строка под заголовком — та же, что в веб-плеере (public/js/torrserverstats.js и
     * player.js): «TORRSERVER 1.2 GB | СКОРОСТЬ 24.6 Mb/s | ПИРЫ 12 / 48 | СИДЫ 7».
     *
     * Данные — POST <TorrServer>/cache {action: get, hash}. Адрес TorrServer и хэш берутся из
     * ссылки текущего элемента (<ts>/stream?link=<hash>&index=<n>), поэтому на следующей серии
     * строка переключается сама. Опрос раз в секунду и только пока HUD виден: скрытую строку
     * никто не читает, а TorrServer на слабом роутере лишние запросы замечает.
     */
    private fun startTorrStats() {
        if (statsJob?.isActive == true) return
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val target = currentTorrTarget()
                if (target == null) {
                    // Не поток TorrServer (IPTV, прямая ссылка) — строке нечего показать
                    statsView.visibility = View.GONE
                    break
                }
                val stats = withContext(Dispatchers.IO) { loadTorrStats(target.first, target.second) }
                if (stats != null) {
                    statsView.text = hudLine(stats)
                    statsView.visibility = View.VISIBLE
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun stopTorrStats() {
        statsJob?.cancel()
        statsJob = null
    }

    /** (адрес TorrServer, хэш) текущего элемента; null — это не поток TorrServer. */
    /** Адрес потока текущего элемента очереди (http/https) или null. */
    private fun currentStreamUrl(): String? =
        (player?.currentMediaItem?.localConfiguration?.uri ?: intent.data)?.toString()
            ?.takeIf { it.startsWith("http") }

    private fun currentTorrTarget(): Pair<String, String>? {
        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: intent.data ?: return null
        val path = uri.path ?: return null
        val authority = "${uri.scheme}://${uri.encodedAuthority}"

        // <ts>/stream?link=<hash>&index=<n> — так ссылки строит веб-клиент
        val streamAt = path.lastIndexOf("/stream")
        val link = uri.getQueryParameter("link")
        if (streamAt >= 0 && link != null && HASH_RE.matches(link)) {
            return authority + path.substring(0, streamAt) to link.lowercase(Locale.US)
        }

        // <ts>/play/<hash>/<n> — прямые ссылки на файл
        val segments = uri.pathSegments
        val playAt = segments.indexOf("play")
        if (playAt >= 0 && playAt + 1 < segments.size && HASH_RE.matches(segments[playAt + 1])) {
            val prefix = segments.subList(0, playAt).joinToString("") { "/$it" }
            return authority + prefix to segments[playAt + 1].lowercase(Locale.US)
        }
        return null
    }

    // region диагностика

    /**
     * «AV1 3840×2160», если это видео больше 1080p в кодеке, который без аппаратного
     * декодера не идёт ни в чём, а такого декодера на устройстве нет; иначе null.
     *
     * Программно (dav1d — он же внутри VLC) AV1 4K на 4×Cortex-A55 декодируется со скоростью
     * 8,5 кадра/с, на NVIDIA SHIELD — порядка 13–15, а фильму нужно 24. Уход в libVLC давал
     * слайд-шоу; честнее сразу сказать, что устройство этот формат не тянет. Всё остальное
     * (до 1080p, MPEG-4 в AVI, WMV…) libVLC играет — туда по-прежнему откатываемся.
     */
    private fun hardwareOnlyFormat(format: Format?): String? {
        format ?: return null
        val mime = format.sampleMimeType ?: return null
        val name = HARDWARE_ONLY_CODECS[mime] ?: return null
        if (format.width <= 1920 && format.height <= 1088) return null
        val hardware = try {
            MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.hardwareAccelerated }
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            true
        }
        return if (hardware) null else "$name ${format.width}×${format.height}"
    }

    private fun showFormatUnsupported(format: String, mime: String?) {
        Log.w(TAG, "No hardware decoder for $format, not falling back to libVLC")
        reportDiagnostics(PlaybackDiagnostics.KIND_FORMAT_UNSUPPORTED, "no hardware decoder: $format", null, mime)
        App.toast(getString(R.string.player_format_unsupported, format), true)
        finish()
    }

    /**
     * Отчёт о сбое на сервер ([PlaybackDiagnostics]). Зовётся до того, как откат поменяет
     * состояние: в отчёт попадает то, на чём сломалось, а не то, на что переключаемся.
     */
    private fun reportDiagnostics(
        kind: String,
        reason: String,
        error: PlaybackException?,
        failedMime: String? = null,
        problem: String? = null,
        probeStream: Boolean = false,
        onResult: ((PlaybackDiagnostics.Result) -> Unit)? = null,
    ) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        fallbackSteps.put(
            JSONObject()
                .put("kind", kind)
                .put("reason", reason.take(300))
                .put("code", error?.errorCodeName)
                .put("atMs", elapsed),
        )
        try {
            val target = currentTorrTarget()
            PlaybackDiagnostics.report(
                this,
                intent.getStringExtra(Extras.DIAGNOSTICS_API)?.takeIf { it.isNotBlank() },
                intent.getStringExtra(Extras.CLIENT_ID)?.takeIf { it.isNotBlank() },
                PlaybackDiagnostics.Snapshot(
                    kind = kind,
                    reason = reason,
                    error = error,
                    failedMime = failedMime,
                    media = diagnosticsMedia(target?.second, elapsed),
                    player = diagnosticsPlayer(),
                    torrServer = target?.first,
                    torrHeaders = torrHeaders,
                    playback = if (usingVlc) null else playbackInfo.toJson(this),
                    problem = problem,
                    probeUrl = if (probeStream) currentStreamUrl() else null,
                ),
                onResult,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Diagnostics snapshot failed: $e")
            onResult?.invoke(PlaybackDiagnostics.Result.QUEUED)
        }
    }

    private fun diagnosticsMedia(hash: String?, elapsedMs: Long) = JSONObject().apply {
        val p = player
        val uri = p?.currentMediaItem?.localConfiguration?.uri ?: intent.data
        intent.getStringExtra(Extras.TITLE)?.let { put("title", it.take(200)) }
        hash?.let { put("hash", it) }
        uri?.let { u ->
            (u.getQueryParameter("index") ?: u.pathSegments.let { seg ->
                val at = seg.indexOf("play")
                seg.getOrNull(at + 2).takeIf { at >= 0 }
            })?.let { put("fileIndex", it) }
            put("scheme", u.scheme)
            put("hls", u.path?.endsWith(".m3u8") == true)
            // Имя в ссылке есть не всегда (у TorrServer — /stream?link=…); тогда его спросит
            // PlaybackDiagnostics у самого TorrServer
            u.lastPathSegment?.takeIf { it.contains('.') }?.let {
                put("fileName", it.take(300))
                put("container", it.substringAfterLast('.').lowercase(Locale.US))
            }
        }
        put("elapsedMs", elapsedMs)
        if (p != null) {
            put("positionMs", p.currentPosition)
            if (p.duration != C.TIME_UNSET) put("durationMs", p.duration)
            put("bufferedMs", p.bufferedPosition)
            put("state", p.playbackState)
            put("playlistIndex", p.currentMediaItemIndex)
            put("playlistSize", p.mediaItemCount)
            val size = p.videoSize
            if (size.width > 0) put("videoSize", "${size.width}x${size.height}")
            put("tracks", PlaybackDiagnostics.tracksJson(p.currentTracks))
        }
    }

    private fun diagnosticsPlayer() = JSONObject().apply {
        put("engine", if (playerEngine == Prefs.ENGINE_VLC) "vlc" else "auto")
        put("current", if (usingVlc) "vlc" else "exo")
        put("decoderMode", when (playerDecoderMode) {
            Prefs.DECODER_HARDWARE -> "hardware"
            Prefs.DECODER_SOFTWARE -> "software"
            else -> "combined"
        })
        put("audioMode", audioModeName(playerAudioMode))
        put("audioDecoder", if (playerAudioDecoder == Prefs.AUDIO_DECODER_FFMPEG) "ffmpeg" else "auto")
        put("effectiveAudioMode", audioModeName(audioMode))
        put("audioLimit", audioLimit.name)
        put("usbAudio", usbAudio)
        put("softwareVideoFallback", softwareVideoFallback)
        put("pcmAudioFallback", pcmAudioFallback)
        put("safeAudioFallback", safeAudioFallback)
        put("bufferMb", playerBufferMb)
        // Копия: сам список дописывается дальше, а отчёт уходит в другой поток
        put("steps", JSONArray(fallbackSteps.toString()))
    }

    private fun audioModeName(mode: Int) = when (mode) {
        Prefs.AUDIO_PCM -> "pcm"
        Prefs.AUDIO_PASSTHROUGH -> "passthrough"
        Prefs.AUDIO_AUTO -> "auto"
        else -> "mode$mode"
    }

    // endregion

    private fun loadTorrStats(base: String, hash: String): List<HudStat>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/cache").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4_000
                readTimeout = 4_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                // Те же заголовки, что у видеопотока: среди них Basic-авторизация TorrServer
                torrHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val request = JSONObject().put("action", "get").put("hash", hash).toString()
            connection.outputStream.use { it.write(request.toByteArray()) }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            // Данные бывают и в Torrent, и прямо в корне — как разбирает веб
            formatTorrStats(root.optJSONObject("Torrent") ?: root)
        } catch (e: Exception) {
            // Недоступная статистика не должна ничем мешать просмотру
            Log.d(TAG, "TorrServer stats unavailable: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private val torrHeaders: Map<String, String> by lazy {
        intent.getStringArrayExtra(Extras.HEADERS).toHeaderMap()
    }

    private fun formatTorrStats(t: JSONObject): List<HudStat> {
        val preloaded = t.optDouble("preloaded_bytes", 0.0)
        val speed = t.optDouble("download_speed", 0.0)
        val active = t.optInt("active_peers", 0)
        val total = t.optInt("total_peers", 0)
        val seeders = t.optInt("connected_seeders", 0)
        val stats = mutableListOf(
            HudStat("TorrServer", formatSize(preloaded)),
            HudStat(getString(R.string.player_hud_speed), formatSpeed(speed)),
        )
        if (active > 0) {
            stats += HudStat(getString(R.string.player_hud_peers), "$active / $total")
            stats += HudStat(getString(R.string.player_hud_seeds), seeders.toString())
        }
        return stats
    }

    /** Как formatSize в вебе: двоичные единицы, один знак после точки (у GB — два). */
    private fun formatSize(bytes: Double): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> String.format(Locale.US, "%.0f B", bytes)
        bytes < 1024.0 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024)
        bytes < 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** Как formatSpeed в вебе: байты/с → мегабиты/с, меньше мегабита — килобиты. */
    private fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "0 Mb/s"
        val megabits = bytesPerSecond * 8 / 1_000_000
        return if (megabits < 1) String.format(Locale.US, "%.1f Kb/s", bytesPerSecond * 8 / 1000)
        else String.format(Locale.US, "%.1f Mb/s", megabits)
    }

    // endregion

    // region player

    private fun initPlayer() {
        usbAudio = AudioOutput.isUsbOutput(this)
        val headers = intent.getStringArrayExtra(Extras.HEADERS).toHeaderMap()
        val newPlayer = if (usingVlc) buildVlcPlayer(headers) else buildExoPlayer(headers)

        newPlayer.addListener(playerListener)
        playerView.player = newPlayer
        player = newPlayer
        (playerView.videoSurfaceView?.parent as? ViewGroup)?.let { assSubtitles?.addTo(it) }
        applyVideoScale()
        applySubtitleSize()

        val items = buildMediaItems()
        if (items.isEmpty()) {
            Log.e(TAG, "No playable url supplied")
            App.toast(R.string.invalid_url, true)
            finish()
            return
        }

        // A rebuild carries its own resume point; a fresh start takes it from the intent.
        val startIndex = (resumeIndex ?: intent.getIntExtra(Extras.PLAYLIST_INDEX, 0))
            .coerceIn(0, items.size - 1)
        val startPosition = (resumePositionMs ?: intent.getLongExtra(Extras.POSITION, 0L))
            .coerceAtLeast(0L)
        resumeIndex = null
        resumePositionMs = null
        // Выбранные озвучка и субтитры: ExoPlayer узнаёт те же группы дорожек при повторном
        // разборе файла, у libVLC группы новые — тогда просто дорожки по умолчанию
        resumeTrackParams?.let { newPlayer.trackSelectionParameters = it }
        resumeTrackParams = null
        audioRestoreKey = null

        newPlayer.setMediaItems(items, startIndex, startPosition)
        newPlayer.playWhenReady = true
        newPlayer.prepare()
        OnlineSubtitles.proxyBase = intent.getStringExtra(Extras.ONLINE_SUBS_PROXY)
        onlineOnItem()
        loadServerAudioPref()

        updateTitle()
        updateEpisodeButtons()
        updatePlayPauseIcon()
        applyClockVisibility()
        handler.post(progressTick)
        startTimecodeReporting()
        showControls()
    }

    private fun buildVlcPlayer(headers: Map<String, String>): Player = VlcPlayer(
        context = this,
        headers = headers,
        subtitleUrls = intent.getStringArrayExtra(Extras.SUBS)?.toList().orEmpty(),
        subtitleIndex = intent.getIntExtra(Extras.PLAYLIST_INDEX, 0),
        audioMode = audioMode,
        subtitleScale = Prefs.PLAYER_SUBTITLE_SCALES[playerSubtitleSize],
        autoAdvance = autoNext,
    )

    private fun buildExoPlayer(headers: Map<String, String>): Player {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            // TorrServer отвечает, когда кусок скачан: 8 с по умолчанию на медленной раздаче
            // мало, а каждый обрыв — лишнее переподключение. Повторы — без счёта
            // (WaitForStreamPolicy)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }

        val ass = AssSubtitles(this)
        return ExoPlayer.Builder(
            this,
            ass.renderersFactory(buildRenderersFactory())
        )
            .setTrackSelector(DefaultTrackSelector(this))
            .setMediaSourceFactory(
                // Сбой открытия — из первого чтения, чтобы повтор шёл с нужного места
                // (DeferredOpenDataSource)
                ass.mediaSourceFactory(DeferredOpenDataSource.Factory(DefaultDataSource.Factory(this, httpFactory)))
            )
            // How much of the stream is held in memory. LoadControl is fixed at build time, so
            // changing this setting rebuilds the player (see applyBufferSize).
            // Сворачивание и выключение экрана: декодеры Amlogic и Tegra отпускают картинку
            // дольше 2 с по умолчанию, и Media3 останавливала плеер ошибкой (см. onPlayerError)
            .setDetachSurfaceTimeoutMs(DETACH_SURFACE_TIMEOUT_MS)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setTargetBufferBytes(playerBufferMb * 1024 * 1024)
                    .build()
            )
            .build()
            .also { exo ->
                // Без автопереключения серий ExoPlayer встаёт в конце серии, а не уходит на
                // следующую: плеер закроется на ней же (onPlayWhenReadyChanged)
                exo.pauseAtEndOfMediaItems = !autoNext
                playbackInfo.onPlayerBuilt(lastBuildReason)
                exo.addAnalyticsListener(playbackInfo)
                ass.attach(exo) { handler.post { restartPlayer("ASS in the preloaded item") } }
                assSubtitles = ass
                passthroughRestart = PassthroughRestart(exo, handler) { playbackInfo.passthrough }
                    .also { it.attach() }
                freezeWatchdog = VideoFreezeWatchdog(exo, handler) { cure ->
                    playbackInfo.event("video frozen, audio on: $cure")
                }.also { it.attach() }
            }
    }

    /**
     * Builds the queue. A playlist (episodes of a series) is used when supplied, so the player can
     * roll on to the next episode without bouncing back to the web UI; otherwise it's a single item.
     */
    private fun buildMediaItems(): List<MediaItem> {
        val title = intent.getStringExtra(Extras.TITLE)
        val playlistUrls = intent.getStringArrayExtra(Extras.PLAYLIST_URLS)
        val playlistTitles = intent.getStringArrayExtra(Extras.PLAYLIST_TITLES)
        val startIndex = intent.getIntExtra(Extras.PLAYLIST_INDEX, 0)

        if (playlistUrls != null && playlistUrls.size > 1) {
            return playlistUrls.mapIndexed { index, url ->
                buildMediaItem(
                    url = url,
                    title = playlistTitles?.getOrNull(index) ?: title,
                    // Subtitles are only supplied for the item we start on.
                    withSubtitles = index == startIndex
                )
            }
        }

        val single = intent.data?.toString() ?: return emptyList()
        return listOf(buildMediaItem(single, title, withSubtitles = true))
    }

    private fun buildMediaItem(url: String, title: String?, withSubtitles: Boolean): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        title?.takeIf { it.isNotEmpty() }?.let {
            builder.setMediaMetadata(MediaMetadata.Builder().setTitle(it).build())
        }
        if (withSubtitles) {
            buildSubtitleConfigurations()?.let { builder.setSubtitleConfigurations(it) }
        }
        return builder.build()
    }

    private fun buildSubtitleConfigurations(): List<MediaItem.SubtitleConfiguration>? {
        val urls = intent.getStringArrayExtra(Extras.SUBS)?.takeIf { it.isNotEmpty() } ?: return null
        val names = intent.getStringArrayExtra(Extras.SUBS_NAME)
        return urls.mapIndexed { index, url ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(guessSubtitleMimeType(url))
                .setLabel(names?.getOrNull(index))
                .setSelectionFlags(0)
                .build()
        }
    }

    private fun guessSubtitleMimeType(url: String): String = when {
        url.endsWith(".ass", true) || url.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
        url.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
        url.endsWith(".ttml", true) || url.endsWith(".xml", true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun Array<String>?.toHeaderMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        // Flat [key, value, key, value] — same convention prepareHeaders() uses for other players.
        var i = 0
        while (i + 1 < size) {
            val key = this[i]
            val value = this[i + 1]
            if (key.isNotEmpty()) map[key] = value
            i += 2
        }
        return map
    }

    /** Shows what is playing: the series/film name, plus which episode when there is a queue. */
    private fun updateTitle() {
        val base = intent.getStringExtra(Extras.TITLE)?.trim().orEmpty()
        val p = player
        val name = intent.getStringExtra(Extras.TITLE_NAME)?.trim().orEmpty()
        val hasLogo = !intent.getStringExtra(Extras.TITLE_LOGO).isNullOrBlank()

        // Веб без логотипа и названия (старый или раздача неизвестна TMDB) — прежний текст
        if (name.isEmpty() && !hasLogo) {
            titleLogoView.visibility = View.GONE
            episodeView.visibility = View.GONE
            titleView.visibility = View.VISIBLE
            titleView.text = if (p != null && p.mediaItemCount > 1) {
                val episode = episodeLabel(p.currentMediaItemIndex)
                if (base.isEmpty()) episode else "$base · $episode"
            } else {
                base
            }
            return
        }

        // Как в веб-плеере: логотип (нет — название), под ним у сериала строка серии. Фильму
        // строка не нужна: веб её не шлёт
        val lines = intent.getStringArrayExtra(Extras.EPISODE_LINES)
        val index = p?.currentMediaItemIndex ?: 0
        val line = lines?.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: if (lines != null && p != null && p.mediaItemCount > 1) episodeLabel(index) else null
        episodeView.text = line.orEmpty()
        episodeView.visibility = if (line == null) View.GONE else View.VISIBLE

        val text = name.ifEmpty { base }
        when {
            titleLogoState == LogoState.SHOWN -> {
                titleLogoView.visibility = View.VISIBLE
                titleView.visibility = View.GONE
            }
            // Логотип ещё грузится: название невидимо, но место держит — иначе оно мелькнёт и
            // сменится картинкой. Не пришёл за TITLE_LOGO_WAIT_MS — остаётся названием
            titleLogoState == LogoState.LOADING && !titleLogoWaitOver -> {
                titleLogoView.visibility = View.INVISIBLE
                titleView.text = text
                titleView.visibility = View.INVISIBLE
            }
            else -> {
                titleLogoView.visibility = View.GONE
                titleView.text = text
                titleView.visibility = View.VISIBLE
            }
        }
    }

    private enum class LogoState { NONE, LOADING, SHOWN, FAILED }

    /** Загрузить логотип названия (один раз на запуск плеера, он общий для всех серий). */
    private fun loadTitleLogo() {
        val url = intent.getStringExtra(Extras.TITLE_LOGO)?.takeIf { it.startsWith("http") } ?: return
        titleLogoState = LogoState.LOADING
        titleLogoWaitOver = false
        titleLogoView.visibility = View.INVISIBLE
        Glide.with(this).load(url).listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
            ): Boolean {
                titleLogoState = LogoState.FAILED
                updateTitle()
                return false
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>?,
                dataSource: DataSource, isFirstResource: Boolean,
            ): Boolean {
                titleLogoState = LogoState.SHOWN
                updateTitle()
                return false
            }
        }).into(titleLogoView)
        handler.postDelayed({
            if (titleLogoState == LogoState.LOADING) {
                titleLogoWaitOver = true
                updateTitle()
            }
        }, TITLE_LOGO_WAIT_MS)
    }

    /** "Серия 1 - Название" from the web UI when it sent one for this item, else "Episode N". */
    private fun episodeLabel(index: Int): String =
        intent.getStringArrayExtra(Extras.PLAYLIST_EPISODE_TITLES)
            ?.getOrNull(index)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.player_episode_number, index + 1)

    /**
     * The one thing worth knowing when a file will not play is what is actually inside it, so an
     * "it does not work" report can name the codec instead of describing the symptom.
     */
    private fun logTracks(tracks: Tracks) {
        tracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                Log.i(
                    TAG,
                    "Track ${format.sampleMimeType} codecs=${format.codecs} " +
                            "supported=${group.isTrackSupported(i)} " +
                            "selected=${group.isTrackSelected(i)}",
                )
            }
        }
    }

    private val playerListener = object : Player.Listener {

        // Новый ролик или серия, смена качества в потоке — рамка видео под новый кадр
        override fun onVideoSizeChanged(videoSize: VideoSize) = applyVideoScale()

        override fun onPlaybackStateChanged(playbackState: Int) {
            progressBar.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

            if (playbackState == Player.STATE_ENDED) {
                val p = player ?: return
                // Only a finished *last* item counts as "watched to the end"; finishing an episode
                // mid-playlist just rolls on to the next one — unless auto-advance is off.
                if (!p.hasNextMediaItem() || !autoNext) {
                    endedByCompletion = true
                    finish()
                }
            }
        }

        // Автопереключение серий выключено: ExoPlayer встал в конце серии
        // (pauseAtEndOfMediaItems) — серия досмотрена, выходим, как после фильма
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && !autoNext &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                endedByCompletion = true
                finish()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseIcon()
            if (isPlaying) {
                wakeFromScreenSaver()
                showControls()
            } else {
                handler.removeCallbacks(hideControls)
            }
            scheduleScreenSaver(isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            updateEpisodeButtons()
            // New episode, new timings: drop what we had and re-query.
            skipFetchedForIndex = -1
            // Позиция прошлого перезапуска после порчи — из другой серии (пересборка плеера
            // приходит как PLAYLIST_CHANGED — та же серия, её не считаем)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                lastStreamRestartAt = -1L
                containerRetries = 0
            }
            skipRanges = emptyList()
            offeredSkipKeys.clear()
            hideSkipButton()
            onlineOnItem()
            loadServerAudioPref()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateEpisodeButtons()
            logTracks(tracks)
            restoreAudioChoice(tracks)
            applyServerAudio(tracks)
            // Отрисовка libass появляется вместе с дорожкой, раньше масштаб задать некому
            assSubtitles?.setFontScale(Prefs.PLAYER_SUBTITLE_SCALES[playerSubtitleSize])
            // Видео или звук есть, но ни одну дорожку этого типа ExoPlayer декодировать не
            // может: он бы играл без картинки или молча. libVLC справится со своим FFmpeg.
            if (!usingVlc) {
                for (type in intArrayOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
                    val groups = tracks.groups.filter { it.type == type }
                    // Превышение возможностей — не отказ: декодеры заявляют уровни с запасом
                    // (MediaTek — HEVC 5.1 Main tier, а UHD BDRemux — 5.1 High tier), и такие
                    // файлы на этих ТВ играют. ExoPlayer такую дорожку выбирает сам
                    // (exceedRendererCapabilitiesIfNecessary); не справится декодер — откат
                    // в onPlayerError
                    if (groups.isNotEmpty() && groups.none { it.isSupported(/* allowExceedsCapabilities= */ true) }) {
                        val mime = groups.first().getTrackFormat(0).sampleMimeType
                        if (type == C.TRACK_TYPE_VIDEO) {
                            hardwareOnlyFormat(groups.first().getTrackFormat(0))?.let {
                                showFormatUnsupported(it, mime)
                                return
                            }
                        }
                        reportDiagnostics(
                            PlaybackDiagnostics.KIND_VLC_UNSUPPORTED_TRACK,
                            "no supported ${if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"} track ($mime)",
                            null,
                            mime,
                        )
                        switchToVlc("no supported ${if (type == C.TRACK_TYPE_VIDEO) "video" else "audio"} track ($mime)")
                        return
                    }
                }
            }
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (p.duration > 0 && p.duration != C.TIME_UNSET) lastDuration = p.duration
            if (p.currentPosition >= 0) lastPosition = p.currentPosition
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)

            // Картинку не отпустили вовремя — так бывает, когда приложение сворачивают или
            // гасят экран (SHIELD, TCL). Это не сбой файла: свернули — onStop запомнит место
            // и onStart соберёт плеер заново; остались на экране — пересобрать сейчас же
            if (error.isSurfaceDetachTimeout()) {
                Log.w(TAG, "Surface detach timed out, not a playback failure")
                handler.postDelayed({
                    if (!isFinishing && !stoppedInBackground) restartPlayer("surface detach timeout")
                }, SURFACE_RESTART_DELAY_MS)
                return
            }

            // Декодер Dolby Vision устройства сломался посреди фильма (TCL, Realtek, profile
            // 8.1): у profile 7 и 8 под DV — обычный HEVC HDR10, его и играем с того же места,
            // а не уходим в libVLC
            val failedFormat = (error as? ExoPlaybackException)?.rendererFormat
            if (!usingVlc &&
                !DolbyVisionBaseLayer.forceBaseLayer &&
                error.isVideoFailure() &&
                DolbyVisionBaseLayer.hasHevcBaseLayer(failedFormat)
            ) {
                reportDiagnostics(PlaybackDiagnostics.KIND_DV_BASE_LAYER, "Dolby Vision decoder failed, playing the HEVC base layer", error)
                DolbyVisionBaseLayer.forceBaseLayer = true
                Log.w(TAG, "Dolby Vision decoder failed (${failedFormat?.codecs}), retrying as HEVC: ${error.errorCodeName}")
                handler.post { restartPlayer("Dolby Vision base layer") }
                return
            }

            // What "combined" is supposed to mean. ExoPlayer's own decoder fallback only
            // covers a decoder that fails to *initialise*; a decoder that starts and then chokes
            // partway through — which is how MPEG-4 in AVI tends to fail — never reached the
            // software path at all.
            if (!usingVlc &&
                !videoFallbackTried &&
                playerDecoderMode == Prefs.DECODER_COMBINED &&
                error.isVideoFailure() &&
                !error.isMpeg4Part2WithoutSoftware() &&
                !error.isAboveFullHd()
            ) {
                reportDiagnostics(PlaybackDiagnostics.KIND_SOFTWARE_VIDEO, "hardware video decoder failed", error)
                videoFallbackTried = true
                softwareVideoFallback = true
                Log.w(TAG, "Hardware video decoder failed, retrying in software: ${error.errorCodeName}")
                App.toast(R.string.player_switching_software, true)
                // Posted, not called inline: this runs inside the player's own error callback.
                handler.post { restartPlayer("software video fallback") }
                return
            }

            // Ресивер (или система) не принял битстрим: AudioTrack с AC3/DTS не создаётся.
            // Звук декодируем сами — с той же позиции, только для этого файла.
            // Битстрим может и не отказать, а встать: выход открыт, но не играет, и позиция
            // стоит — Media3 через 10 с сдаётся («stuck playing», 4 из 5 таких отчётов — с
            // AC3/E-AC3/DTS битстримом на TCL и Xiaomi). Это тоже отказ выхода.
            if (!usingVlc &&
                !pcmAudioFallback &&
                audioMode != Prefs.AUDIO_PCM &&
                (error.errorCode in AUDIO_OUTPUT_ERRORS || error.isStuckPlaying())
            ) {
                reportDiagnostics(PlaybackDiagnostics.KIND_PCM_AUDIO, "audio passthrough failed", error)
                pcmAudioFallback = true
                Log.w(TAG, "Audio passthrough failed, decoding to PCM: ${error.errorCodeName}")
                App.toast(R.string.player_audio_pcm_fallback, true)
                handler.post { restartPlayer("PCM audio fallback") }
                return
            }

            // Выход отказал и в PCM — так бывает с USB-ЦАП (многоканальный звук, 24 бита,
            // 96 кГц) — или звук не декодировался: ещё раз с самым простым выходом, а libVLC —
            // только если не поможет и это
            if (!usingVlc &&
                !safeAudioFallback &&
                (error.errorCode in AUDIO_OUTPUT_ERRORS || error.isAudioDecoderError() ||
                        (error.isStuckPlaying() && (player as? ExoPlayer)?.audioFormat != null))
            ) {
                reportDiagnostics(PlaybackDiagnostics.KIND_SAFE_AUDIO, "audio failed (USB: $usbAudio)", error)
                safeAudioFallback = true
                Log.w(TAG, "Audio failed (USB: $usbAudio), retrying stereo 16-bit: ${error.errorCodeName}")
                App.toast(R.string.player_audio_safe_fallback, true)
                handler.post { restartPlayer("safe audio fallback") }
                return
            }

            // Выход не создаётся и в стерео 16 бит («Cannot create AudioTrack», Sony BRAVIA 2015,
            // Android 7 — на смене серии): прежние звуковые выходы освобождаются в фоне, и
            // аудиосистема ещё не отпустила их. Ещё раз — после паузы, а не закрываться
            if (!usingVlc && safeAudioFallback && !audioOutputRetried && error.errorCode in AUDIO_OUTPUT_ERRORS) {
                audioOutputRetried = true
                fallbackSteps.put(
                    JSONObject().put("kind", "audio_output_retry").put("code", error.errorCodeName)
                        .put("atMs", SystemClock.elapsedRealtime() - startedAt),
                )
                Log.w(TAG, "Audio output failed again, retrying after a pause: ${error.errorCodeName}")
                handler.postDelayed({
                    if (!isFinishing && !stoppedInBackground) restartPlayer("audio output retry")
                }, AUDIO_OUTPUT_RETRY_DELAY_MS)
                return
            }

            // Контейнер «не распознан» у потока TorrServer: в отчётах это обычные MKV серий,
            // которые libVLC потом играл, — на входе были не те байты. Открыть заново (с
            // первыми байтами ответа в отчёте, чтобы понять, что приходит), потом — libVLC
            if (!usingVlc &&
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                containerRetries < MAX_CONTAINER_RETRIES &&
                currentTorrTarget() != null
            ) {
                containerRetries++
                reportDiagnostics(
                    PlaybackDiagnostics.KIND_CONTAINER_RETRY,
                    "container not recognized, reopening #$containerRetries",
                    error,
                    probeStream = true,
                )
                Log.w(TAG, "Container not recognized, reopening #$containerRetries")
                handler.postDelayed({
                    if (!isFinishing && !stoppedInBackground) restartPlayer("container retry")
                }, CONTAINER_RETRY_DELAY_MS)
                return
            }

            // Посреди просмотра разборщик получил не те байты («No valid varint length mask
            // found» в MKV, сбой HTTP-клиента системы): так бывает, когда TorrServer не успевает
            // за потоком. Файл при этом цел — новый разборщик найдёт ближайший кластер по
            // индексу и запросит данные заново. Раньше плеер на этом закрывался (3 отчёта).
            // Порча в последние секунды файла — это конец серии, а не повод перезапускаться
            // в ту же точку и закрываться («Нейровася» E05: сбой на 1425,9 с из 1425,9 с)
            if (!usingVlc && error.isMidStreamDataError() && lastDuration - lastPosition <= END_DATA_ERROR_SLACK_MS) {
                reportDiagnostics(PlaybackDiagnostics.KIND_STREAM_RESTART, "stream data error at the end (${lastPosition / 1000}s of ${lastDuration / 1000}s), finished", error)
                Log.w(TAG, "Stream data error ${lastDuration - lastPosition}ms before the end, treating as finished")
                handler.post { finishAsCompleted() }
                return
            }
            if (!usingVlc && streamRestarts < MAX_STREAM_RESTARTS && error.isMidStreamDataError()) {
                // Прошлый перезапуск не продвинулся — порча на этом месте не проходит: дальше
                // не в ту же точку, а с шагом вперёд (+10 с, +20 с)
                val stuck = lastStreamRestartAt >= 0 && lastPosition <= lastStreamRestartAt + STREAM_PROGRESS_MS
                val startAt = if (stuck) {
                    (lastPosition + STREAM_SKIP_STEP_MS * streamRestarts).coerceAtMost(lastDuration - END_DATA_ERROR_SLACK_MS)
                } else null
                reportDiagnostics(
                    PlaybackDiagnostics.KIND_STREAM_RESTART,
                    "stream data error at ${lastPosition / 1000}s, restart #${streamRestarts + 1}" +
                            (startAt?.let { " from ${it / 1000}s" } ?: ""),
                    error,
                )
                streamRestarts++
                lastStreamRestartAt = startAt ?: lastPosition
                Log.w(TAG, "Stream data error mid-playback, restarting at ${startAt ?: lastPosition}ms: ${error.errorCodeName}")
                App.toast(R.string.player_stream_restart, false)
                handler.post { restartPlayer("stream restart", startAt) }
                return
            }

            // Последний шаг перед отказом: libVLC со своим FFmpeg — тем же, чем внешние плееры
            // играют AVI с XviD/DivX. Сюда же — контейнер, который ExoPlayer не разбирает.
            if (!usingVlc && error.isVlcWorthTrying()) {
                // Декодер есть, но программный и не справился с 4K — VLC будет не лучше
                hardwareOnlyFormat((error as? ExoPlaybackException)?.rendererFormat)?.let {
                    showFormatUnsupported(it, (error as ExoPlaybackException).rendererFormat?.sampleMimeType)
                    return
                }
                reportDiagnostics(
                    PlaybackDiagnostics.KIND_VLC, "ExoPlayer failed: ${error.errorCodeName}", error,
                    probeStream = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                )
                switchToVlc("${error.errorCodeName} ${error.formatDetail().orEmpty()}")
                return
            }

            // Name what actually failed. "Unsupported" on its own sends the user nowhere;
            // the codec and error code say whether another decoder mode could help at all.
            val detail = listOfNotNull(error.formatDetail(), error.errorCodeName)
                .joinToString(", ")
            Log.e(TAG, "Giving up on playback: $detail")
            reportDiagnostics(
                PlaybackDiagnostics.KIND_FAILED,
                "${if (usingVlc) "libVLC" else "ExoPlayer"} gave up: $detail",
                error,
            )
            App.toast(getString(R.string.player_playback_failed, detail), true)
            finish()
        }

        /**
         * A video failure worth retrying in software: a decoder error, or the unexpected-runtime
         * error that a struggling codec surfaces as. A null format means the player could not say
         * which renderer broke, and video is the one worth trying again.
         */
        private fun PlaybackException.isVideoFailure(): Boolean {
            val mime = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
            val retryable = isDecoderError() ||
                    errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
            return retryable && (mime == null || mime.startsWith("video/"))
        }

        /** The offending track's mime and codec string, when the error carries a format. */
        private fun PlaybackException.formatDetail(): String? =
            (this as? ExoPlaybackException)?.rendererFormat?.let { format ->
                listOfNotNull(format.sampleMimeType, format.codecs).joinToString(" ")
                    .takeIf { it.isNotBlank() }
            }

        /** Media3 сдалась: плеер «играет», а позиция 10 с не двигается — встал звуковой выход. */
        private fun PlaybackException.isStuckPlaying(): Boolean {
            if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
            var cause: Throwable? = this
            while (cause != null) {
                if (cause is StuckPlayerException) {
                    return cause.stuckType == StuckPlayerException.STUCK_PLAYING_NO_PROGRESS
                }
                cause = cause.cause
            }
            return false
        }

        private fun PlaybackException.isSurfaceDetachTimeout(): Boolean {
            if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
            var cause: Throwable? = this
            while (cause != null) {
                if (cause is ExoTimeoutException) {
                    return cause.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE
                }
                cause = cause.cause
            }
            return false
        }

        /**
         * Ошибка данных, а не сети и не декодера, — и уже посреди просмотра: файл открылся,
         * длительность известна, позиция ушла от начала.
         */
        private fun PlaybackException.isMidStreamDataError(): Boolean =
            errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            ) && lastDuration > 0 && lastPosition > 0

        /**
         * Кадр больше 1080p: программный декодер с таким не справится (4K HEVC на приставке —
         * единицы кадров в секунду), откат на него только затягивает переход в libVLC.
         */
        private fun PlaybackException.isAboveFullHd(): Boolean {
            val format = (this as? ExoPlaybackException)?.rendererFormat
                ?: (player as? ExoPlayer)?.videoFormat
                ?: return false
            return format.width > 1920 || format.height > 1088
        }

        /**
         * MPEG-4 Part 2 (XviD/DivX), который не берёт и FFmpeg-модуль. Программный декодер
         * Android для него рассчитан максимум на 352×288, так что откат на него ничего не даёт —
         * сразу в libVLC. С FFmpeg (до 1080p) программный откат — и есть спасение, когда
         * аппаратный MPEG-4 спотыкается посреди файла.
         */
        private fun PlaybackException.isMpeg4Part2WithoutSoftware(): Boolean {
            val mime = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType ?: return false
            return mime in MPEG4_PART2_MIMES && !FfmpegLibrary.supportsFormat(mime)
        }

        private fun PlaybackException.isVlcWorthTrying(): Boolean =
            isDecoderError() || isVideoFailure() || errorCode in setOf(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            )

        private fun PlaybackException.isAudioDecoderError(): Boolean = isDecoderError() &&
                (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true

        private fun PlaybackException.isDecoderError(): Boolean = errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }

    // endregion

    // region lifecycle / result

    /**
     * Reports where playback stopped, using the same result contract the external players use, so
     * MainActivity can persist the timeline and refresh the TV "Continue Watching" row.
     */
    private fun deliverResult() {
        if (resultDelivered) return
        resultDelivered = true

        val uri = player?.currentMediaItem?.localConfiguration?.uri ?: intent.data
        val data = Intent(RESULT_ACTION).apply {
            uri?.let { setData(it) }
            putExtra(Extras.POSITION, if (endedByCompletion) lastDuration else lastPosition)
            putExtra("duration", lastDuration)
            putExtra("end_by", if (endedByCompletion) END_BY_COMPLETION else END_BY_USER)
            autoSkipOverride?.let { putExtra(Extras.AUTO_SKIP, it) }
        }
        setResult(Activity.RESULT_OK, data)
    }

    private fun snapshotPosition() {
        player?.let {
            lastPosition = it.currentPosition.coerceAtLeast(0L)
            if (it.duration > 0 && it.duration != C.TIME_UNSET) lastDuration = it.duration
        }
    }

    private fun rememberResumePoint() {
        val p = player ?: return
        resumeIndex = p.currentMediaItemIndex
        resumePositionMs = p.currentPosition.coerceAtLeast(0L)
        // Звук выключается только на миг перезапуска битстрима (PassthroughRestart) — в снимок не берём
        resumeTrackParams = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
    }

    private fun releasePlayer() {
        snapshotPosition()
        handler.removeCallbacks(onlineTick)
        timecodeJob?.cancel()
        timecodeJob = null
        passthroughRestart?.release()
        passthroughRestart = null
        freezeWatchdog?.release()
        freezeWatchdog = null
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        playerView.player = null
        assSubtitles?.release()
        assSubtitles = null
    }

    override fun finish() {
        snapshotPosition()
        deliverResult()
        super.finish()
    }

    override fun onStart() {
        super.onStart()
        if (!stoppedInBackground) return
        stoppedInBackground = false
        // Вернулись из свёрнутого состояния: итог просмотра ещё впереди
        resultDelivered = false
        if (player != null) return
        try {
            initPlayer()
            if (!playingBeforePause) player?.playWhenReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume playback", e)
            App.toast(R.string.no_launch_player, true)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playingBeforePause = it.playWhenReady
            it.pause()
        }
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        stopTorrStats()
        snapshotPosition()
        deliverResult()
        if (!isFinishing) {
            rememberResumePoint()
            stoppedInBackground = true
        }
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterAudioDeviceCallback()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    // endregion
}
