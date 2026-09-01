package my.torrstream.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import my.torrstream.app.browser.Browser
import my.torrstream.app.helpers.Helpers.debugLog
import my.torrstream.app.helpers.Prefs.appLang
import my.torrstream.app.helpers.Prefs.storagePrefs

class AndroidJS(private val mainActivity: MainActivity, private val browser: Browser) {

    private val store: SharedPreferences = App.context.storagePrefs

    // Local properties
    private var lastEventHash: String? = null
    private var keys: Array<String?>? = null
    private var values: Array<String?>? = null
    private var dumped = false

    companion object {
        // Constants
        private const val TAG = "AndroidJS"
        private const val UPDATE_DELAY = 5000L // in ms, wait before update TV channel
    }

    @JavascriptInterface
    fun storageChange(json: String?) {
        val hash = json.hashCode().toString()
        if (hash == lastEventHash) {
            debugLog(TAG, "Ignoring duplicate storage change event: $json")
            return
        }
        lastEventHash = hash
        json?.let {
            val eo: JSONObject = if (json == "\"\"") {
                JSONObject()
            } else {
                JSONObject(json)
            }
            if (!eo.has("name") || !eo.has("value")) return

            when (eo.optString("name")) {
//                "activity" -> {
//                    MainActivity.lampaActivity = eo.optString("value", "{}")
//                    debugLog(TAG, "lampaActivity stored: ${MainActivity.lampaActivity}")
//                }

//                "player_timecode" -> {
//                    MainActivity.playerTimeCode = eo.optString("value", MainActivity.playerTimeCode)
//                    debugLog(TAG, "playerTimeCode stored: ${MainActivity.playerTimeCode}")
//                }

//                "playlist_next" -> {
//                    MainActivity.playerAutoNext = eo.optString("value", "true") == "true"
//                    debugLog(TAG, "playerAutoNext stored: ${MainActivity.playerAutoNext}")
//                }

                "language" -> {
                    val newLang = eo.optString("value", "ru")
                    if (newLang != "undefined" && mainActivity.appLang != newLang) {
                        App.setAppLanguage(mainActivity, newLang)
                        // mainActivity.appLang = newLang
                        // mainActivity.runOnUiThread { mainActivity.recreate() }
                        debugLog(TAG, "language changed to $newLang")
                    } else {
                        debugLog(TAG, "language not changed [${mainActivity.appLang}]")
                    }
                }

//                "source" -> {
//                    mainActivity.lampaSource = eo.optString("value", mainActivity.lampaSource)
//                    debugLog(TAG, "lampaSource stored: ${mainActivity.lampaSource}")
//                }

                else -> { // no op
                }
            }
        }
    }


    @JavascriptInterface
    @Throws(JSONException::class)
    fun openTorrentLink(url: String, jsonString: String): Boolean {
        val jsonData = if (jsonString == "\"\"") JSONObject() else JSONObject(jsonString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (url.startsWith("magnet", ignoreCase = true)) {
                data = url.toUri()
            } else {
                setDataAndType(url.toUri(), "application/x-bittorrent")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addCategory(Intent.CATEGORY_BROWSABLE)

            jsonData.optString("title").takeIf { it.isNotEmpty() }?.let { title ->
                putExtra("title", title)
                putExtra("displayName", title)
                putExtra("forcename", title)
            }

            jsonData.optString("poster").takeIf { it.isNotEmpty() }?.let { poster ->
                putExtra("poster", poster)
            }

            jsonData.optString("media").takeIf { it.isNotEmpty() }?.let { category ->
                putExtra("category", category)
            }

            jsonData.optJSONObject("data")?.let { dataObj ->
                putExtra("data", dataObj.toString())
            }
        }

        mainActivity.runOnUiThread {
            try {
                mainActivity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open torrent link", e)
                App.toast(R.string.no_torrent_activity_found, true)
            }
        }

        return true
    }

    /**
     * Builds a `{"movie": {...LampaCard fields...}}` blob from the player payload so
     * MainActivity can track per-item playback state (and, on Android TV, publish a
     * "Continue Watching" row) — instead of everything collapsing onto one shared slot.
     */
    private fun buildActivityJson(jsonObject: JSONObject, link: String): String {
        val card = JSONObject().apply {
            val title = jsonObject.optString("title", "")
            putSafe("id", jsonObject.optString("id").ifEmpty { link })
            putSafe("title", title)
            putSafe("name", title)
            putSafe("type", jsonObject.optString("type").ifEmpty { "movie" })
            jsonObject.optString("poster").takeIf { it.isNotEmpty() }?.let { putSafe("img", it) }
        }
        return JSONObject().apply { putSafe("movie", card) }.toString()
    }

    @JavascriptInterface
    fun openPlayer(link: String, jsonStr: String) {
        debugLog(TAG, "openPlayer: $link json:$jsonStr")
        val jsonObject = try {
            JSONObject(jsonStr.ifEmpty { "{}" }).apply {
                if (!has("url")) {
                    putSafe("url", link)
                }
            }
        } catch (_: Exception) {
            JSONObject().apply { putSafe("url", link) }
        }

        val activityJson = buildActivityJson(jsonObject, link)
        MainActivity.lampaActivity = activityJson
        mainActivity.runOnUiThread { mainActivity.runPlayer(jsonObject, "", activityJson) }
    }

    /** Lets the user pick and persist a default external player from the web settings screen. */
    @JavascriptInterface
    fun choosePlayer() {
        mainActivity.runOnUiThread { mainActivity.showChoosePlayerDialog() }
    }

    // https://stackoverflow.com/a/41560207
    // https://copyprogramming.com/howto/android-webview-savestate
    @JavascriptInterface
    @Synchronized
    fun dump() {
        check(!dumped) { "already dumped" }
        val map = store.all
        val size = map?.size ?: 0
        keys = arrayOfNulls(size)
        values = arrayOfNulls(size)
        for ((cur, key) in map!!.keys.withIndex()) {
            keys!![cur] = key
            values!![cur] = (map[key] as String?)!!
        }
        dumped = true
    }

    @JavascriptInterface
    @Synchronized
    fun size(): Int {
        check(dumped) { "dump() first" }
        return keys!!.size
    }

    @JavascriptInterface
    @Synchronized
    fun key(i: Int): String? {
        check(dumped) { "dump() first" }
        return keys!![i]
    }

    @JavascriptInterface
    @Synchronized
    fun value(i: Int): String? {
        check(dumped) { "dump() first" }
        return values!![i]
    }

    @JavascriptInterface
    @Synchronized
    operator fun get(key: String?): String? {
        return store.getString(key, null)
    }

    @JavascriptInterface
    @Synchronized
    operator fun set(key: String?, value: String?) {
        check(!dumped) { "already dumped" }
        store.edit { putString(key, value) }
    }

    @JavascriptInterface
    @Synchronized
    fun clear() {
        store.edit { clear() }
        keys = null
        values = null
        dumped = false
    }

    @Synchronized
    override fun toString(): String {
        return store.all.toString()
    }

    private fun JSONObject.putSafe(key: String, value: Any) = try {
        put(key, value)
    } catch (_: JSONException) { /* Ignore */
    }
}
