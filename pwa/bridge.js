/* bridge.js — browser implementation of window.Android for the web app (PWA).
 * Backs the shared bridge-core logic with sql.js (SQLite compiled to WASM), reading the
 * same .db module files the Android app bundles. Modules are cached (Cache Storage) for
 * offline use and opened lazily into WASM memory on first use. User data (notes, highlights,
 * bookmarks, settings, reading-plan progress) lives in its own sql.js db persisted to IndexedDB.
 */
window.MyBible = (function () {
  'use strict';

  var MOD_CACHE = 'mybible-mods-v1';
  var IDB_NAME = 'mybible', IDB_STORE = 'kv', USER_KEY = 'userdb';

  var SQL = null;                 // sql.js module
  var OPENED = Object.create(null); // id -> sql.js Database (modules)
  var CATALOG = [];               // from catalog.json
  var byId = Object.create(null);
  var userDb = null;              // sql.js Database for user data
  var persistTimer = null;

  // -------------------------------------------------------------- IndexedDB kv
  function idb() {
    return new Promise(function (res, rej) {
      var r = indexedDB.open(IDB_NAME, 1);
      r.onupgradeneeded = function () { r.result.createObjectStore(IDB_STORE); };
      r.onsuccess = function () { res(r.result); };
      r.onerror = function () { rej(r.error); };
    });
  }
  function idbGet(key) {
    return idb().then(function (db) {
      return new Promise(function (res, rej) {
        var t = db.transaction(IDB_STORE, 'readonly').objectStore(IDB_STORE).get(key);
        t.onsuccess = function () { res(t.result || null); }; t.onerror = function () { rej(t.error); };
      });
    });
  }
  function idbSet(key, val) {
    return idb().then(function (db) {
      return new Promise(function (res, rej) {
        var t = db.transaction(IDB_STORE, 'readwrite').objectStore(IDB_STORE).put(val, key);
        t.onsuccess = function () { res(); }; t.onerror = function () { rej(t.error); };
      });
    });
  }

  // ------------------------------------------------------------------- helpers
  function q(h, sql, prm) {
    var st = h.prepare(sql), rows = [];
    try { st.bind(prm || []); while (st.step()) rows.push(st.get()); }
    finally { st.free(); }
    return rows;
  }
  function run(h, sql, prm) { h.run(sql, prm || []); }
  function b64(u8) {
    var s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }
  function schedulePersist() {
    if (persistTimer) clearTimeout(persistTimer);
    persistTimer = setTimeout(function () {
      persistTimer = null;
      try { idbSet(USER_KEY, userDb.export()); } catch (e) { /* best-effort */ }
    }, 250);
  }

  // ------------------------------------------------------------- bridge wiring
  var core = null;
  function buildCore() {
    core = BibleBridge.makeBridge({
      modHandle: function (id) { return OPENED[id] || null; },
      userHandle: function () { return userDb; },
      q: q, run: run, persist: schedulePersist, b64: b64,
      catalog: function () { return CATALOG; }
    });
  }

  function referencedIds(stateJson) {
    try {
      var s = JSON.parse(stateJson || '{}'); var ids = [];
      ['bible', 'commentary', 'dict', 'strongsDict', 'peek'].forEach(function (k) { if (s[k]) ids.push(s[k]); });
      (s.parallel || []).forEach(function (id) { if (id) ids.push(id); });
      return ids;
    } catch (e) { return []; }
  }

  function installAndroid() {
    var A = {};
    core.names.forEach(function (name) {
      A[name] = function () {
        var args = Array.prototype.slice.call(arguments);
        var v = core.call(name, args);
        if (name === 'getSetting') return v == null ? '' : String(v);   // raw string
        return v == null ? '' : JSON.stringify(v);
      };
    });
    // Intercept state saves: open any newly-referenced modules, then re-render.
    var baseSet = A.setSetting;
    A.setSetting = function (key, val) {
      var r = baseSet(key, val);
      if (key === 'state') {
        var need = referencedIds(val).filter(function (id) { return !OPENED[id] && byId[id]; });
        if (need.length) ensure(need).then(function () { if (window.__rerender) window.__rerender(); });
      }
      return r;
    };
    window.Android = A;
  }

  // --------------------------------------------------------------- module load
  function fetchModuleBytes(desc) {
    return caches.open(MOD_CACHE).then(function (cache) {
      return cache.match(desc.file).then(function (resp) {
        if (resp) return resp.arrayBuffer();
        return fetch(desc.file, { cache: 'no-store' }).then(function (net) {
          if (!net.ok) throw new Error('fetch ' + desc.file + ' -> ' + net.status);
          return cache.put(desc.file, net.clone()).then(function () { return net.arrayBuffer(); });
        });
      });
    }).then(function (buf) { return new Uint8Array(buf); });
  }

  // Ensure modules are downloaded + opened in memory. onProgress(done,total,id) optional.
  function ensure(ids, onProgress) {
    var list = (ids || []).filter(function (id) { return byId[id]; });
    var total = list.length, done = 0;
    return list.reduce(function (chain, id) {
      return chain.then(function () {
        if (OPENED[id]) { done++; if (onProgress) onProgress(done, total, id); return; }
        return fetchModuleBytes(byId[id]).then(function (u8) {
          OPENED[id] = new SQL.Database(u8);
          done++; if (onProgress) onProgress(done, total, id);
        });
      });
    }, Promise.resolve());
  }

  function isCached(desc) {
    return caches.open(MOD_CACHE).then(function (c) { return c.match(desc.file).then(function (r) { return !!r; }); });
  }

  // Download (cache) every module in priority order, without opening them. For full offline.
  function backgroundCacheAll(priority, onProgress) {
    var ordered = priority.slice();
    CATALOG.forEach(function (m) { if (ordered.indexOf(m.id) < 0) ordered.push(m.id); });
    var total = ordered.length, done = 0;
    return ordered.reduce(function (chain, id) {
      return chain.then(function () {
        var desc = byId[id]; if (!desc) { done++; return; }
        return isCached(desc).then(function (c) {
          var p = c ? Promise.resolve() : caches.open(MOD_CACHE).then(function (cache) {
            return fetch(desc.file, { cache: 'no-store' }).then(function (net) {
              if (net.ok) return cache.put(desc.file, net.clone());
            }).catch(function () { /* offline / will retry next launch */ });
          });
          return p.then(function () { done++; if (onProgress) onProgress(done, total, id); });
        });
      });
    }, Promise.resolve());
  }

  // ------------------------------------------------------------------- startup
  function init() {
    return initSqlJs({ locateFile: function (f) { return f; } }).then(function (sql) {
      SQL = sql;
      return idbGet(USER_KEY);
    }).then(function (bytes) {
      userDb = bytes ? new SQL.Database(new Uint8Array(bytes)) : new SQL.Database();
      BibleBridge.USER_SCHEMA.forEach(function (s) { userDb.run(s); });
      return fetch('catalog.json', { cache: 'no-store' }).then(function (r) { return r.json(); });
    }).then(function (cat) {
      CATALOG = cat; CATALOG.forEach(function (m) { byId[m.id] = m; });
      buildCore();
      installAndroid();
      return { catalog: CATALOG };
    });
  }

  // Load the UI scripts (after the bridge is live) in the same order the app expects.
  function start() {
    return new Promise(function (res) {
      var files = ['morph.js', 'books.js', 'app.js'], i = 0;
      (function next() {
        if (i >= files.length) return res();
        var s = document.createElement('script');
        s.src = files[i++]; s.onload = next; s.onerror = next;
        document.body.appendChild(s);
      })();
    });
  }

  function savedState() { try { return window.Android.getSetting('state'); } catch (e) { return ''; } }

  return {
    init: init, ensure: ensure, start: start, savedState: savedState,
    referencedIds: referencedIds, backgroundCacheAll: backgroundCacheAll,
    catalog: function () { return CATALOG; }
  };
})();
