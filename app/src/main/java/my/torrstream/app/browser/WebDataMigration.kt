package my.torrstream.app.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File

/**
 * Перенос данных страницы (localStorage, IndexedDB, cookie) при смене движка WebView.
 *
 * Папки данных у движков разные (WebViewEngine.useOwnDataDirectory), а до Android 9 общая,
 * но откат на более старый Chromium её стирает. Копировать файлы баз между версиями
 * Chromium ненадёжно, поэтому данные переезжают на уровне страницы: перед перезапуском
 * работающий движок выгружает их скриптом в файл, а новый при первом запуске открывает
 * пустую страницу того же источника (BOOT_PATH — JSON, скриптов приложения там нет),
 * загружает данные обратно и только потом открывает приложение.
 *
 * Переносятся весь localStorage (настройки, вход, clientId) и все базы IndexedDB —
 * избранное и кэши; кэши — пока укладываются в [MAX_JSON_CHARS]: они наполнятся заново.
 */
object WebDataMigration {
    private const val TAG = "WebDataMigration"

    /** Имя объекта в JS: TSMigration.exported(…) / data() / imported(…) */
    const val BRIDGE = "TSMigration"

    /** Страница источника без скриптов приложения: на ней и идёт загрузка данных. */
    private const val BOOT_PATH = "/api/version"

    private const val FILE = "webview_migration.json"
    private const val PREFS = "webview_migration"
    private const val KEY_SOURCE = "source"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_COOKIES = "cookies"

    private const val EXPORT_TIMEOUT_MS = 15_000L
    private const val IMPORT_TIMEOUT_MS = 60_000L

    /** Предел выгрузки: строка идёт через мост JS целиком, на слабой приставке это память. */
    private const val MAX_JSON_CHARS = 8 * 1024 * 1024

    /** Срок перенесённых cookie: исходный WebView его не сообщает. */
    private const val COOKIE_MAX_AGE = 365L * 24 * 60 * 60

    /** Базы приложения — их ищем и там, где нет indexedDB.databases() (Chromium < 71). */
    private val KNOWN_DBS = listOf(
        "FavoritesDB", "HomeCacheDB", "PosterCacheDB",
        "TmdbDetailsCacheDB", "TmdbItemCacheDB", "CatalogFullCacheDB",
    )

    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ==================== МОСТ ====================

    @Volatile private var exportCallback: ((String?, String?) -> Unit)? = null
    @Volatile private var importData: String? = null
    @Volatile private var importCallback: ((JSONObject) -> Unit)? = null

    /**
     * Объект для addJavascriptInterface. Методы принимают вызовы, только пока идёт
     * выгрузка или загрузка: в WebView открываются и чужие страницы, им сюда нельзя.
     */
    val bridge = Bridge()

    class Bridge {
        @JavascriptInterface
        fun exported(origin: String?, json: String?) {
            exportCallback?.invoke(origin, json)
        }

        @JavascriptInterface
        fun exportFailed(message: String?) {
            Log.w(TAG, "export failed in page: $message")
            exportCallback?.invoke(null, null)
        }

        @JavascriptInterface
        fun data(): String = importData ?: "null"

        @JavascriptInterface
        fun imported(result: String?) {
            val cb = importCallback ?: return
            val json = runCatching { JSONObject(result ?: "") }
                .getOrElse { JSONObject().put("ok", false).put("error", result) }
            cb(json)
        }
    }

    // ==================== ВЫГРУЗКА ====================

    /**
     * Выгружает данные открытой страницы в файл. [source] — ключ движка, который сейчас
     * работает (WebViewEngine.runningKey): если новый движок не включится и запустится
     * этот же, загружать ничего не нужно. [onDone] — на главном потоке, true — выгружено.
     */
    fun export(ctx: Context, browser: Browser?, source: String, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        if (browser == null || browser.isDestroyed) {
            onDone(false)
            return
        }
        var finished = false
        var callback: ((String?, String?) -> Unit)? = null
        fun finish(ok: Boolean) {
            main.post {
                if (finished) return@post
                finished = true
                // Таймер этой выгрузки не должен снять обработчик следующей
                if (exportCallback === callback) exportCallback = null
                onDone(ok)
            }
        }
        callback = { origin, json ->
            // Поток моста — не главный: файл пишем прямо здесь
            val ok = origin != null && json != null && origin.startsWith("http") && runCatching {
                file(app).writeText(json)
                val cookies = runCatching { CookieManager.getInstance().getCookie(origin) }.getOrNull()
                prefs(app).edit(commit = true) {
                    putString(KEY_SOURCE, source)
                    putString(KEY_ORIGIN, origin)
                    putString(KEY_COOKIES, cookies)
                }
                Log.i(TAG, "exported ${json.length} chars from $origin ($source)")
            }.onFailure { Log.e(TAG, "export write failed", it) }.isSuccess
            finish(ok)
        }
        exportCallback = callback
        main.postDelayed({ finish(false) }, EXPORT_TIMEOUT_MS)
        browser.evaluateJavascript(exportScript()) { }
    }

    // ==================== ЗАГРУЗКА ====================

    /**
     * Источник, данные которого ждут загрузки в этот движок, или null. Выгрузка из этого
     * же движка (новый не включился) не нужна — она стирается; как и выгрузка с другого
     * сервера, если адрес приложения успели сменить.
     */
    fun pendingImportOrigin(ctx: Context, running: String, appUrl: String): String? {
        val p = prefs(ctx)
        val origin = p.getString(KEY_ORIGIN, null)
        if (origin == null || !file(ctx).isFile) return null
        if (p.getString(KEY_SOURCE, null) == running ||
            Uri.parse(origin).host != Uri.parse(appUrl.trim()).host
        ) {
            discard(ctx)
            return null
        }
        return origin
    }

    /** Адрес пустой страницы источника, которую надо открыть перед [runImport]. */
    fun bootUrl(origin: String) = origin.trimEnd('/') + BOOT_PATH

    /**
     * Загружает выгруженные данные в открытую страницу источника. [onDone] — на главном
     * потоке: ok — перенесено; retry — страница оказалась не того источника (сервер не
     * ответил), данные остаются до следующего запуска.
     */
    fun runImport(
        ctx: Context,
        browser: Browser?,
        origin: String,
        onDone: (ok: Boolean, retry: Boolean, error: String?) -> Unit,
    ) {
        val app = ctx.applicationContext
        var finished = false
        fun finish(ok: Boolean, retry: Boolean, error: String?) {
            main.post {
                if (finished) return@post
                finished = true
                importCallback = null
                importData = null
                if (ok) {
                    restoreCookies(app, origin)
                    discard(app)
                } else if (!retry) {
                    // Повторять бессмысленно: ошибка не сетевая, при следующем запуске
                    // было бы то же самое
                    discard(app)
                }
                onDone(ok, retry, error)
            }
        }
        if (browser == null || browser.isDestroyed) {
            finish(false, true, "no browser")
            return
        }
        importData = runCatching { file(app).readText() }.getOrNull()
        if (importData == null) {
            finish(false, false, "no data")
            return
        }
        importCallback = { r ->
            Log.i(TAG, "import result: $r")
            finish(r.optBoolean("ok"), r.optBoolean("retry"), r.optString("error").ifEmpty { null })
        }
        main.postDelayed({ finish(false, true, "timeout") }, IMPORT_TIMEOUT_MS)
        browser.evaluateJavascript(importScript(origin)) { }
    }

    private fun restoreCookies(ctx: Context, origin: String) {
        val cookies = prefs(ctx).getString(KEY_COOKIES, null) ?: return
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        cookies.split(';').map { it.trim() }.filter { '=' in it }.forEach {
            cm.setCookie(origin, "$it; Path=/; Max-Age=$COOKIE_MAX_AGE")
        }
        cm.flush()
    }

    fun discard(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit(commit = true) { clear() }
    }

    // ==================== СКРИПТЫ ====================
    // ES5: выгрузка идёт в движке, от которого уходят, — это бывает Chromium 66

    private fun exportScript(): String = """
(function () {
  var B = window.$BRIDGE;
  if (!B) return;
  var MAX = $MAX_JSON_CHARS;
  var KNOWN = ${KNOWN_DBS.joinToString(",", "[", "]") { "'$it'" }};
  var SKIP = {};
  function fail(e) { try { B.exportFailed(String(e && e.message || e)); } catch (x) {} }
  // Date — метка, двоичные значения (Blob, ArrayBuffer) запись не переносят
  function enc(key, value) {
    var raw = this[key];
    if (raw instanceof Date) return { __tsDate: raw.getTime() };
    if ((typeof Blob !== 'undefined' && raw instanceof Blob) || raw instanceof ArrayBuffer ||
        (ArrayBuffer.isView && ArrayBuffer.isView(raw))) throw SKIP;
    return value;
  }
  function encode(v) {
    try { return JSON.stringify(v, enc); } catch (e) { if (e === SKIP) return null; throw e; }
  }
  try {
    var local = {};
    for (var i = 0; i < localStorage.length; i++) {
      var k = localStorage.key(i);
      local[k] = localStorage.getItem(k);
    }
    var head = '{"v":1,"origin":' + JSON.stringify(location.origin) + ',"local":' + JSON.stringify(local) + ',"idb":[';
    var used = head.length;
    var dbs = [];
    var names = KNOWN.slice();
    function listDbs(cb) {
      if (!window.indexedDB) { cb(); return; }
      if (!indexedDB.databases) { cb(); return; }
      indexedDB.databases().then(function (list) {
        for (var i = 0; i < list.length; i++) {
          if (list[i].name && names.indexOf(list[i].name) < 0) names.push(list[i].name);
        }
        cb();
      }, function () { cb(); });
    }
    function exportDb(name, cb) {
      var req;
      try { req = indexedDB.open(name); } catch (e) { cb(); return; }
      // Базы нет — не создаём её пустой: приложение потом не дождалось бы onupgradeneeded
      req.onupgradeneeded = function () { try { req.transaction.abort(); } catch (e) {} };
      req.onerror = function (e) { if (e && e.preventDefault) e.preventDefault(); cb(); };
      req.onsuccess = function () {
        var db = req.result;
        var storeNames = [];
        for (var i = 0; i < db.objectStoreNames.length; i++) storeNames.push(db.objectStoreNames[i]);
        if (!storeNames.length) { db.close(); cb(); return; }
        var tx = db.transaction(storeNames, 'readonly');
        var stores = [];
        storeNames.forEach(function (sn) {
          var st = tx.objectStore(sn);
          var indexes = [];
          for (var j = 0; j < st.indexNames.length; j++) {
            var ix = st.index(st.indexNames[j]);
            indexes.push({ name: ix.name, keyPath: ix.keyPath, unique: ix.unique, multiEntry: ix.multiEntry });
          }
          var meta = { name: sn, keyPath: st.keyPath, autoIncrement: st.autoIncrement, indexes: indexes };
          var recs = [];
          stores.push({ meta: meta, recs: recs });
          st.openCursor().onsuccess = function (e) {
            var c = e.target.result;
            if (!c) return;
            var s = encode({ k: c.primaryKey, v: c.value });
            if (s !== null) recs.push(s);
            c['continue']();
          };
        });
        tx.oncomplete = function () {
          db.close();
          var parts = stores.map(function (s) {
            var m = JSON.stringify(s.meta);
            return m.slice(0, -1) + ',"records":[' + s.recs.join(',') + ']}';
          });
          var str = '{"name":' + JSON.stringify(name) + ',"version":' + db.version + ',"stores":[' + parts.join(',') + ']}';
          // Не влезает — пропускаем базу целиком (это кэш, он наполнится заново)
          if (used + str.length < MAX) { dbs.push(str); used += str.length + 1; }
          cb();
        };
        tx.onerror = tx.onabort = function () { db.close(); cb(); };
      };
    }
    listDbs(function () {
      var n = 0;
      (function next() {
        if (n >= names.length) {
          B.exported(location.origin, head + dbs.join(',') + ']}');
          return;
        }
        exportDb(names[n++], next);
      })();
    });
  } catch (e) { fail(e); }
})();
"""

    private fun importScript(origin: String): String = """
(function () {
  var B = window.$BRIDGE;
  if (!B) return;
  function done(r) { try { B.imported(JSON.stringify(r)); } catch (e) {} }
  try {
    if (location.origin !== ${JSONObject.quote(origin)}) {
      done({ ok: false, retry: true, error: 'page ' + location.origin });
      return;
    }
    var data = JSON.parse(B.data(), function (k, v) {
      if (v && typeof v === 'object' && typeof v.__tsDate === 'number' && Object.keys(v).length === 1) return new Date(v.__tsDate);
      return v;
    });
    if (!data || !data.local) { done({ ok: false, error: 'empty data' }); return; }
    localStorage.clear();
    var keys = Object.keys(data.local);
    for (var i = 0; i < keys.length; i++) localStorage.setItem(keys[i], data.local[keys[i]]);
    var stats = { ok: true, local: keys.length, dbs: 0, records: 0, failed: [] };
    var dbs = data.idb || [];
    var n = 0;
    function next() {
      if (n >= dbs.length) { done(stats); return; }
      importDb(dbs[n++], next);
    }
    function importDb(d, cb) {
      var del = indexedDB.deleteDatabase(d.name);
      var opened = false;
      del.onsuccess = del.onerror = function () { if (!opened) { opened = true; open(); } };
      function open() {
        var req = indexedDB.open(d.name, d.version);
        req.onupgradeneeded = function () {
          var db = req.result;
          d.stores.forEach(function (s) {
            var opts = {};
            if (s.keyPath !== null && s.keyPath !== undefined) opts.keyPath = s.keyPath;
            if (s.autoIncrement) opts.autoIncrement = true;
            var st = db.createObjectStore(s.name, opts);
            (s.indexes || []).forEach(function (ix) {
              st.createIndex(ix.name, ix.keyPath, { unique: !!ix.unique, multiEntry: !!ix.multiEntry });
            });
          });
        };
        req.onerror = function (e) { if (e && e.preventDefault) e.preventDefault(); stats.failed.push(d.name); cb(); };
        req.onsuccess = function () {
          var db = req.result;
          var names = d.stores.map(function (s) { return s.name; });
          if (!names.length) { db.close(); stats.dbs++; cb(); return; }
          var tx = db.transaction(names, 'readwrite');
          d.stores.forEach(function (s) {
            var st = tx.objectStore(s.name);
            s.records.forEach(function (r) {
              if (st.keyPath !== null) st.put(r.v); else st.put(r.v, r.k);
              stats.records++;
            });
          });
          // Ошибка одной записи не отменяет остальные
          tx.onerror = function (e) { if (e && e.preventDefault) e.preventDefault(); };
          tx.oncomplete = function () { db.close(); stats.dbs++; cb(); };
          tx.onabort = function () { db.close(); stats.failed.push(d.name); cb(); };
        };
      }
    }
    if (!window.indexedDB) { done(stats); return; }
    next();
  } catch (e) { done({ ok: false, error: String(e && e.message || e) }); }
})();
"""
}
