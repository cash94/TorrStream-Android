package my.torrstream.app.channels

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import my.torrstream.app.App
import my.torrstream.app.BuildConfig
import my.torrstream.app.MainActivity
import my.torrstream.app.R
import my.torrstream.app.helpers.Helpers.isTvContentProviderAvailable
import my.torrstream.app.helpers.Prefs.appUrl
import my.torrstream.app.helpers.ServerCookies
import my.torrstream.app.net.HttpHelper

/**
 * Каналы главного экрана Android TV: ряды «Главной» и категории «Каталога» веб-интерфейса.
 *
 * Каждый ряд — отдельный канал (PreviewChannel), каждая карточка — PreviewProgram.
 * Нажатие на карточку запускает MainActivity с [EXTRA_CARD], и та открывает карточку
 * поверх «Главной» (см. MainActivity.openChannelCard), так что «Назад» из неё ведёт на главную.
 *
 * Показывать все каналы сразу система не даёт: без вопроса пользователю видимым становится
 * только первый канал приложения. Остальные включаются в настройках лаунчера или из меню
 * приложения («Каналы на главном экране»), которое спрашивает систему по одному каналу.
 *
 * Google TV ([isGoogleTv]) показывает от приложения только этот первый канал: ни запроса
 * «Показать канал?», ни настройки каналов у него нет. Поэтому там канал один — общий: в
 * нём карточки выбранных в меню категорий (до [GTV_MAX_KEYS]), поровну на [ITEMS_PER_CHANNEL]
 * мест. Одна категория — 30 её карточек и её название, десять — по три, канал «TorrStream».
 */
@RequiresApi(Build.VERSION_CODES.O)
object Channels {
    private const val TAG = "Channels"

    /** Extra с JSON карточки: {id, media_type, title, poster_path}. */
    const val EXTRA_CARD = "tvChannelCard"

    private const val ITEMS_PER_CHANNEL = 30
    private const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L // сервер обновляет подборки раз в сутки
    private const val DEFAULT_IMAGE_HOST = "tsimg.torrstream.online"
    private const val POSTER_SIZE = "w342"

    private const val PREF_UPDATED_AT = "updated_at"
    private const val PREF_SERVER = "server"
    private const val PREF_DISABLED = "disabled"
    private const val PREF_GTV_KEYS = "gtv_keys"

    /** Сколько категорий можно сложить в общий канал Google TV. */
    const val GTV_MAX_KEYS = 10
    private const val GOOGLE_TV_LAUNCHER = "com.google.android.apps.tv.launcherx"
    private const val MIXED_CHANNEL_NAME = "TorrStream"

    enum class Source { TMDB, KINOPOISK, CATALOG, RUS }

    class Def(val key: String, val name: String, val source: Source, val mediaType: String = "movie") {
        val providerId get() = "ch_$key"

        fun path(): String = when (source) {
            Source.TMDB -> "/api/tmdb/collection?preset=$key"
            Source.KINOPOISK -> "/api/kinopoisk/collection?preset=$key"
            Source.CATALOG -> "/api/catalog/$key/items?from=0&limit=$ITEMS_PER_CHANNEL"
            Source.RUS -> "/api/rus/items?from=0&limit=$ITEMS_PER_CHANNEL"
        }
    }

    /**
     * Порядок и названия — как в веб-интерфейсе: HOME_ROWS в public/js/home.js (без
     * «Продолжить просмотр» — для него есть системный ряд Watch Next, см. [WatchNext]) и
     * CATALOG_CONFIG в public/js/catalog.js (без «Истории» и «Избранного»: они живут в
     * браузере). Поменялись ряды там — поправить и здесь.
     */
    val DEFS: List<Def> = listOf(
        Def("trending_week", "В тренде на этой неделе", Source.TMDB),
        Def("pop_streaming", "Что популярно · Онлайн", Source.TMDB),
        Def("pop_ontv", "Что популярно · По ТВ", Source.TMDB),
        Def("pop_rent", "Что популярно · Напрокат", Source.TMDB),
        Def("pop_theatres", "Что популярно · В кинотеатрах", Source.TMDB),
        Def("top_movies", "Топ рейтинга: фильмы", Source.TMDB),
        Def("top_tv", "Топ рейтинга: сериалы", Source.TMDB, "tv"),
        Def("popular_movies", "Популярные фильмы", Source.TMDB),
        Def("popular_tv", "Популярные сериалы", Source.TMDB, "tv"),
        Def("kp_zombie", "Кинопоиск · Про зомби", Source.KINOPOISK),
        Def("kp_vampire", "Кинопоиск · Про вампиров", Source.KINOPOISK),
        Def("kp_disaster", "Кинопоиск · Катастрофы", Source.KINOPOISK),
        Def("kp_kids", "Кинопоиск · Мультфильмы детям", Source.KINOPOISK),
        Def("movie", "Фильмы", Source.CATALOG),
        Def("tv", "Сериалы", Source.CATALOG, "tv"),
        Def("cartoons", "Мультфильмы", Source.CATALOG),
        Def("cartoons_tv", "Мультсериалы", Source.CATALOG, "tv"),
        Def("anime", "Аниме", Source.CATALOG, "tv"),
        Def("rus", "Русские", Source.RUS),
        Def("kp_popular", "Кинопоиск · Популярное", Source.CATALOG),
        Def("kp_pop_movies", "Кинопоиск · Популярные фильмы", Source.CATALOG),
        Def("kp_pop_series", "Кинопоиск · Популярные сериалы", Source.CATALOG, "tv"),
        Def("kp_top250", "Кинопоиск · Топ 250 фильмов", Source.CATALOG),
        Def("kp_top250_tv", "Кинопоиск · Топ 250 сериалов", Source.CATALOG, "tv"),
        Def("kp_family", "Кинопоиск · Семейное", Source.CATALOG),
        Def("kp_comics", "Кинопоиск · Комиксы", Source.CATALOG),
        Def("kp_love", "Кинопоиск · Про любовь", Source.CATALOG),
        Def("quadhd", "Фильмы в 4K", Source.CATALOG),
        Def("legends", "Лучшие фильмы", Source.CATALOG),
    )

    /** Этот канал система делает видимым без вопроса пользователю. */
    private val DEFAULT_KEY = DEFS.first().key

    private class Card(
        val id: String,
        val mediaType: String,
        val title: String,
        val poster: String,
        val overview: String?,
        val year: String?,
        /** Категория карточки в общем канале Google TV — пишется в описание. */
        val category: String? = null,
    ) {
        fun withCategory(name: String) = Card(id, mediaType, title, poster, overview, year, name)
    }

    private val prefs get() = App.context.getSharedPreferences("tv_channels", MODE_PRIVATE)

    /**
     * Умеет ли устройство каналы: Android 8+ с системным TvProvider, к которому нас пускают.
     * На телефонах, старых ТВ-приставках и части «не гугловых» прошивок провайдера нет.
     */
    val isSupported: Boolean
        get() = isTvContentProviderAvailable && try {
            App.context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(TvContractCompat.Channels._ID), null, null, null
            )?.use { true } == true
        } catch (e: Exception) {
            Log.w(TAG, "TvProvider недоступен", e)
            false
        }

    /**
     * Google TV: его лаунчер — домашний экран устройства. Классический лаунчер Android TV
     * (com.google.android.tvlauncher) и лаунчеры других прошивок сюда не попадают.
     */
    val isGoogleTv: Boolean
        get() = (BuildConfig.DEBUG && prefs.getBoolean("debug_force_google_tv", false)) || try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            App.context.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == GOOGLE_TV_LAUNCHER
        } catch (e: Exception) {
            false
        }

    // ==================== GOOGLE TV: ОБЩИЙ КАНАЛ ====================

    /** Категории общего канала Google TV в порядке [DEFS]. По умолчанию — как раньше, «В тренде». */
    fun googleTvKeys(): List<String> {
        val saved = prefs.getString(PREF_GTV_KEYS, null)
            ?.split(',')?.filter { key -> DEFS.any { it.key == key } }
        return saved?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_KEY)
    }

    fun setGoogleTvKeys(keys: List<String>) {
        val ordered = DEFS.map { it.key }.filter { it in keys }.take(GTV_MAX_KEYS)
        prefs.edit { putString(PREF_GTV_KEYS, ordered.ifEmpty { listOf(DEFAULT_KEY) }.joinToString(",")) }
    }

    /**
     * Один канал из нескольких категорий. Это всегда канал [DEFAULT_KEY] — первый созданный
     * приложением, единственный, что Google TV показывает: новый канал он бы уже не показал.
     * Карточки идут по кругу (первая каждой категории, вторая каждой…), чтобы в видимой части
     * ряда были все категории; повторы фильма между категориями пропускаются.
     */
    private fun updateGoogleTv(server: String, imageHost: String, published: Map<String, Long>): Int {
        val defs = googleTvKeys().mapNotNull { key -> DEFS.firstOrNull { it.key == key } }
        val perCategory = ITEMS_PER_CHANNEL / defs.size.coerceAtLeast(1)
        val mixed = defs.size > 1

        val lists = defs.mapNotNull { def ->
            val cards = try {
                fetchCards(server, def, imageHost)
            } catch (e: Exception) {
                Log.w(TAG, "${def.key}: ${e.message}")
                null
            } ?: return@mapNotNull null
            if (mixed) cards.map { it.withCategory(def.name) } else cards
        }
        if (lists.isEmpty()) return 0

        val seen = mutableSetOf<String>()
        val picked = lists.map { cards ->
            cards.filter { seen.add("${it.mediaType}:${it.id}") }.take(perCategory)
        }
        val cards = mutableListOf<Card>()
        for (i in 0 until perCategory) picked.forEach { list -> list.getOrNull(i)?.let { cards += it } }
        if (cards.isEmpty()) return 0

        val defaultDef = DEFS.first { it.key == DEFAULT_KEY }
        val channelId = published[DEFAULT_KEY] ?: createChannel(defaultDef) ?: return 0
        renameChannel(channelId, if (mixed) MIXED_CHANNEL_NAME else defs.first().name)
        syncPrograms(channelId, defaultDef, cards)

        // Остальные каналы Google TV не показывает — незачем их и обновлять
        published.filterKeys { it != DEFAULT_KEY }.values.forEach { deleteChannel(it) }
        return 1
    }

    private fun renameChannel(channelId: Long, name: String) {
        try {
            App.context.contentResolver.update(
                TvContractCompat.buildChannelUri(channelId),
                ContentValues().apply {
                    put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, name)
                    put(TvContractCompat.Channels.COLUMN_DESCRIPTION, name)
                }, null, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Канал $channelId не переименован", e)
        }
    }

    // ==================== ВКЛЮЧЁННЫЕ КАНАЛЫ ====================

    /** Каналы, которые пользователь убрал из меню приложения: их мы не публикуем. */
    fun disabledKeys(): Set<String> = prefs.getStringSet(PREF_DISABLED, emptySet()) ?: emptySet()

    fun setDisabled(key: String, disabled: Boolean) {
        val set = disabledKeys().toMutableSet()
        if (disabled) set += key else set -= key
        prefs.edit { putStringSet(PREF_DISABLED, set) }
    }

    private class Published(val id: Long, val browsable: Boolean)

    /**
     * Наши каналы в TvProvider по ключу. Колонки читаем сами: PreviewChannel.fromCursor
     * из tvprovider 1.0.0 падает на канале без описания (NPE в setDescription).
     */
    private fun queryChannels(): Map<String, Published> {
        val map = mutableMapOf<String, Published>()
        App.context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(
                TvContractCompat.Channels._ID,
                TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.Channels.COLUMN_BROWSABLE
            ), null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val pid = cursor.getString(1) ?: continue
                if (!pid.startsWith("ch_")) continue
                map[pid.removePrefix("ch_")] = Published(cursor.getLong(0), cursor.getInt(2) == 1)
            }
        }
        return map
    }

    /** key → id канала в TvProvider (только уже опубликованные). */
    fun publishedChannels(): Map<String, Long> = queryChannels().mapValues { it.value.id }

    /** Каналы, которые лаунчер сейчас показывает (флаг browsable ставит только система). */
    fun browsableChannels(): Set<String> = queryChannels().filterValues { it.browsable }.keys

    // ==================== ОБНОВЛЕНИЕ ====================

    /**
     * Публикует каналы и их карточки. Сетевой, звать не с главного потока.
     * Без [force] обновляет не чаще раза в [REFRESH_INTERVAL_MS] — Scheduler зовёт
     * нас на каждой загрузке страницы и каждые 15 минут.
     * Возвращает, свежие ли теперь каналы (для тоста пункта меню «Обновить каналы»).
     */
    fun update(force: Boolean = false): Boolean {
        if (!isSupported) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Каналы не поддерживаются устройством")
            return false
        }
        val server = serverBase() ?: return false
        val now = System.currentTimeMillis()
        val fresh = now - prefs.getLong(PREF_UPDATED_AT, 0L) < REFRESH_INTERVAL_MS &&
                prefs.getString(PREF_SERVER, null) == server
        if (!force && fresh) return true

        try {
            val imageHost = fetchImageHost(server)
            val disabled = disabledKeys()
            val published = publishedChannels()
            var ok = 0

            if (isGoogleTv) {
                ok = updateGoogleTv(server, imageHost, published)
                if (ok > 0) prefs.edit {
                    putLong(PREF_UPDATED_AT, now)
                    putString(PREF_SERVER, server)
                }
                Log.i(TAG, "Google TV: общий канал ${if (ok > 0) "обновлён" else "не обновлён"}")
                return ok > 0
            }

            DEFS.forEach { def ->
                if (def.key in disabled) {
                    published[def.key]?.let { deleteChannel(it) }
                    return@forEach
                }
                val cards = try {
                    fetchCards(server, def, imageHost)
                } catch (e: Exception) {
                    Log.w(TAG, "${def.key}: ${e.message}")
                    null
                } ?: return@forEach // сеть подвела — старые карточки остаются как есть

                if (cards.isEmpty()) {
                    // Модуль не установлен или каталог пуст: пустой канал лаунчеру не нужен
                    published[def.key]?.let { deleteChannel(it) }
                    return@forEach
                }
                val channelId = published[def.key] ?: createChannel(def) ?: return@forEach
                syncPrograms(channelId, def, cards)
                ok++
            }

            // Каналы, которых больше нет в списке (переименовали ключ, убрали ряд)
            val known = DEFS.map { it.key }.toSet()
            published.filterKeys { it !in known }.values.forEach { deleteChannel(it) }

            if (ok > 0) prefs.edit {
                putLong(PREF_UPDATED_AT, now)
                putString(PREF_SERVER, server)
            }
            Log.i(TAG, "Каналы обновлены: $ok из ${DEFS.size}")
            return ok > 0
        } catch (e: Exception) {
            Log.e(TAG, "update failed", e)
            return false
        }
    }

    /**
     * Создаёт канал сразу, до первой загрузки карточек, — нужен меню приложения, чтобы
     * попросить систему показать его. Возвращает id канала.
     */
    fun ensureChannel(key: String): Long? {
        val def = DEFS.firstOrNull { it.key == key } ?: return null
        return publishedChannels()[key] ?: createChannel(def)
    }

    /** Убирает канал с главного экрана вместе с его карточками. */
    fun deleteChannel(key: String) {
        publishedChannels()[key]?.let { deleteChannel(it) }
    }

    /** Следующее update() пойдёт в сеть, даже если данные свежие. */
    fun invalidate() = prefs.edit { remove(PREF_UPDATED_AT) }

    // ==================== TvProvider ====================

    @SuppressLint("RestrictedApi")
    private fun createChannel(def: Def): Long? {
        val context = App.context
        val channel = PreviewChannel.Builder()
            .setDisplayName(def.name)
            .setDescription(def.name)
            .setInternalProviderId(def.providerId)
            .setAppLinkIntentUri(Uri.parse(openAppIntent().toUri(Intent.URI_INTENT_SCHEME)))
            .build()
        val uri = try {
            context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI, channel.toContentValues()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать канал ${def.key}", e)
            null
        } ?: return null
        val channelId = ContentUris.parseId(uri)

        try {
            BitmapFactory.decodeResource(context.resources, R.drawable.lampa_icon)?.let {
                ChannelLogoUtils.storeChannelLogo(context, channelId, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Логотип канала ${def.key} не сохранён", e)
        }
        // Первый канал система показывает сама; для остальных вызов ничего не делает
        if (def.key == DEFAULT_KEY) {
            try {
                TvContractCompat.requestChannelBrowsable(context, channelId)
            } catch (e: Exception) {
                Log.w(TAG, "requestChannelBrowsable(${def.key})", e)
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Канал ${def.key} создан: $channelId")
        return channelId
    }

    private fun deleteChannel(channelId: Long) {
        try {
            App.context.contentResolver.delete(
                TvContractCompat.buildChannelUri(channelId), null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось удалить канал $channelId", e)
        }
    }

    /**
     * Сверяет карточки канала с новым списком по internalProviderId: совпавшие обновляет,
     * новые добавляет, лишние удаляет. Полная пересборка заставила бы лаунчер мигать рядом.
     */
    @SuppressLint("RestrictedApi")
    private fun syncPrograms(channelId: Long, def: Def, cards: List<Card>) {
        val resolver = App.context.contentResolver
        val existing = mutableMapOf<String, Long>()
        resolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(
                TvContractCompat.PreviewPrograms._ID,
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            ), null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(1)?.let { existing[it] = cursor.getLong(0) }
            }
        }

        val seen = mutableSetOf<String>()
        cards.forEachIndexed { index, card ->
            val pid = "${def.key}:${card.mediaType}:${card.id}"
            if (!seen.add(pid)) return@forEachIndexed
            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(
                    if (card.mediaType == "tv") TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                    else TvContractCompat.PreviewPrograms.TYPE_MOVIE
                )
                .setTitle(card.title)
                .setPosterArtUri(Uri.parse(card.poster))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
                .setInternalProviderId(pid)
                .setWeight(cards.size - index) // лаунчер ставит левее карточку с большим весом
                .setIntentUri(Uri.parse(cardIntent(card).toUri(Intent.URI_INTENT_SCHEME)))
            val overview = card.overview?.takeIf { it.isNotBlank() }
            val description = when {
                card.category != null && overview != null -> "${card.category} · $overview"
                else -> card.category ?: overview
            }
            description?.let { builder.setDescription(it) }
            card.year?.let { builder.setReleaseDate(it) }
            val values = builder.build().toContentValues()

            try {
                val programId = existing[pid]
                if (programId != null) {
                    resolver.update(TvContractCompat.buildPreviewProgramUri(programId), values, null, null)
                } else {
                    resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Карточка $pid не записана", e)
            }
        }

        (existing.keys - seen).forEach { stale ->
            existing[stale]?.let {
                try {
                    resolver.delete(TvContractCompat.buildPreviewProgramUri(it), null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Карточка $stale не удалена", e)
                }
            }
        }
    }

    // ==================== ИНТЕНТЫ ====================

    private fun openAppIntent() = Intent(App.context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun cardIntent(card: Card): Intent {
        val json = JSONObject().apply {
            put("id", card.id)
            put("media_type", card.mediaType)
            put("title", card.title)
            put("poster", card.poster)
        }.toString()
        return Intent(App.context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // Уже запущенное приложение получает карточку в onNewIntent, а плеер поверх
            // него закрывается: карточка должна открыться сразу, не за плеером
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(EXTRA_CARD, json)
        }
    }

    // ==================== СЕТЬ ====================

    /** Адрес сервера — источник страницы (config.js берёт SERVER_URL так же). */
    private fun serverBase(): String? {
        val uri = Uri.parse(App.context.appUrl.trim())
        val scheme = uri.scheme ?: return null
        val authority = uri.encodedAuthority ?: return null
        return "$scheme://$authority"
    }

    private val client by lazy { HttpHelper.getOkHttpClient(15000) }

    private fun getJson(url: String): JSONObject? {
        // Cookie входа из WebView: при включённом на сервере входе без неё 401
        val builder = Request.Builder().url(url)
        ServerCookies.forUrl(url)?.let { builder.header("Cookie", it) }
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.d(TAG, "$url → ${response.code()}")
                return null
            }
            val body = response.body()?.string() ?: return null
            return JSONObject(body)
        }
    }

    /** Основное зеркало картинок TMDB — то же, что у веб-интерфейса (/api/client-config). */
    private fun fetchImageHost(server: String): String = try {
        getJson("$server/api/client-config")?.optJSONArray("tmdbImages")
            ?.optString(0)?.takeIf { it.isNotBlank() } ?: DEFAULT_IMAGE_HOST
    } catch (e: Exception) {
        DEFAULT_IMAGE_HOST
    }

    /** Пустой список — у ряда нет карточек (или модуль не установлен); null — ошибка сети. */
    private fun fetchCards(server: String, def: Def, imageHost: String): List<Card>? {
        val json = try {
            getJson(server + def.path())
        } catch (e: Exception) {
            Log.w(TAG, "${def.key}: $e")
            return null
        } ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        return parseCards(items, def, imageHost)
    }

    private fun parseCards(items: JSONArray, def: Def, imageHost: String): List<Card> {
        val cards = mutableListOf<Card>()
        for (i in 0 until items.length()) {
            if (cards.size >= ITEMS_PER_CHANNEL) break
            val item = items.optJSONObject(i) ?: continue
            val id = item.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            // Серверные каталоги кладут название в имя раздачи — как getCatalogItemTitle в catalog.js
            val torrentName = item.optJSONArray("torrent")?.optJSONObject(0)?.optString("name")
            val title = listOf(torrentName, item.optString("title"), item.optString("name"))
                .firstOrNull { !it.isNullOrBlank() } ?: continue
            val poster = posterUrl(item.optString("poster_path"), imageHost) ?: continue
            val date = item.optString("release_date").ifBlank { item.optString("first_air_date") }
            cards += Card(
                id = id,
                mediaType = item.optString("media_type").ifBlank { def.mediaType },
                title = title,
                poster = poster,
                overview = item.optString("overview").takeIf { it.isNotBlank() },
                year = Regex("(19|20)\\d{2}").find(date)?.value,
            )
        }
        return cards
    }

    /**
     * Постер через зеркало: image.tmdb.org из России открывается не везде, а картинку
     * лаунчер качает сам. Путь берём и из «/abc.jpg», и из полного URL TMDB.
     */
    private fun posterUrl(pathOrUrl: String?, imageHost: String): String? {
        val value = pathOrUrl?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val path = if (value.startsWith("http", ignoreCase = true)) {
            Regex("/t/p/[^/]+(/[^?#]+)").find(value)?.groupValues?.get(1) ?: return value
        } else {
            if (value.startsWith("/")) value else "/$value"
        }
        return "https://$imageHost/t/p/$POSTER_SIZE$path"
    }
}
