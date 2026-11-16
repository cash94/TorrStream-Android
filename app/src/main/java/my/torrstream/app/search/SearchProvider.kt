package my.torrstream.app.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import my.torrstream.app.App
import my.torrstream.app.R
import my.torrstream.app.tmdb.TMDB
import my.torrstream.app.tmdb.models.entity.Entity
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Результаты TorrStream в глобальном поиске Android TV («Аватар» в микрофон пульта).
 *
 * Система спрашивает content://my.torrstream.app.atvsearch/search_suggest_query
 * (res/xml/searchable.xml), запрос — в selectionArgs[0] или следующем сегменте пути.
 * Отдаём фильмы и сериалы из TMDB; выбранный результат приходит в MainActivity
 * интентом GLOBALSEARCH: data = content://…/video/<tmdb id>, а в
 * SearchManager.EXTRA_DATA_KEY — JSON {media_type, title, poster}.
 *
 * Работает на Android TV с Leanback-лаунчером; Google TV сторонние результаты
 * в выдачу не берёт.
 */
class SearchProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Запрос может прийти раньше Application.onCreate (процесс разбудил сам поиск)
        context?.let { App.ensureContext(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val segments = uri.pathSegments
        val at = segments.indexOf(SearchManager.SUGGEST_URI_PATH_QUERY)
        if (at < 0) return null
        val query = (selectionArgs?.firstOrNull() ?: segments.getOrNull(at + 1))
            ?.trim().orEmpty()
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 40) ?: 20
        val cursor = MatrixCursor(COLUMNS)
        if (query.length < 2) return cursor

        val ctx = context ?: return cursor
        val movie = ctx.getString(R.string.global_search_movie)
        val series = ctx.getString(R.string.global_search_series)
        val results = try {
            rank(TMDB.search(query), query)
        } catch (e: Exception) {
            Log.w(TAG, "search '$query' failed", e)
            emptyList()
        }
        Log.d(TAG, "search '$query' -> ${results.size}")

        results.take(limit).forEachIndexed { i, e ->
            val id = e.id ?: return@forEachIndexed
            val type = e.media_type ?: return@forEachIndexed
            val title = e.title?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val year = e.year.orEmpty()
            val subtitle = listOf(if (type == "tv") series else movie, year)
                .filter { it.isNotBlank() }.joinToString(" · ")
            val extra = JSONObject().apply {
                put("media_type", type)
                put("title", title)
                put("poster", e.poster_path.orEmpty())
            }.toString()
            val rating = e.vote_average?.takeIf { it > 0 }
            cursor.addRow(arrayOf<Any?>(
                i,                                          // _ID
                title,                                      // TEXT_1
                subtitle,                                   // TEXT_2
                e.poster_path?.takeIf { it.isNotBlank() },  // RESULT_CARD_IMAGE
                "video/mp4",                                // CONTENT_TYPE
                0,                                          // IS_LIVE
                year.toIntOrNull(),                         // PRODUCTION_YEAR
                if (rating != null) RATING_PERCENTAGE else null,
                rating?.let { (it * 10).roundToInt().toFloat() },
                id.toString(),                              // INTENT_DATA_ID
                extra,                                      // INTENT_EXTRA_DATA
            ))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ) = 0

    companion object {
        private const val TAG = "SearchProvider"

        /**
         * Порядок TMDB в search/multi плохо подходит для голоса: на «Аватар» фильм
         * 2009 года был восьмым. Сначала точное совпадение названия (своего или
         * оригинального), потом начинающиеся с запроса, внутри — по популярности.
         */
        private fun rank(list: List<Entity>, query: String): List<Entity> {
            val q = query.lowercase()
            fun group(e: Entity): Int {
                val names = listOfNotNull(e.title, e.original_title).map { it.lowercase() }
                return when {
                    names.any { it == q } -> 0
                    names.any { it.startsWith(q) } -> 1
                    else -> 2
                }
            }
            return list.sortedWith(compareBy<Entity> { group(it) }
                .thenByDescending { it.popularity ?: 0.0 })
        }

        /** Rating.RATING_PERCENTAGE: TMDB даёт 0–10, лаунчеру — проценты. */
        private const val RATING_PERCENTAGE = 6

        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_IS_LIVE,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_RATING_STYLE,
            SearchManager.SUGGEST_COLUMN_RATING_SCORE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
        )
    }
}
