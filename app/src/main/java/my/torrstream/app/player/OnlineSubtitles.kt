package my.torrstream.app.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * «Онлайн субтитры» встроенного плеера — то же, что OnlineSubs веб-плеера (public/js/player.js):
 * TheSubtitleDB ищет прямо по id TMDB, OpenSubtitles через strem.io — по IMDb, который отдаёт
 * TheSubtitleDB. Что искать (id, тип, сезон, серия), веб присылает на каждую серию плейлиста
 * полем online_subs.
 *
 * Серии в TheSubtitleDB — по IMDb самой серии (seasons[].episodes[].imdb): параметры
 * season/episode он молча не учитывает и отдаёт чужие серии.
 *
 * Всё сетевое здесь блокирующее — звать из Dispatchers.IO.
 */
object OnlineSubtitles {

    private const val TAG = "OnlineSubs"
    private const val TSDB_API = "https://api.thesubtitledb.org/v1/"
    private const val OS_STREMIO_API = "https://opensubtitles-v3.strem.io/subtitles/"
    private const val PER_LANG = 15
    private const val TIMEOUT_MS = 12_000
    private val FORMATS = setOf("srt", "vtt", "webvtt", "ass", "ssa")

    data class Lang(val code: String, val os: String)

    /** Порядок в списке — как в вебе: русские, украинские, английские. */
    val LANGS = listOf(Lang("ru", "rus"), Lang("uk", "ukr"), Lang("en", "eng"))

    fun lang(code: String?): Lang? {
        val c = code?.lowercase() ?: return null
        return LANGS.firstOrNull { it.code == c || it.os == c }
    }

    data class Target(val tmdbId: Int, val type: String, val season: Int?, val episode: Int?) {
        companion object {
            /** {tmdb_id, type, season, episode} из веба; null — веб не знает, что это за фильм. */
            fun fromJson(json: String?): Target? {
                if (json.isNullOrBlank()) return null
                return try {
                    val o = JSONObject(json)
                    val id = o.optInt("tmdb_id", 0)
                    val type = o.optString("type")
                    if (id <= 0 || (type != "movie" && type != "tv")) return null
                    val season = if (o.isNull("season")) null else o.optInt("season")
                    val episode = if (o.isNull("episode")) null else o.optInt("episode")
                    if (type == "tv" && (season == null || episode == null || episode <= 0)) return null
                    Target(id, type, season, episode)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    data class Sub(
        val lang: String,
        val url: String,
        val format: String,
        val encoding: String,
        val name: String,
        val hi: Boolean,
        val source: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lang", lang).put("url", url).put("format", format).put("encoding", encoding)
            .put("name", name).put("hi", hi).put("source", source)

        companion object {
            fun fromJson(o: JSONObject?): Sub? {
                val url = o?.optString("url")?.takeIf { it.isNotBlank() } ?: return null
                return Sub(
                    o.optString("lang"), url, o.optString("format", "srt"), o.optString("encoding"),
                    o.optString("name"), o.optBoolean("hi"), o.optString("source"),
                )
            }
        }
    }

    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    // region search

    fun search(t: Target): List<Sub> {
        val base = "${TSDB_API}by-tmdb/${t.type}/${t.tmdbId}"
        var imdb: String? = null
        val tsdbPath: String?
        if (t.type == "tv") {
            val info = fetchJson("$base?limit=1")
            imdb = info?.optJSONObject("title")?.optString("imdb")?.takeIf { it.isNotBlank() }
            tsdbPath = episodeImdb(info, t.season, t.episode)?.let { "${TSDB_API}by-imdb/$it" }
        } else tsdbPath = base

        val own = LANGS.map { l ->
            if (tsdbPath == null) emptyList()
            else {
                val data = fetchJson("$tsdbPath?lang=${l.code}&limit=$PER_LANG")
                if (imdb == null) {
                    imdb = data?.optJSONObject("title")?.optString("imdb")?.takeIf { it.isNotBlank() }
                }
                tsdbItems(data, l.code)
            }
        }
        val os = imdb?.let { id ->
            fetchJson(
                OS_STREMIO_API + (if (t.type == "tv") "series/$id:${t.season}:${t.episode}" else "movie/$id") + ".json"
            )
        }
        val osSubs = os?.optJSONArray("subtitles")

        val seen = HashSet<String>()
        val list = mutableListOf<Sub>()
        LANGS.forEachIndexed { i, l ->
            var count = 0
            for (s in own[i]) {
                if (count >= PER_LANG) break
                if (seen.add(s.url)) { list += s; count++ }
            }
            for (k in 0 until (osSubs?.length() ?: 0)) {
                if (count >= PER_LANG) break
                val s = osSubs!!.optJSONObject(k) ?: continue
                val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (lang(s.optString("lang")) != l || !seen.add(url)) continue
                val name = s.optString("subtitleFileName").ifBlank { s.optString("movieReleaseName") }.trim()
                // strem.io сам перекодирует в UTF-8 (subencoding-stremio-utf8 в адресе)
                list += Sub(l.code, url, "srt", "utf-8", name, false, "OpenSubtitles")
                count++
            }
        }
        return list
    }

    private fun episodeImdb(info: JSONObject?, season: Int?, episode: Int?): String? {
        val seasons = info?.optJSONArray("seasons") ?: return null
        for (i in 0 until seasons.length()) {
            val eps = seasons.optJSONObject(i)?.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val e = eps.optJSONObject(j) ?: continue
                if (e.optInt("season", -1) == season && e.optInt("episode", -1) == episode) {
                    return e.optString("imdb").takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    private fun tsdbItems(data: JSONObject?, lang: String): List<Sub> {
        val items = data?.optJSONObject("subtitles")?.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Sub>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val fmt = s.optString("format", "srt").lowercase()
            val url = s.optString("download_url").takeIf { it.isNotBlank() } ?: continue
            if (fmt !in FORMATS) continue
            out += Sub(
                lang, url, fmt, s.optString("encoding"), s.optString("release_name").trim(),
                s.optBoolean("hearing_impaired"), "TheSubtitleDB",
            )
        }
        return out
    }

    private fun fetchJson(url: String): JSONObject? = try {
        fetchBytes(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }
    } catch (e: Exception) {
        Log.w(TAG, "$url: ${e.message}")
        null
    }

    private fun fetchBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "TorrStream")
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection?.disconnect()
        }
    }

    // endregion

    // region file

    /** Скачать и разобрать; пустой список — файл не годится. Исключение — не скачался. */
    fun download(sub: Sub): List<Cue> {
        val bytes = fetchBytes(sub.url) ?: throw java.io.IOException("HTTP error")
        return parse(decode(bytes, sub.encoding))
    }

    /**
     * Строгий UTF-8, не вышло — объявленная кодировка, иначе cp1251. Объявленной не верим
     * первой: TheSubtitleDB пишет «CP1251» у исходника, а отдаёт файл уже в UTF-8.
     */
    fun decode(bytes: ByteArray, declared: String?): String {
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
        }
        val cp = Regex("^(?:cp|windows-?)(\\d{3,4})$")
            .find(declared.orEmpty().lowercase().replace(Regex("[_\\s]"), "-"))?.groupValues?.get(1)
        val charset = try {
            Charset.forName("windows-${cp ?: "1251"}")
        } catch (_: Exception) {
            Charset.forName("windows-1251")
        }
        return String(bytes, charset)
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\{[^}]*\\}"), "").replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\\\N", RegexOption.IGNORE_CASE), "\n").replace("\\h", " ")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(Regex("[ \\t]+\\n"), "\n").trim()

    /** «1:02:03,456», «02:03.456», «0:01:02.34» → мс */
    private fun timeMs(v: String): Long {
        val p = v.trim().replace(',', '.').split(':')
        var sec = p.last().toDoubleOrNull() ?: 0.0
        if (p.size > 1) sec += (p[p.size - 2].toIntOrNull() ?: 0) * 60
        if (p.size > 2) sec += (p[p.size - 3].toIntOrNull() ?: 0) * 3600
        return (sec * 1000).toLong()
    }

    /** SRT / WebVTT / ASS → реплики по возрастанию начала */
    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<Cue>()
        if (Regex("^\\s*\\[Script Info]", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("\\n\\[Events]", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            var fmt: List<String>? = null
            for (line in text.split('\n')) {
                if (line.startsWith("Format:", true)) {
                    fmt = line.substring(7).split(',').map { it.trim().lowercase() }
                } else if (fmt != null && line.startsWith("Dialogue:", true)) {
                    val f = fmt
                    val parts = line.substring(9).split(',', limit = f.size)
                    val get = { name: String -> f.indexOf(name).takeIf { it in parts.indices }?.let { parts[it] } }
                    val body = clean(get("text").orEmpty())
                    val start = get("start") ?: continue
                    val end = get("end") ?: continue
                    if (body.isNotEmpty()) cues += Cue(timeMs(start), timeMs(end), body)
                }
            }
        } else {
            val timing = Regex("([\\d:]+[,.]\\d+)\\s*-->\\s*([\\d:]+[,.]\\d+)")
            for (block in text.split(Regex("\\n[ \\t]*\\n"))) {
                val lines = block.split('\n')
                for (j in lines.indices) {
                    val m = timing.find(lines[j]) ?: continue
                    val body = clean(lines.drop(j + 1).joinToString("\n"))
                    if (body.isNotEmpty()) cues += Cue(timeMs(m.groupValues[1]), timeMs(m.groupValues[2]), body)
                    break
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /** Текст на экране в момент positionMs; пусто — реплики нет. */
    fun textAt(cues: List<Cue>, positionMs: Long): String {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        val parts = ArrayList<String>(2)
        var i = lo - 1
        while (i >= 0 && i >= lo - 8) {
            if (cues[i].endMs > positionMs) parts.add(0, cues[i].text)
            i--
        }
        return parts.joinToString("\n")
    }

    // endregion

    // region store

    /**
     * Выбор помним, как веб: для файла — субтитры и сдвиг, для раздачи — язык (следующая серия
     * сама включит первые субтитры того же языка). Ключ файла — «hash_index».
     */
    class Store(context: Context) {
        private val prefs = context.getSharedPreferences("online_subs", Context.MODE_PRIVATE)

        private fun read(): JSONObject = try {
            JSONObject(prefs.getString("store", null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

        private fun JSONObject.section(name: String): JSONObject =
            optJSONObject(name) ?: JSONObject().also { put(name, it) }

        fun fileChoice(fileKey: String): Pair<Sub, Long>? {
            val f = read().optJSONObject("f")?.optJSONObject(fileKey) ?: return null
            val sub = Sub.fromJson(f.optJSONObject("item")) ?: return null
            return sub to f.optLong("offset_ms", 0L)
        }

        fun torrentLang(hash: String): String? =
            read().optJSONObject("t")?.optJSONObject(hash)?.optString("lang")?.takeIf { it.isNotBlank() }

        fun save(fileKey: String, hash: String, sub: Sub?, offsetMs: Long) {
            val st = read()
            val f = st.section("f")
            val t = st.section("t")
            val now = System.currentTimeMillis()
            if (sub != null) {
                f.put(fileKey, JSONObject().put("item", sub.toJson()).put("offset_ms", offsetMs).put("ts", now))
                t.put(hash, JSONObject().put("lang", sub.lang).put("ts", now))
            } else {
                f.remove(fileKey)
                t.remove(hash)
            }
            trim(f)
            trim(t)
            prefs.edit().putString("store", st.toString()).apply()
        }

        /** Старые записи — прочь, иначе строка растёт без конца */
        private fun trim(o: JSONObject) {
            if (o.length() <= MAX_ENTRIES) return
            val keys = o.keys().asSequence().toList()
                .sortedBy { o.optJSONObject(it)?.optLong("ts") ?: 0L }
            keys.take(keys.size - MAX_ENTRIES).forEach { o.remove(it) }
        }

        private companion object {
            const val MAX_ENTRIES = 300
        }
    }

    // endregion
}
