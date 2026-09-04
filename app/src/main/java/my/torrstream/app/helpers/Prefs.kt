package my.torrstream.app.helpers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import my.torrstream.app.App
import my.torrstream.app.BuildConfig
import my.torrstream.app.tmdb.TMDB
import java.util.Locale
import androidx.core.content.edit

object Prefs {

    // Constants for SharedPreferences keys
    const val APP_PREFERENCES = "settings"
    const val STORAGE_PREFERENCES = "storage"
    private const val APP_LAST_PLAYED = "last_played"
    private const val APP_URL = "url"
    private const val APP_URL_HISTORY = "lampa_history"
    private const val APP_PLAYER = "player"
    private const val IPTV_PLAYER = "iptv_player"
    private const val PLAYER_KEEP_CONN_KEY = "player_keep_connection"
    private const val LAMPA_SOURCE = "source"
    private const val APP_LANG = "lang"
    private const val TMDB_API_KEY = "tmdb_api_url"
    private const val TMDB_IMG_KEY = "tmdb_image_url"
    private const val MIGRATE_KEY = "migrate"
    private const val PLAYER_CLOCK_KEY = "player_clock"
    private const val PLAYER_BUFFER_KEY = "player_buffer_mb"
    private const val PLAYER_DECODER_KEY = "player_decoder"

    /** Hardware first, software picked up whenever hardware cannot handle the track. */
    const val DECODER_COMBINED = 0
    /** Platform decoders only — no software fallback, no bundled extensions. */
    const val DECODER_HARDWARE = 1
    /** Software decoders preferred, for formats the device's hardware handles badly. */
    const val DECODER_SOFTWARE = 2

    /** Buffer sizes offered in the built-in player's settings, in megabytes. */
    val PLAYER_BUFFER_OPTIONS = intArrayOf(30, 50, 80, 120, 160, 200)
    const val PLAYER_BUFFER_DEFAULT_MB = 50

    // Extension properties for SharedPreferences
    val Context.appPrefs: SharedPreferences
        get() = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)

    val Context.storagePrefs: SharedPreferences
        get() = getSharedPreferences(STORAGE_PREFERENCES, MODE_PRIVATE)

    val Context.lastPlayedPrefs: SharedPreferences
        get() = getSharedPreferences(APP_LAST_PLAYED, MODE_PRIVATE)

    val Context.defPrefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(this)

    // Extension properties for app settings
    var Context.appUrl: String
        get() = appPrefs.getString(APP_URL, BuildConfig.defaultAppUrl) ?: ""
        set(url) = appPrefs.edit { putString(APP_URL, url) }

    var Context.appPlayer: String?
        get() = appPrefs.getString(APP_PLAYER, "")
        set(player) = appPrefs.edit { putString(APP_PLAYER, player) }

    var Context.tvPlayer: String?
        get() = appPrefs.getString(IPTV_PLAYER, "")
        set(player) = appPrefs.edit { putString(IPTV_PLAYER, player) }

    // Keep the RCH socket alive while an external player is in front (default off)
    var Context.playerKeepConnection: Boolean
        get() = appPrefs.getBoolean(PLAYER_KEEP_CONN_KEY, false)
        set(value) = appPrefs.edit { putBoolean(PLAYER_KEEP_CONN_KEY, value) }

    var Context.lampaSource: String
        get() = appPrefs.getString(LAMPA_SOURCE, "tmdb") ?: "tmdb"
        set(source) = appPrefs.edit {putString(LAMPA_SOURCE, source) }

    var Context.appLang: String
        get() = appPrefs.getString(APP_LANG, Locale.getDefault().language)
            ?: Locale.getDefault().language
        set(lang) = appPrefs.edit { putString(APP_LANG, lang) }

    var Context.tmdbApiUrl: String
        get() = appPrefs.getString(TMDB_API_KEY, TMDB.APIURL) ?: TMDB.APIURL
        set(url) = appPrefs.edit {putString(TMDB_API_KEY, url) }

    var Context.tmdbImgUrl: String
        get() = appPrefs.getString(TMDB_IMG_KEY, TMDB.IMGURL) ?: TMDB.IMGURL
        set(url) = appPrefs.edit {putString(TMDB_IMG_KEY, url) }

    /** Built-in player: overlay clock in the top corner. */
    var Context.playerShowClock: Boolean
        get() = appPrefs.getBoolean(PLAYER_CLOCK_KEY, true)
        set(value) = appPrefs.edit { putBoolean(PLAYER_CLOCK_KEY, value) }

    /** Built-in player: which decoders to use — one of the DECODER_* constants. */
    var Context.playerDecoderMode: Int
        get() = appPrefs.getInt(PLAYER_DECODER_KEY, DECODER_COMBINED)
        set(value) = appPrefs.edit { putInt(PLAYER_DECODER_KEY, value) }

    /** Built-in player: how much of the stream ExoPlayer keeps buffered, in megabytes. */
    var Context.playerBufferMb: Int
        get() = appPrefs.getInt(PLAYER_BUFFER_KEY, PLAYER_BUFFER_DEFAULT_MB)
        set(value) = appPrefs.edit { putInt(PLAYER_BUFFER_KEY, value) }

    val Context.firstRun: Boolean
        get() {
            val lastRunVersion = defPrefs.getString("last_run_version", "")
            val isFirstRun = BuildConfig.VERSION_NAME != lastRunVersion
            if (isFirstRun) defPrefs.edit {putString("last_run_version", BuildConfig.VERSION_NAME)
            }
            return isFirstRun
        }

    /**
     * A property to get or set the migration status in SharedPreferences.
     * - `true`: Migration is enabled.
     * - `false`: Migration is disabled or not set.
     */
    var Context.migrate: Boolean
        get() = defPrefs.getBoolean(MIGRATE_KEY, false) // Default to false if key doesn't exist
        set(enabled) {
            defPrefs.edit().apply {
                if (enabled) {
                    putBoolean(MIGRATE_KEY, true)
                } else {
                    remove(MIGRATE_KEY)
                }
                apply() // Save changes asynchronously
            }
        }

    // Data class to represent URL history entries
    data class InputHistory(
        val input: String,
        val timestamp: Long
    )

    // Extension property to get URL history
    val Context.urlHistory: List<String>
        get() {
            val json = defPrefs.getString(APP_URL_HISTORY, "[]")
            return parseUrlHistory(json)
                .sortedByDescending { it.timestamp } // Sort by timestamp in descending order
                .map { it.input } // Extract the URL strings
        }

    // Extension function to add a URL to history
    fun Context.addUrlHistory(url: String) {
        val history = parseUrlHistory(defPrefs.getString(APP_URL_HISTORY, "[]"))
            .filter { it.input != url } // Remove duplicates
            .toMutableList()
        history.add(InputHistory(url, System.currentTimeMillis())) // Add new entry
        saveUrlHistory(history)
    }

    // Extension function to remove a URL from history
    fun Context.remUrlHistory(url: String) {
        val history = parseUrlHistory(defPrefs.getString(APP_URL_HISTORY, "[]"))
            .filter { it.input != url } // Remove the specified URL
        saveUrlHistory(history)
    }

    // Extension function to clear URL history
    fun Context.clearUrlHistory() {
        saveUrlHistory(emptyList())
    }

    // Helper function to parse URL history from JSON
    private fun parseUrlHistory(json: String?): List<InputHistory> {
        return try {
            Gson().fromJson(json, Array<InputHistory>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList() // Return an empty list if parsing fails
        }
    }

    // Helper function to save URL history to SharedPreferences
    private fun Context.saveUrlHistory(history: List<InputHistory>) {
        val json = Gson().toJson(history)
        defPrefs.edit {
            putString(APP_URL_HISTORY, json)
        }
    }

    // Generic function to get preferences
    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String, def: T): T {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(App.context)
            prefs.all[name] as? T ?: def
        } catch (_: Exception) {
            def
        }
    }
}