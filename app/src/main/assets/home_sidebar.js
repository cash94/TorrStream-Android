// home_sidebar.js — боковое меню подборок для главной веб-интерфейса.
//
// Подкладывается приложением в страницу после загрузки (MainActivity.injectHomeSidebar),
// выключается в меню приложения. Веб-интерфейс не меняется: скрипт только читает то,
// что главная и так отдаёт наружу, и ничего не знает о составе подборок.
//
//  - HomeScreen.state (js/home.js): rowEls / rows / rowCols / activeRow — ряды главной
//    в том порядке, в каком они на экране. Новая подборка на сервере — новый ряд —
//    новый пункт меню, без правок здесь.
//  - ScreenStrategies.home (js/home.js, registerStrategy): handleNavigation, focusItem,
//    onOk, ensureFocus — те же вызовы, что делает пульт.
//
// Открывается «влево», когда левее идти некуда (первая карточка ряда, «Смотреть»,
// первый пункт шапки): исходная навигация не сдвинула фокус — значит, край.
// В меню: вверх/вниз — выбор, ОК — перейти, вправо/«Назад» — закрыть.
//
// Заодно — оценка «★ 7.5» в углу постеров рядов и заметный фокус на пунктах шапки (Главная, Мои торренты, Донат…): у них
// фокус — только цвет текста, и его не отличить от текущего раздела.
//
// ES5 без зависимостей: на части телевизоров Chrome 66 и старше.
(function () {
    'use strict';

    if (window.__tsSidebar) {
        window.__tsSidebar.setEnabled(true);
        return;
    }

    var TEXT = window.__tsSidebarText || {};
    var T_ROWS = TEXT.rows || 'Главная';
    var T_HINT = TEXT.hint || 'OK — перейти · ▶ или «Назад» — закрыть';
    var HOOK_TRIES = 1200;     // ×100 мс: главная может подняться не сразу (заставка, вход)
    var HTML = document.documentElement;

    var CSS = [
        // --- заметный фокус в шапке ---
        'html.ts-sb #home-topbar .home-nav-btn.focused,',
        'html.ts-sb #detail-view .home-nav-btn.focused{',
        '  background:#fff!important;color:#000!important;border-radius:.45em;',
        '  box-shadow:0 0 0 .4em #fff!important}',
        'html.ts-sb #home-topbar .home-nav-btn.focused i,',
        'html.ts-sb #detail-view .home-nav-btn.focused i{color:#000!important}',
        'html.ts-sb #home-topbar .home-nav-btn.home-nav-icon.focused,',
        'html.ts-sb #detail-view .home-nav-btn.home-nav-icon.focused{',
        '  border-radius:50%;border-color:#fff!important;box-shadow:none!important}',

        // --- оценка на постерах рядов: «★ 7.5», как в строке баннера ---
        '.ts-sb-rate{position:absolute;top:.5vh;left:.5vh;z-index:3;padding:.35vh .55vh;',
        '  border-radius:.6vh;background:rgba(0,0,0,.72);color:#fff;font-size:2vh;',
        '  font-weight:700;line-height:1;pointer-events:none;white-space:nowrap}',
        '.ts-sb-rate i{font-style:normal;color:#ff8c00;margin-right:.25em}',
        'html:not(.ts-sb) .ts-sb-rate{display:none}',

        // --- без кнопок баннера: «Смотреть» и «‹ ›» с пульта почти недостижимы, место —
        //     карточкам. Скрытые кнопки главная сама пропускает (offsetParent === null) ---
        'html.ts-sb #home-play-btn,html.ts-sb #home-prev-btn,html.ts-sb #home-next-btn{display:none!important}',

        // --- ▶ по центру постера под фокусом ---
        '.ts-sb-play{position:absolute;left:50%;top:50%;z-index:3;width:9vh;height:9vh;',
        '  margin:-4.5vh 0 0 -4.5vh;box-sizing:border-box;border-radius:50%;',
        '  border:.35vh solid rgba(255,140,0,.9);background:rgba(0,0,0,.42);',
        '  opacity:0;transition:opacity .15s;pointer-events:none}',
        '.ts-sb-play:after{content:"";position:absolute;left:50%;top:50%;margin:-1.6vh 0 0 -.9vh;',
        '  border-style:solid;border-width:1.6vh 0 1.6vh 2.6vh;',
        '  border-color:transparent transparent transparent rgba(255,140,0,.95)}',
        'html.ts-sb .home-card.focused .ts-sb-play{opacity:1}',
        'html:not(.ts-sb) .ts-sb-play{display:none}',

        // --- затемнение и панель ---
        '#ts-sb-dim{position:fixed;top:0;left:0;right:0;bottom:0;z-index:99990;',
        '  background:rgba(0,0,0,.55);opacity:0;visibility:hidden;',
        '  transition:opacity .18s,visibility 0s .18s}',
        'html.ts-sb-open #ts-sb-dim{opacity:1;visibility:visible;transition:opacity .18s}',
        '#ts-sb{position:fixed;top:0;left:0;bottom:0;z-index:99991;width:40vw;',
        '  min-width:280px;max-width:720px;box-sizing:border-box;padding:6vh 5vw 4vh 0;',
        '  display:flex;flex-direction:column;font-family:inherit;',
        '  background:linear-gradient(90deg,rgba(14,14,16,.98) 0%,rgba(14,14,16,.97) 72%,rgba(14,14,16,0) 100%);',
        '  -webkit-transform:translateX(-105%);transform:translateX(-105%);',
        '  transition:-webkit-transform .18s ease-out,transform .18s ease-out}',
        'html.ts-sb-open #ts-sb{-webkit-transform:none;transform:none}',
        '#ts-sb .ts-sb-head{color:#8c8c8c;font-size:2.1vh;letter-spacing:.08em;',
        '  text-transform:uppercase;padding:0 0 1.4vh 3vw;flex:none}',
        '#ts-sb .ts-sb-list{position:relative;flex:1 1 auto;min-height:0;overflow:hidden}',
        '#ts-sb .ts-sb-item{display:flex;align-items:center;box-sizing:border-box;',
        '  margin:0 0 .3vh 1.6vw;padding:1vh 1.4vw;border-radius:1vh;color:#c4c4c4;',
        '  font-size:2.7vh;line-height:1.25;cursor:pointer}',
        '#ts-sb .ts-sb-mark{flex:none;width:.3em;height:1.05em;margin-right:.75em;',
        '  border-radius:.2em;background:transparent}',
        '#ts-sb .ts-sb-ico{flex:none;width:1.2em;margin-right:.55em;font-size:.9em;',
        '  line-height:1;text-align:center}',
        '#ts-sb .ts-sb-label{flex:1 1 auto;min-width:0}',
        '#ts-sb .ts-sb-item.in-group{padding-left:2.6vw}',
        '#ts-sb .ts-sb-item.in-sub{padding-left:4.2vw}',
        '#ts-sb .ts-sb-group.ts-sb-sub{font-size:1.9vh;margin:1.4vh 0 .4vh 4.4vw;color:#8c8c8c}',
        '#ts-sb .ts-sb-group{color:#a6a6a6;font-size:2.2vh;font-weight:700;letter-spacing:.06em;',
        '  text-transform:uppercase;margin:2.2vh 0 .6vh 3vw}',
        '#ts-sb .ts-sb-item.is-active{color:#fff;font-weight:700}',
        '#ts-sb .ts-sb-item.is-active .ts-sb-mark{background:#ff8c00}',
        '#ts-sb .ts-sb-item.is-focus{background:#fff;color:#000;font-weight:700}',
        '#ts-sb .ts-sb-sep{height:1px;margin:1.3vh 0 1.6vh 3vw;background:rgba(255,255,255,.14)}',
        '#ts-sb .ts-sb-hint{flex:none;color:#6f6f6f;font-size:1.9vh;padding:1.6vh 0 0 3vw}'
    ].join('\n');

    var st = {
        enabled: true,
        open: false,
        items: [],
        idx: 0,
        prevFocus: null,
        lastRepeat: false,
        swallowUp: {},
        panel: null,
        list: null,
        dim: null
    };

    // ==================== ДОСТУП К ГЛАВНОЙ ====================

    function strategy() {
        return (typeof ScreenStrategies !== 'undefined' && ScreenStrategies.home) || null;
    }

    function homeState() {
        return (window.HomeScreen && window.HomeScreen.state) || null;
    }

    function homeActive() {
        try {
            return !!(window.HomeScreen && window.HomeScreen.isActive && window.HomeScreen.isActive());
        } catch (e) {
            return false;
        }
    }

    function focused() {
        return document.querySelector('.focused');
    }

    function dirOf(k) {
        if (typeof window.arrowDir === 'function') return window.arrowDir(k);
        return ({ 37: 'left', 38: 'up', 39: 'right', 40: 'down' })[k] || null;
    }

    function isOk(k) {
        return typeof window.isOkKey === 'function' ? window.isOkKey(k) : k === 13;
    }

    function isBack(k) {
        if (typeof window.isBackKey === 'function') return window.isBackKey(k);
        return k === 27 || k === 8 || k === 461 || k === 10009;
    }

    // ==================== РАЗМЕТКА ====================

    function el(tag, cls, text) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (text) e.textContent = text;
        return e;
    }

    function ensureDom() {
        if (!document.getElementById('ts-sb-style')) {
            var s = el('style');
            s.id = 'ts-sb-style';
            s.textContent = CSS;
            (document.head || HTML).appendChild(s);
        }
        if (st.panel && document.body.contains(st.panel)) return;
        st.dim = el('div');
        st.dim.id = 'ts-sb-dim';
        st.dim.addEventListener('click', function () { close(true); });

        st.panel = el('div');
        st.panel.id = 'ts-sb';
        st.panel.appendChild(el('div', 'ts-sb-head', T_ROWS));
        st.list = el('div', 'ts-sb-list');
        st.panel.appendChild(st.list);
        st.panel.appendChild(el('div', 'ts-sb-hint', T_HINT));

        document.body.appendChild(st.dim);
        document.body.appendChild(st.panel);
    }

    function rowTitle(rowEl) {
        var t = rowEl && rowEl.querySelector('.catalog-row-title');
        return t ? (t.textContent || '').replace(/\s+/g, ' ').trim() : '';
    }

    /** Классы значка без служебного (у него в оригинале display:none или размер водяного знака). */
    function iconClass(node, drop) {
        var parts = (node.className || '').split(/\s+/), out = [];
        for (var i = 0; i < parts.length; i++) if (parts[i] && parts[i] !== drop) out.push(parts[i]);
        return out.join(' ');
    }

    function capitalize(s) {
        return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
    }

    /**
     * «Что популярно · Онлайн», «Топ рейтинга: фильмы» → приставка и хвост. Подборки с общей
     * приставкой, идущие подряд, меню показывает группой: заголовок один, в пунктах — хвосты.
     * Состав групп нигде не записан — только по названиям, как их отдаёт сервер.
     */
    function splitName(name) {
        var m = /^(.+?)\s*(?:·|:|—|\|)\s*(.+)$/.exec(name);
        return m ? { prefix: m[1], rest: capitalize(m[2]) } : null;
    }

    function groupRows(rows) {
        for (var i = 0; i < rows.length; i++) {
            rows[i].split = rows[i].type === 'row' ? splitName(rows[i].name) : null;
        }
        for (var j = 0; j < rows.length; j++) {
            var cur = rows[j].split;
            if (!cur) continue;
            var prev = j > 0 && rows[j - 1].split;
            var next = j + 1 < rows.length && rows[j + 1].split;
            var samePrev = prev && prev.prefix === cur.prefix;
            var sameNext = next && next.prefix === cur.prefix;
            if (!samePrev && !sameNext) continue;   // одиночку не дробим
            if (!samePrev) rows[j].group = cur.prefix;
            rows[j].inGroup = true;
            rows[j].name = cur.rest;
        }
        return rows;
    }

    /**
     * Ряд из плиток категорий («Категории»: Фильмы, Сериалы, Аниме…) — не одна подборка, а
     * вход в несколько каталогов. В меню он раскрывается группой: заголовок — название ряда,
     * пункты — плитки со своими значком и цветом. ОК на пункте открывает каталог, как ОК
     * на самой плитке.
     */
    function pushTiles(out, hs, rowIndex, name, cards) {
        if (!cards[0].classList || !cards[0].classList.contains('home-cat-tile')) return false;
        var col = (hs.rowCols && hs.rowCols[rowIndex]) || 0;
        var tiles = [];
        for (var j = 0; j < cards.length; j++) {
            var card = cards[j];
            var label = card.getAttribute('data-title') ||
                ((card.querySelector('.home-tile-label') || {}).textContent || '').trim();
            if (!label) continue;
            var glyph = card.querySelector('.home-cat-watermark');
            var art = card.querySelector('.home-cat-art');
            tiles.push({
                type: 'tile',
                index: rowIndex,
                card: card,
                name: label,
                icon: glyph ? iconClass(glyph, 'home-cat-watermark') : 'fi fi-rr-apps',
                color: art && art.style.getPropertyValue ? art.style.getPropertyValue('--cat-c1').trim() : '',
                group: j === 0 ? name : null,
                inGroup: true,
                active: rowIndex === hs.activeRow && j === col
            });
        }
        // Внутри группы — те же подгруппы по приставке: «Кинопоиск · Комиксы» → «Комиксы»
        for (var k = 0; k < tiles.length; k++) tiles[k].split = splitName(tiles[k].name);
        for (var m = 0; m < tiles.length; m++) {
            var cur = tiles[m].split;
            if (!cur) continue;
            var prev = m > 0 && tiles[m - 1].split;
            var next = m + 1 < tiles.length && tiles[m + 1].split;
            var samePrev = prev && prev.prefix === cur.prefix;
            var sameNext = next && next.prefix === cur.prefix;
            if (!samePrev && !sameNext) continue;
            if (!samePrev) tiles[m].sub = cur.prefix;
            tiles[m].inSub = true;
            tiles[m].name = cur.rest;
        }
        for (var t = 0; t < tiles.length; t++) {
            tiles[t].split = null;
            out.push(tiles[t]);
        }
        return true;
    }

    /**
     * Подборка «…В кинотеатрах» — первым пунктом группы плиток («Категории»): самое
     * свежее, куда заходят чаще всего. Нет такой подборки или нет плиток — ничего не меняем.
     */
    var PIN_FIRST = /в кинотеатрах$/i;
    var PIN_ICON = 'fi fi-rr-popcorn';
    var PIN_COLOR = '#ff8c00';

    function pinFirst(rows) {
        var pin = -1, firstTile = -1;
        for (var i = 0; i < rows.length; i++) {
            if (pin < 0 && rows[i].type === 'row' && PIN_FIRST.test(rows[i].name)) pin = i;
            if (firstTile < 0 && rows[i].type === 'tile') firstTile = i;
        }
        if (pin < 0 || firstTile < 0) return rows;
        var it = rows.splice(pin, 1)[0];
        if (pin < firstTile) firstTile--;
        var sp = splitName(it.name);
        var head = rows[firstTile];
        it.type = 'tile-row';
        it.name = sp ? sp.rest : it.name;
        it.icon = PIN_ICON;
        it.color = PIN_COLOR;
        it.group = head.group;
        it.inGroup = true;
        head.group = null;
        rows.splice(firstTile, 0, it);
        return rows;
    }

    /** Пункты: подборки главной сверху, разделы шапки — под чертой. */
    function collect() {
        var out = [];
        var hs = homeState();
        if (hs && hs.rowEls) {
            var rows = [];
            for (var i = 0; i < hs.rowEls.length; i++) {
                var cards = hs.rows && hs.rows[i];
                var name = rowTitle(hs.rowEls[i]);
                if (!name || !cards || !cards.length) continue;
                if (pushTiles(rows, hs, i, name, cards)) continue;
                rows.push({ type: 'row', index: i, name: name, active: i === hs.activeRow });
            }
            out = groupRows(pinFirst(rows));
        }
        var btns = document.querySelectorAll('#home-topbar .home-nav-btn');
        var first = true;
        for (var b = 0; b < btns.length; b++) {
            var btn = btns[b];
            if (btn.offsetParent === null || btn.id === 'home-nav-home') continue;
            var label = btn.getAttribute('title') || (btn.textContent || '').trim();
            if (!label) continue;
            var ico = btn.querySelector('i');
            out.push({
                type: 'nav',
                el: btn,
                name: label,
                icon: ico ? iconClass(ico, 'home-nav-glyph') : '',
                sep: first && out.length > 0
            });
            first = false;
        }
        return out;
    }

    function render() {
        st.list.innerHTML = '';
        for (var i = 0; i < st.items.length; i++) {
            var it = st.items[i];
            if (it.sep) st.list.appendChild(el('div', 'ts-sb-sep'));
            if (it.group) st.list.appendChild(el('div', 'ts-sb-group', it.group));
            if (it.sub) st.list.appendChild(el('div', 'ts-sb-group ts-sb-sub', it.sub));
            var row = el('div', 'ts-sb-item' + (it.active ? ' is-active' : '') +
                (it.inSub ? ' in-sub' : it.inGroup ? ' in-group' : ''));
            if (it.type === 'row') {
                row.appendChild(el('span', 'ts-sb-mark'));
            } else {
                var ico = el('i', 'ts-sb-ico ' + it.icon);
                if (it.color) ico.style.color = it.color;
                row.appendChild(ico);
            }
            row.appendChild(el('span', 'ts-sb-label', it.name));
            row.setAttribute('data-ts-sb', String(i));
            row.addEventListener('click', onItemClick);
            it.node = row;
            st.list.appendChild(row);
        }
        paint();
    }

    function onItemClick(e) {
        var i = parseInt(e.currentTarget.getAttribute('data-ts-sb'), 10);
        if (isNaN(i)) return;
        e.preventDefault();
        e.stopPropagation();
        st.idx = i;
        choose();
    }

    /** Подсветка пункта и прокрутка списка, чтобы он был виден (без scrollIntoView). */
    function paint() {
        for (var i = 0; i < st.items.length; i++) {
            var n = st.items[i].node;
            if (n) n.classList.toggle('is-focus', i === st.idx);
        }
        var cur = st.items[st.idx] && st.items[st.idx].node;
        if (!cur) return;
        // Выбранный пункт — посередине списка: видно и что выше, и что ниже
        var box = st.list;
        var want = cur.offsetTop - (box.clientHeight - cur.offsetHeight) / 2;
        box.scrollTop = Math.max(0, Math.min(want, box.scrollHeight - box.clientHeight));
    }

    // ==================== ОТКРЫТЬ / ЗАКРЫТЬ / ВЫБРАТЬ ====================

    function open() {
        if (!st.enabled || st.open || !homeActive()) return false;
        ensureDom();
        st.items = collect();
        if (!st.items.length) return false;
        st.prevFocus = focused();
        st.idx = 0;
        for (var i = 0; i < st.items.length; i++) {
            if (st.items[i].active) { st.idx = i; break; }
        }
        render();
        st.open = true;
        HTML.classList.add('ts-sb-open');
        // Список отрисован до показа: прокрутку считаем, когда размеры уже есть
        paint();
        return true;
    }

    function close(restore) {
        if (!st.open) return;
        st.open = false;
        HTML.classList.remove('ts-sb-open');
        var s = strategy();
        if (!restore || !s) return;
        var p = st.prevFocus;
        st.prevFocus = null;
        if (p && document.body.contains(p) && s.focusItem) s.focusItem(p);
        else if (s.ensureFocus) s.ensureFocus(true);
    }

    function choose() {
        var it = st.items[st.idx];
        var s = strategy();
        close(false);
        if (!it || !s) return;
        if (it.type === 'tile') {
            if (!document.body.contains(it.card)) {
                if (s.ensureFocus) s.ensureFocus(true);
                return;
            }
            if (s.focusItem) s.focusItem(it.card);
            if (s.onOk) s.onOk(it.card);
            return;
        }
        if (it.type === 'nav') {
            if (s.onOk) s.onOk(it.el);
            else it.el.click();
            return;
        }
        var hs = homeState();
        var cards = hs && hs.rows && hs.rows[it.index];
        if (!cards || !cards.length) {
            if (s.ensureFocus) s.ensureFocus(true);
            return;
        }
        var col = (hs.rowCols && hs.rowCols[it.index]) || 0;
        var card = cards[Math.max(0, Math.min(cards.length - 1, col))];
        if (s.focusItem) s.focusItem(card);
    }

    function move(delta) {
        var n = st.idx + delta;
        if (n < 0 || n >= st.items.length) return;
        st.idx = n;
        paint();
    }

    // ==================== ОЦЕНКА НА ПОСТЕРАХ ====================

    function fmtRating(v) {
        v = Number(v);
        return v > 0 ? (Math.round(v * 10) / 10).toFixed(1) : '';
    }

    /**
     * «★ 7.5» в угол постера каждой карточки рядов главной. Оценка — та же, что в строке
     * баннера (item.vote_average из HomeScreen.state.data); у плиток категорий и у
     * «Продолжить просмотр» её нет — там и значка нет.
     */
    function decorateCards() {
        if (!st.enabled) return;
        var hs = homeState();
        if (!hs || !hs.rows || !hs.rowKeys || !hs.data) return;
        for (var i = 0; i < hs.rows.length; i++) {
            var items = hs.data[hs.rowKeys[i]];
            var cards = hs.rows[i];
            if (!items || !cards) continue;
            for (var j = 0; j < cards.length; j++) {
                var card = cards[j];
                if (!card || !card.querySelector) continue;
                var idx = parseInt(card.getAttribute('data-item-index'), 10);
                var item = items[isNaN(idx) ? j : idx];
                var box = card.querySelector('.torrent-poster');
                if (!box) continue;
                if (item && !item.catKey && !box.querySelector('.ts-sb-play')) {
                    box.appendChild(el('span', 'ts-sb-play'));
                }
                var badge = box.querySelector('.ts-sb-rate');
                var r = item && !item.catKey ? fmtRating(item.vote_average) : '';
                if (!r) {
                    if (badge) badge.parentNode.removeChild(badge);
                    continue;
                }
                if (!badge) {
                    badge = el('span', 'ts-sb-rate');
                    box.appendChild(badge);
                }
                if (badge.__tsRate !== r) {
                    badge.innerHTML = '<i>★</i>' + r;
                    badge.__tsRate = r;
                }
            }
        }
    }

    var decorateTimer = 0;
    function scheduleDecorate() {
        if (decorateTimer) return;
        decorateTimer = setTimeout(function () {
            decorateTimer = 0;
            decorateCards();
        }, 150);
    }

    /** Ряды главной приходят по одному и пересобираются — следим за их контейнером. */
    function watchRows(tries) {
        var rows = document.getElementById('home-rows');
        if (!rows || typeof MutationObserver === 'undefined') {
            if (rows === null && tries < HOOK_TRIES) setTimeout(function () { watchRows(tries + 1); }, 100);
            return;
        }
        new MutationObserver(scheduleDecorate).observe(rows, { childList: true, subtree: true });
        scheduleDecorate();
    }

    // ==================== КРУПНЕЕ КАРТОЧКИ ====================
    //
    // Раскладку главной считает layoutHome (home.js): высоту баннера пишет в его style,
    // размер плиток — в <style id="home-card-style">. Её числа не трогаем, а поверх
    // (селекторы специфичнее, тоже !important) ставим плитки в CARD_SCALE раз крупнее и
    // забираем у баннера ровно ту высоту, на которую выросли картинки. Пересчитываем,
    // когда главная пересчитала своё (смена размера окна, ползунок «Размер карточек»).

    var CARD_SCALE = 1.2;
    var HERO_MIN_PX = 150;

    function ownStyle(id) {
        var s = document.getElementById(id);
        if (!s) {
            s = el('style');
            s.id = id;
            (document.head || HTML).appendChild(s);
        }
        return s;
    }

    function syncCardScale() {
        var own = ownStyle('ts-sb-cards');
        var src = document.getElementById('home-card-style');
        var hero = document.getElementById('home-hero');
        var css = '';
        var t = src && src.textContent;
        var card = t && /width:(\d+)px!important;height:(\d+)px!important/.exec(t);
        var img = t && /row-poster-img\{width:\d+px!important;height:(\d+)px!important/.exec(t);
        var heroH = hero ? parseInt(hero.style.height, 10) : NaN;
        if (st.enabled && card && img && heroH > 0) {
            var w = +card[1], h = +card[2], ih = +img[1];
            var delta = Math.round(ih * CARD_SCALE) - ih;
            if (heroH - delta < HERO_MIN_PX) delta = Math.max(0, heroH - HERO_MIN_PX);
            var nih = ih + delta;
            var nw = Math.round(w * nih / ih);
            var cardSel = 'html.ts-sb .catalog-row-card.home-card,' +
                'html.ts-sb .catalog-row-viewport .catalog-row-card.home-card';
            var imgSel = 'html.ts-sb .catalog-row-card.home-card .torrent-poster,' +
                'html.ts-sb .catalog-row-viewport .catalog-row-card.home-card .torrent-poster,' +
                'html.ts-sb .catalog-row-card.home-card .row-poster-img';
            css = cardSel + '{flex:0 0 ' + nw + 'px!important;width:' + nw + 'px!important;height:' +
                (h + delta) + 'px!important}' +
                imgSel + '{width:' + nw + 'px!important;height:' + nih + 'px!important}' +
                'html.ts-sb #home-hero{height:' + (heroH - delta) + 'px!important}';
        }
        if (own.textContent !== css) own.textContent = css;
    }

    function watchLayout(tries) {
        var src = document.getElementById('home-card-style');
        var hero = document.getElementById('home-hero');
        if (!src || !hero || typeof MutationObserver === 'undefined') {
            if (tries < HOOK_TRIES && typeof MutationObserver !== 'undefined') {
                setTimeout(function () { watchLayout(tries + 1); }, 100);
            }
            return;
        }
        new MutationObserver(syncCardScale).observe(src, { childList: true, characterData: true, subtree: true });
        new MutationObserver(syncCardScale).observe(hero, { attributes: true, attributeFilter: ['style'] });
        syncCardScale();
    }

    // ==================== ПУЛЬТ ====================

    function onKeyDown(e) {
        st.lastRepeat = !!e.repeat;
        if (!st.open) return;
        if (!homeActive()) { close(false); return; }
        var k = e.keyCode;
        var d = dirOf(k);
        var ok = !d && isOk(k);
        var back = !d && !ok && isBack(k);
        if (!d && !ok && !back) return;
        // Клавиша наша: ни control.js, ни главная её не увидят — и её отпускание тоже
        e.preventDefault();
        e.stopImmediatePropagation();
        st.swallowUp[k] = true;
        if (d === 'up') move(-1);
        else if (d === 'down') move(1);
        else if (d === 'right' || back) close(true);
        else if (ok && !e.repeat) choose();
    }

    function onKeyUp(e) {
        if (!st.swallowUp[e.keyCode]) return;
        delete st.swallowUp[e.keyCode];
        e.preventDefault();
        e.stopImmediatePropagation();
    }

    // ==================== ПОДКЛЮЧЕНИЕ К ГЛАВНОЙ ====================

    function wrap(s, name, after) {
        var orig = s[name];
        if (typeof orig !== 'function') return;
        s[name] = function () {
            var before = focused();
            var r = orig.apply(this, arguments);
            try { after(arguments, before); } catch (err) { /* меню — не повод ломать навигацию */ }
            return r;
        };
    }

    function hook(tries) {
        var s = strategy();
        if (!s || !window.HomeScreen || !document.body) {
            if (tries < HOOK_TRIES) setTimeout(function () { hook(tries + 1); }, 100);
            return;
        }
        if (s.__tsSidebar) return;
        s.__tsSidebar = true;
        ensureDom();
        wrap(s, 'handleNavigation', function (args, before) {
            // Удержание «влево» добегает до края и не должно с разгона открывать меню
            if (args[0] === 'left' && before && focused() === before && !st.lastRepeat) open();
        });
        // keydown/keyup на window в фазе перехвата — раньше обработчиков control.js на document
        watchRows(0);
        watchLayout(0);
        window.addEventListener('keydown', onKeyDown, true);
        window.addEventListener('keyup', onKeyUp, true);
        window.addEventListener('resize', function () { if (st.open) close(false); });
    }

    function setEnabled(on) {
        st.enabled = !!on;
        HTML.classList.toggle('ts-sb', st.enabled);
        if (!st.enabled) close(false);
        else scheduleDecorate();
        syncCardScale();
    }

    window.__tsSidebar = { setEnabled: setEnabled, open: open, close: close };
    setEnabled(true);
    hook(0);
})();
