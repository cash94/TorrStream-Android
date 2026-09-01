package my.torrstream.app.channels

import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.google.gson.Gson
import org.json.JSONObject
import my.torrstream.app.App
import my.torrstream.app.MainActivity
import my.torrstream.app.PlayerStateManager
import my.torrstream.app.helpers.Helpers.getJson
import my.torrstream.app.helpers.Helpers.isTvContentProviderAvailable
import my.torrstream.app.models.LAMPA_CARD_KEY
import my.torrstream.app.models.LampaCard

/**
 * Publishes and maintains the "Continue Watching" row (Android TV's Watch Next channel)
 * from [PlayerStateManager]'s persisted playback states.
 */
@RequiresApi(Build.VERSION_CODES.O)
object WatchNext {
    private const val TAG = "WatchNext"
    private const val MAX_ITEMS = 10

    // activityKey -> lastUpdated timestamp at the moment the user removed the row.
    // A state is only re-published once it has *new* progress past that point,
    // so an explicit removal sticks until the user actually watches it again.
    private val dismissedPrefs
        get() = App.context.getSharedPreferences("watch_next_dismissed", MODE_PRIVATE)

    private fun isDismissed(state: PlayerStateManager.PlaybackState): Boolean {
        val dismissedAt = dismissedPrefs.getLong(state.activityKey, -1L)
        return dismissedAt != -1L && state.lastUpdated <= dismissedAt
    }

    private fun dismiss(activityKey: String, atMillis: Long) {
        dismissedPrefs.edit().putLong(activityKey, atMillis).apply()
    }

    private fun LampaCard.posterUri(): Uri? =
        img?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    /**
     * Rebuilds a `{"movie": {...LampaCard...}}` blob shaped like MainActivity.getCardFromActivity()
     * expects, so the Watch Next row's resume intent can round-trip back to the right content.
     * findStateByCard() matches on the card's id/title, not on the exact original JSON, so
     * reconstructing the card from what we already persisted is enough.
     */
    private fun buildActivityJson(card: LampaCard): String {
        val movieJson = JSONObject(Gson().toJson(card))
        return JSONObject().apply { put("movie", movieJson) }.toString()
    }

    private fun resumeIntentUri(activityJson: String): Uri {
        val intent = Intent(Intent.ACTION_VIEW, null, App.context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("continueWatch", true)
            putExtra("lampaActivity", activityJson)
            putExtra("android.intent.extra.START_PLAYBACK", true)
        }
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    private fun PlayerStateManager.PlaybackState.card(): LampaCard? =
        (extras[LAMPA_CARD_KEY] as? String)?.let { getJson(it, LampaCard::class.java) }

    /**
     * Re-publishes the whole Watch Next row from currently persisted playback states.
     * Safe to call periodically (e.g. from [my.torrstream.app.sched.Scheduler]) and after
     * every meaningful playback position update.
     */
    fun resyncAll() {
        if (!isTvContentProviderAvailable) return
        try {
            val resolver = App.context.contentResolver
            val existing = resolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null
            )?.use { cursor ->
                val map = mutableMapOf<String, Long>()
                if (cursor.moveToFirst()) {
                    do {
                        val program = WatchNextProgram.fromCursor(cursor)
                        program.internalProviderId?.let { map[it] = program.id }
                    } while (cursor.moveToNext())
                }
                map
            } ?: emptyMap()

            val manager = PlayerStateManager(App.context)
            val candidates = manager.getContinueWatchingCandidates(MAX_ITEMS)
            val seenKeys = mutableSetOf<String>()

            candidates.forEach { state ->
                if (isDismissed(state)) return@forEach
                val card = state.card() ?: return@forEach
                val timeline = state.currentItem?.timeline
                val durationMs = ((timeline?.duration ?: 0.0) * 1000).toLong()
                val positionMs = state.currentPosition.takeIf { it > 0 }
                    ?: ((timeline?.time ?: 0.0) * 1000).toLong()
                if (durationMs <= 0 || positionMs <= 0) return@forEach
                val poster = card.posterUri() ?: return@forEach // skip cardless/posterless items (e.g. trailers)

                seenKeys += state.activityKey
                val title = card.title ?: card.name ?: return@forEach

                val values = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setInternalProviderId(state.activityKey)
                    .setTitle(title)
                    .setPosterArtUri(poster)
                    .setDurationMillis(durationMs.toInt())
                    .setLastPlaybackPositionMillis(positionMs.toInt())
                    .setLastEngagementTimeUtcMillis(state.lastUpdated)
                    .setIntentUri(resumeIntentUri(buildActivityJson(card)))
                    .build()
                    .toContentValues()

                try {
                    val existingId = existing[state.activityKey]
                    if (existingId != null) {
                        resolver.update(
                            TvContractCompat.buildWatchNextProgramUri(existingId), values, null, null
                        )
                    } else {
                        resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to publish Watch Next row for ${state.activityKey}", e)
                }
            }

            // Drop rows for content that's no longer a valid candidate (finished/removed).
            (existing.keys - seenKeys).forEach { staleKey ->
                existing[staleKey]?.let { removeById(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resyncAll failed", e)
        }
    }

    /**
     * Adds or updates the Watch Next row for a single in-progress item. Cheaper than
     * [resyncAll] for the common "position just updated" case.
     */
    fun addLastPlayed(card: LampaCard, state: PlayerStateManager.PlaybackState) {
        if (!isTvContentProviderAvailable) return
        resyncAll() // Keep it simple/robust: one code path maintains the whole row.
    }

    /** Removes the Watch Next row tied to [state], e.g. once playback has finished. */
    fun removeContinueWatch(state: PlayerStateManager.PlaybackState) {
        if (!isTvContentProviderAvailable) return
        try {
            dismiss(state.activityKey, System.currentTimeMillis())
            findProgramId(state.activityKey)?.let { removeById(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Watch Next row for ${state.activityKey}", e)
        }
    }

    /** Removes a row by its raw Watch Next program id (e.g. from a system removal broadcast). */
    fun removeByProgramId(watchNextProgramId: Long) {
        if (!isTvContentProviderAvailable) return
        getActivityKeyForProgramId(watchNextProgramId)?.let { dismiss(it, System.currentTimeMillis()) }
        removeById(watchNextProgramId)
    }

    /** Finds the activityKey we tagged a given Watch Next program row with, if any. */
    fun getActivityKeyForProgramId(watchNextProgramId: Long): String? {
        if (!isTvContentProviderAvailable) return null
        return try {
            App.context.contentResolver.query(
                TvContractCompat.buildWatchNextProgramUri(watchNextProgramId), null, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) WatchNextProgram.fromCursor(cursor).internalProviderId else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActivityKeyForProgramId failed", e)
            null
        }
    }

    private fun findProgramId(activityKey: String): Long? {
        return App.context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val program = WatchNextProgram.fromCursor(cursor)
                    if (program.internalProviderId == activityKey) return program.id
                } while (cursor.moveToNext())
            }
            null
        }
    }

    private fun removeById(watchNextProgramId: Long) {
        try {
            App.context.contentResolver.delete(
                TvContractCompat.buildWatchNextProgramUri(watchNextProgramId), null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Watch Next row $watchNextProgramId", e)
        }
    }
}
