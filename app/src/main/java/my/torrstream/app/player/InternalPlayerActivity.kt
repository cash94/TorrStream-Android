package my.torrstream.app.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
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
import java.net.URL
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import my.torrstream.app.App
import my.torrstream.app.R
import my.torrstream.app.helpers.Prefs
import my.torrstream.app.helpers.Prefs.playerBufferMb
import my.torrstream.app.helpers.Prefs.playerDecoderMode
import my.torrstream.app.helpers.Prefs.playerShowClock
// Non-transitive R classes are on, so media3's own drawables are not merged into our R.
import androidx.media3.ui.R as Media3R
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

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
        const val PLAYLIST_INDEX = "playlist_index"
        const val SUBS = "subs"                // String[] of subtitle urls for the current item
        const val SUBS_NAME = "subs.name"
        const val SKIP_API = "skip_api"        // base url of the intro/credits timing service
        const val TMDB_ID = "tmdb_id"
        const val SEASON = "season"
        const val TIMECODE_API = "timecode_api"  // POST endpoint that stores playback progress
        const val CLIENT_ID = "client_id"
    }

    private data class SkipRange(val type: String, val startMs: Long, val endMs: Long) {
        val key: String get() = "${type}_${startMs}_$endMs"
    }

    companion object {
        private const val TAG = "InternalPlayer"
        const val RESULT_ACTION = "my.torrstream.app.player.result.VIEW"
        const val END_BY_COMPLETION = "completion"
        const val END_BY_USER = "user"

        private const val TYPE_INTRO = "intro"
        private const val TYPE_CREDITS = "credits"
        private const val SKIP_BUTTON_TIMEOUT_MS = 10_000L
        private const val SCREEN_SAVER_DELAY_MS = 15_000L
        private const val SCREEN_SAVER_DIM = 0.75f
        private const val NEW_LINE = "\n"
        private const val SEPARATOR = "  \u00B7  "

        private const val TIMECODE_SAVE_INTERVAL_MS = 30_000L
        /** Below this the position isn't worth resuming from — matches the web player. */
        private const val TIMECODE_MIN_SEC = 5
        /** This close to the end, resuming would drop the viewer back into the credits. */
        private const val TIMECODE_END_GUARD_SEC = 10

        private const val SEEK_STEP_MS = 15_000L

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
        private const val SEEK_DEBOUNCE_MS = 250L
        private const val SEEK_BAR_STEPS = 1000
        private const val DIMMED_ALPHA = 0.35f

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )
    }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var audioDisabledAfterError = false

    private lateinit var playerView: PlayerView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleView: TextView
    private lateinit var controlsPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView

    private lateinit var btnPrevEpisode: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnNextEpisode: ImageButton
    private lateinit var btnAudio: ImageButton
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnEpisodes: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnResize: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var clockView: TextView
    private lateinit var remainingText: TextView
    private lateinit var dimOverlay: View

    /** Kept so the status line can report how full the buffer actually is. */
    private var loadControl: DefaultLoadControl? = null
    private var screenSaverOn = false

    /** Set when the player is rebuilt (buffer change) so playback resumes where it left off. */
    private var resumeIndex: Int? = null
    private var resumePositionMs: Long? = null

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

    private lateinit var sidePanel: View
    private lateinit var sidePanelTitle: TextView
    private lateinit var sidePanelList: ListView
    private var sidePanelActions: List<() -> Unit> = emptyList()
    private var sidePanelOpener: View? = null

    /** Set while an item's action swaps in another panel, so the click does not close it. */
    private var sidePanelReplaced = false

    private val handler = Handler(Looper.getMainLooper())
    private var resizeModeIndex = 0
    private var isMuted = false
    private var volumeBeforeMute = 1f

    /** Where "down" from the seek bar returns to — the button the user came up from. */
    private var lastFocusedButton: View? = null

    // Mirrored continuously: after the player is released its position is no longer readable,
    // but finish() still has to report where the user stopped.
    private var lastPosition = 0L
    private var lastDuration = 0L
    private var endedByCompletion = false
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_internal_player)
        bindViews()
        setupControls()

        try {
            initPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
            App.toast(R.string.no_launch_player, true)
            finish()
        }
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        progressBar = findViewById(R.id.playerProgress)
        titleView = findViewById(R.id.playerTitle)
        controlsPanel = findViewById(R.id.controlsPanel)
        seekBar = findViewById(R.id.seekBar)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)

        btnPrevEpisode = findViewById(R.id.btnPrevEpisode)
        btnRewind = findViewById(R.id.btnRewind)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnForward = findViewById(R.id.btnForward)
        btnNextEpisode = findViewById(R.id.btnNextEpisode)
        btnAudio = findViewById(R.id.btnAudio)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnEpisodes = findViewById(R.id.btnEpisodes)
        btnMute = findViewById(R.id.btnMute)
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
        btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        btnEpisodes.setOnClickListener { showEpisodesDialog() }
        btnMute.setOnClickListener { toggleMute() }
        btnResize.setOnClickListener { cycleResizeMode() }
        btnSettings.setOnClickListener { showSettingsPanel() }

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
                handler.postDelayed(seekCommit, SEEK_DEBOUNCE_MS)
                onScrubbed(progress, target, duration)
                showControls()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                handler.removeCallbacks(seekCommit)
                seekCommit.run()
            }
        })

        skipButton.setOnClickListener { executeSkip() }

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
    }

    // region pause screen saver

    /**
     * A paused frame left on screen for long enough is what burns an OLED panel, so a pause that
     * outlasts the delay drops the HUD and dims everything. Any key or tap brings it back, and the
     * press that wakes is swallowed so it does nothing else.
     */
    private val enterScreenSaver = Runnable {
        if (player?.isPlaying == true) return@Runnable
        screenSaverOn = true
        setControlsVisible(false)
        hideSidePanel()
        clockView.visibility = View.GONE
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.animate().alpha(SCREEN_SAVER_DIM).setDuration(600).start()
    }

    private fun scheduleScreenSaver(isPlaying: Boolean) {
        handler.removeCallbacks(enterScreenSaver)
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
     * Worth knowing what "software" can and cannot reach: the bundled FFmpeg extension decodes
     * audio only — it ships no video decoder — so software video means Android's own
     * `c2.android.*` MediaCodec decoders. Those are what usually rescue an AVI carrying
     * MPEG-4 Part 2 (DivX/Xvid) on a device whose hardware decoder rejects it.
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(this)
        return when (playerDecoderMode) {
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
            // wins where it is available. The bundled FFmpeg decoders only step in when nothing
            // else can handle the track — e.g. a DTS-only file on a phone with no DTS decoder.
            else -> factory
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
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
        val labels = listOf(
            withDescription(
                "${getString(R.string.player_clock)}: $clockState",
                R.string.player_clock_desc,
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
        )
        val actions = listOf<() -> Unit>(
            {
                playerShowClock = !playerShowClock
                applyClockVisibility()
                // Reopen so the row shows the value it now holds.
                showSettingsPanel()
            },
            { showBufferPanel() },
            { showDecoderPanel() },
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

    private fun showBufferPanel() {
        val options = Prefs.PLAYER_BUFFER_OPTIONS.toList()
        showSidePanel(
            titleRes = R.string.player_buffer,
            labels = options.map { getString(R.string.player_buffer_mb, it) },
            checkedIndex = options.indexOf(playerBufferMb),
            actions = options.map { mb -> { applyBufferSize(mb) } },
            opener = btnSettings,
        )
    }

    private fun decoderDescriptionRes(mode: Int) = when (mode) {
        Prefs.DECODER_HARDWARE -> R.string.player_decoder_hardware_desc
        Prefs.DECODER_SOFTWARE -> R.string.player_decoder_software_desc
        else -> R.string.player_decoder_combined_desc
    }

    /** Title with an explanatory second line, dimmer and smaller so the choice still reads first. */
    private fun withDescription(title: String, descriptionRes: Int): CharSequence {
        val builder = SpannableStringBuilder(title)
            .append(NEW_LINE)
            .append(getString(descriptionRes))
        val from = title.length + 1
        builder.setSpan(
            RelativeSizeSpan(0.75f), from, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
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
        )
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
    private fun restartPlayer(what: String) {
        val p = player
        resumeIndex = p?.currentMediaItemIndex
        resumePositionMs = p?.currentPosition?.coerceAtLeast(0L)

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

    private fun applyClockVisibility() {
        clockView.visibility = if (playerShowClock) View.VISIBLE else View.GONE
    }

    private fun updateClock() {
        if (!playerShowClock) return
        clockView.text = android.text.format.DateFormat.getTimeFormat(this).format(Date())
    }

    /**
     * "1:23:45 left · ends at 23:47" — the wall-clock finish is the part people actually plan
     * around, and working it out from a remaining-time readout is a chore.
     */
    private fun updateStatusLine(positionMs: Long, durationMs: Long) {
        val parts = mutableListOf(getString(R.string.player_buffer_fill, bufferFillPercent()))
        if (durationMs > 0) {
            val leftMs = (durationMs - positionMs).coerceAtLeast(0L)
            val endsAt = Date(System.currentTimeMillis() + leftMs)
            parts += getString(
                R.string.player_time_left,
                formatTime(leftMs),
                android.text.format.DateFormat.getTimeFormat(this).format(endsAt),
            )
        }
        remainingText.text = parts.joinToString(SEPARATOR)
    }

    /**
     * How full the buffer is, against the size chosen in settings. Taken from the allocator's own
     * byte count rather than ExoPlayer's bufferedPercentage, which measures progress through the
     * whole file and would sit near zero for an entire film.
     */
    private fun bufferFillPercent(): Int {
        val target = playerBufferMb * 1024L * 1024L
        val allocated = loadControl?.allocator?.totalBytesAllocated?.toLong() ?: return 0
        if (target <= 0L) return 0
        return ((allocated * 100L) / target).toInt().coerceIn(0, 100)
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
    ) {
        sidePanelActions = actions
        sidePanelOpener = opener
        sidePanelReplaced = true
        sidePanelTitle.setText(titleRes)
        // Without this the tick from a previous panel (say episode 3) survives into the next one.
        sidePanelList.clearChoices()
        sidePanelList.adapter = ArrayAdapter(this, itemLayout, labels)
        if (checkedIndex in labels.indices) {
            sidePanelList.setItemChecked(checkedIndex, true)
            sidePanelList.setSelection(checkedIndex)
        }

        handler.removeCallbacks(hideControls)
        setControlsVisible(false)

        sidePanel.visibility = View.VISIBLE
        sidePanel.animate().translationX(0f).setDuration(200).start()
        sidePanelList.requestFocus()
    }

    private fun hideSidePanel() {
        if (!sidePanelOpen) return
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
        btnAudio, btnSubtitles, btnEpisodes, btnMute, btnResize, btnSettings
    )

    private var pendingSeekMs = 0L

    /** True only between a scrub keypress and the debounced seek landing. */
    private var scrubbing = false
    private val seekCommit = Runnable {
        player?.seekTo(pendingSeekMs)
        scrubbing = false
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

    private fun toggleMute() {
        val p = player ?: return
        if (isMuted) {
            p.volume = volumeBeforeMute
            isMuted = false
        } else {
            volumeBeforeMute = p.volume.takeIf { it > 0f } ?: 1f
            p.volume = 0f
            isMuted = true
        }
        btnMute.setImageResource(
            if (isMuted) R.drawable.ic_player_volume_off else R.drawable.ic_player_volume
        )
        showControls()
    }

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        playerView.resizeMode = RESIZE_MODES[resizeModeIndex]
        showControls()
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        btnPlayPause.setImageResource(
            if (playing) Media3R.drawable.exo_icon_pause else Media3R.drawable.exo_icon_play
        )
    }

    /** Episode buttons stay focusable when unavailable so the left-to-right order never shifts. */
    private fun updateEpisodeButtons() {
        val p = player
        btnPrevEpisode.alpha = if (p?.hasPreviousMediaItem() == true) 1f else DIMMED_ALPHA
        btnNextEpisode.alpha = if (p?.hasNextMediaItem() == true) 1f else DIMMED_ALPHA
        val multiple = (p?.mediaItemCount ?: 0) > 1
        btnEpisodes.alpha = if (multiple) 1f else DIMMED_ALPHA
    }

    // endregion

    // region controls visibility

    private val hideControls = Runnable { setControlsVisible(false) }

    private fun showControls() {
        setControlsVisible(true)
        handler.removeCallbacks(hideControls)
        // Only auto-hide while something is actually playing; a paused picture keeps its controls.
        if (player?.isPlaying == true) handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
    }

    private fun setControlsVisible(visible: Boolean) {
        val wasVisible = controlsPanel.visibility == View.VISIBLE
        controlsPanel.visibility = if (visible) View.VISIBLE else View.GONE
        titleView.visibility = if (visible) View.VISIBLE else View.GONE
        // The read-out belongs to the seek bar; without the bar there is nothing to read.
        if (!visible) {
            handler.removeCallbacks(hideSeekOverlay)
            seekOverlay.visibility = View.GONE
        }
        if (skipVisible) skipContainer.post { positionSkipButton() }
        if (visible && !wasVisible) {
            // A live skip offer keeps the remote — it expires, the control bar doesn't.
            if (!skipButtonFocused) (lastFocusedButton ?: btnPlayPause).requestFocus()
        }
    }

    private val controlsVisible: Boolean
        get() = controlsPanel.visibility == View.VISIBLE

    /**
     * Remote navigation, as specified: the button row runs left to right, "up" from it lands on
     * the seek bar (where left/right scrub), "up" again hides the panel, and "down" returns.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            // While dimmed, the first press only wakes: it should not also seek or open a panel.
            if (wakeFromScreenSaver()) return true
            // The slide-out list owns the remote while it is open: up/down walk the list, and
            // left/back close it (left, because the panel sits against the right edge).
            if (sidePanelOpen) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                ) {
                    hideSidePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (controlsVisible && seekBar.hasFocus()) {
                        handler.removeCallbacks(hideControls)
                        setControlsVisible(false)
                        return true
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
            if (controlsVisible) showControls() // keep it alive while the user is navigating
        }
        return super.dispatchKeyEvent(event)
    }

    private fun KeyEvent.isDirectionalOrEnter(): Boolean = keyCode in setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    // endregion

    // region skip intro / credits

    private val hideSkip = Runnable { hideSkipButton() }

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
        activeSkipRange = range
        offeredSkipKeys += range.key
        skipButton.setText(
            if (range.type == TYPE_INTRO) R.string.player_skip_intro
            else R.string.player_skip_credits
        )
        skipContainer.visibility = View.VISIBLE
        positionSkipButton()
        // The offer is short-lived, so it takes the remote: one OK press and it's done.
        skipButton.requestFocus()

        // Bar drains left-to-right over exactly the window the offer stays up.
        skipProgress.animate().cancel()
        skipProgress.scaleX = 1f
        skipProgress.animate()
            .scaleX(0f)
            .setDuration(SKIP_BUTTON_TIMEOUT_MS)
            .setInterpolator(LinearInterpolator())
            .start()

        handler.removeCallbacks(hideSkip)
        handler.postDelayed(hideSkip, SKIP_BUTTON_TIMEOUT_MS)
    }

    private fun hideSkipButton() {
        handler.removeCallbacks(hideSkip)
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
        } else if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        } else {
            endedByCompletion = true
            finish()
            return
        }
        hideSkipButton()
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
     * Posts playback progress every 30 s.
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
                put("fileId", snapshot.fileId)
                put("timecode", snapshot.timeSec)
                put("duration", snapshot.durationSec)
            }.toString()

            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
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

    private fun showTrackDialog(trackType: @C.TrackType Int) {
        val p = player ?: return
        val selector = trackSelector ?: return
        val groups = p.currentTracks.groups.filter { it.type == trackType && it.isSupported }

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        var checked = -1

        // Subtitles can be turned off entirely; an audio track cannot.
        if (trackType == C.TRACK_TYPE_TEXT) {
            val textDisabled = selector.parameters.getRendererDisabled(C.TRACK_TYPE_TEXT) ||
                    groups.none { group -> (0 until group.length).any { group.isTrackSelected(it) } }
            labels += getString(R.string.player_subtitles_off)
            actions += {
                selector.parameters = selector.buildUponParameters()
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
                actions += {
                    selector.parameters = selector.buildUponParameters()
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
            opener = if (audio) btnAudio else btnSubtitles
        )
    }

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
        val titles = (0 until p.mediaItemCount).map { index ->
            getString(R.string.player_episode_number, index + 1)
        }
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

    // region player

    private fun initPlayer() {
        val headers = intent.getStringArrayExtra(Extras.HEADERS).toHeaderMap()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }

        val exoPlayer = ExoPlayer.Builder(
            this,
            buildRenderersFactory()
        )
            .setTrackSelector(DefaultTrackSelector(this).also { trackSelector = it })
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory))
            )
            // How much of the stream is held in memory. LoadControl is fixed at build time, so
            // changing this setting rebuilds the player (see applyBufferSize).
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setTargetBufferBytes(playerBufferMb * 1024 * 1024)
                    .build()
                    .also { loadControl = it }
            )
            .build()

        exoPlayer.addListener(playerListener)
        playerView.player = exoPlayer
        playerView.resizeMode = RESIZE_MODES[resizeModeIndex]
        player = exoPlayer

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

        exoPlayer.setMediaItems(items, startIndex, startPosition)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()

        updateTitle()
        updateEpisodeButtons()
        updatePlayPauseIcon()
        applyClockVisibility()
        handler.post(progressTick)
        startTimecodeReporting()
        showControls()
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
        titleView.text = if (p != null && p.mediaItemCount > 1) {
            val episode = getString(R.string.player_episode_number, p.currentMediaItemIndex + 1)
            if (base.isEmpty()) episode else "$base · $episode"
        } else {
            base
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            progressBar.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

            if (playbackState == Player.STATE_ENDED) {
                val p = player ?: return
                // Only a finished *last* item counts as "watched to the end"; finishing an episode
                // mid-playlist just rolls on to the next one.
                if (!p.hasNextMediaItem()) {
                    endedByCompletion = true
                    finish()
                }
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
            skipRanges = emptyList()
            offeredSkipKeys.clear()
            hideSkipButton()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateEpisodeButtons()
        }

        override fun onEvents(p: Player, events: Player.Events) {
            if (p.duration > 0 && p.duration != C.TIME_UNSET) lastDuration = p.duration
            if (p.currentPosition >= 0) lastPosition = p.currentPosition
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)

            // A codec the device can't handle should cost the sound, not the whole film: drop the
            // audio track and carry on. Only reached when neither a platform decoder, HDMI
            // passthrough nor the bundled FFmpeg decoders could take the track.
            if (!audioDisabledAfterError && error.isDecoderError()) {
                val selector = trackSelector
                if (selector != null) {
                    audioDisabledAfterError = true
                    Log.w(TAG, "Retrying without audio after decoder error")
                    selector.parameters = selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .build()
                    App.toast(R.string.audio_unsupported, true)
                    player?.prepare()
                    return
                }
            }

            // Name what actually failed. "Unsupported" on its own sends the user nowhere;
            // the codec and error code say whether another decoder mode could help at all.
            val detail = listOfNotNull(error.formatDetail(), error.errorCodeName)
                .joinToString(", ")
            Log.e(TAG, "Giving up on playback: $detail")
            App.toast(getString(R.string.player_playback_failed, detail), true)
            finish()
        }

        /** The offending track's mime and codec string, when the error carries a format. */
        private fun PlaybackException.formatDetail(): String? =
            (this as? ExoPlaybackException)?.rendererFormat?.let { format ->
                listOfNotNull(format.sampleMimeType, format.codecs).joinToString(" ")
                    .takeIf { it.isNotBlank() }
            }

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
        }
        setResult(Activity.RESULT_OK, data)
    }

    private fun snapshotPosition() {
        player?.let {
            lastPosition = it.currentPosition.coerceAtLeast(0L)
            if (it.duration > 0 && it.duration != C.TIME_UNSET) lastDuration = it.duration
        }
    }

    private fun releasePlayer() {
        snapshotPosition()
        timecodeJob?.cancel()
        timecodeJob = null
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    override fun finish() {
        snapshotPosition()
        deliverResult()
        super.finish()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        snapshotPosition()
        deliverResult()
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    // endregion
}
