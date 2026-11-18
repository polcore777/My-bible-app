/* bridge-core.js — environment-agnostic reimplementation of the native Android bridge.
 *
 * The desktop/Android app's UI (app.js) calls a synchronous window.Android.<method>(...args)
 * bridge and JSON-parses the string it returns. This file contains the exact query logic,
 * ported verbatim from the reference implementation (tools/testserver.py), parameterised over
 * a small `env` adapter so the same code runs against sql.js (browser) or node:sqlite (tests).
 *
 * env = {
 *   modHandle(id)   -> opaque db handle for a module, or null if not loaded
 *   userHandle()    -> opaque db handle for the per-user data db
 *   q(h, sql, prm)  -> array of rows, each row an array of column values (blobs as Uint8Array)
 *   run(h, sql, prm)-> void (INSERT/UPDATE/DELETE on the user db)
 *   persist()       -> called after any user-db mutation (save to durable storage)
 *   b64(uint8)      -> base64 string (for image blobs)
 *   catalog()       -> array of module descriptors for modules() [{id,type,title,abbr,strongs,builtin,rtl}]
 * }
 *
 * Returns an object: { call(name, argsArray) -> JS value, names:[...] }.
 * The browser layer stringifies the result (raw string for getSetting, JSON otherwise).
 */
(function (root) {
  'use strict';

  function strongKey(k) {
    var m = /^\s*([HGhg])\s*0*(\d{1,5})([A-Za-z]?)\s*$/.exec(k || '');
    return m ? (m[1].toUpperCase() + String(parseInt(m[2], 10)) + m[3].toUpperCase()) : null;
  }
  function likeEsc(t) { return t.replace(/\\/g, '\\\\').replace(/%/g, '\\%').replace(/_/g, '\\_'); }

  function makeBridge(env) {
    var M = {};

    M.modules = function () {
      var order = { bible: 0, commentary: 1, dictionary: 2, book: 3, images: 4 };
      var out = (env.catalog() || []).map(function (m) {
        return { id: m.id, type: m.type, title: m.title, abbr: m.abbr,
                 strongs: !!m.strongs, builtin: !!m.builtin, rtl: !!m.rtl };
      });
      out.sort(function (a, b) {
        var oa = order[a.type] == null ? 9 : order[a.type];
        var ob = order[b.type] == null ? 9 : order[b.type];
        return oa - ob || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
      });
      return out;
    };

    M.chapter = function (a) {
      var d = env.modHandle(a[0]); if (!d) return [];
      return env.q(d, 'SELECT verse,html FROM verses WHERE book=? AND chapter=? ORDER BY verse', [a[1], a[2]])
        .map(function (r) { return [r[0], r[1]]; });
    };

    M.verseText = function (a) {
      var d = env.modHandle(a[0]); if (!d) return '';
      var r = env.q(d, 'SELECT html FROM verses WHERE book=? AND chapter=? AND verse=?', [a[1], a[2], a[3]]);
      return r.length ? r[0][0] : '';
    };

    M.search = function (a) {
      var id = a[0], q = a[1], scope = a[2], whole = a[3], limit = a[4];
      var d = env.modHandle(id);
      if (!d || !(q || '').trim()) return { total: 0, results: [] };
      var where = [], args = [];
      var sk = strongKey(q);
      if (sk) {
        where.push('html LIKE ?'); args.push('%>' + sk + '<%');
      } else {
        var toks = (q.match(/"[^"]+"|\S+/g) || []);
        for (var i = 0; i < toks.length; i++) {
          var t = toks[i].replace(/^"|"$/g, '').toLowerCase().replace(/[\W_]+/g, ' ').trim();
          if (!t) continue;
          var like = likeEsc(t);
          if (whole) { where.push("(' '||plain||' ') LIKE ? ESCAPE '\\'"); args.push('% ' + like + ' %'); }
          else { where.push("plain LIKE ? ESCAPE '\\'"); args.push('%' + like + '%'); }
        }
        if (!where.length) return { total: 0, results: [] };
      }
      var w = where.join(' AND ');
      if (scope === 'ot') w += ' AND book<=39';
      else if (scope === 'nt') w += ' AND book>=40';
      else if (scope && scope.indexOf('book:') === 0) { w += ' AND book=?'; args.push(parseInt(scope.slice(5), 10)); }
      var total = env.q(d, 'SELECT COUNT(*) FROM verses WHERE ' + w, args)[0][0];
      var lim = Math.max(1, Math.min(limit, 2000));
      var rows = env.q(d, 'SELECT book,chapter,verse,html FROM verses WHERE ' + w + ' ORDER BY book,chapter,verse LIMIT ?', args.concat([lim]));
      return { total: total, results: rows.map(function (r) { return [r[0], r[1], r[2], r[3]]; }) };
    };

    M.comments = function (a) {
      var d = env.modHandle(a[0]); if (!d) return [];
      var b = a[1], c = a[2], v = a[3];
      var sql = 'SELECT ch1,v1,ch2,v2,html FROM comments WHERE book=? AND (' +
        'ch1=0 OR (v1=0 AND ch1<=? AND ch2>=?) OR ' +
        '(v1>0 AND (ch1<? OR (ch1=? AND v1<=?)) AND (ch2>? OR (ch2=? AND v2>=?)))) ORDER BY ch1,v1,ch2,v2';
      return env.q(d, sql, [b, c, c, c, c, v, c, c, v]).map(function (r) {
        return { ch1: r[0], v1: r[1], ch2: r[2], v2: r[3], html: r[4] };
      });
    };

    M.entry = function (a) {
      var d = env.modHandle(a[0]); if (!d) return '';
      var k = (a[1] || '').trim(); k = strongKey(k) || k;
      var r = env.q(d, 'SELECT html FROM entries WHERE key=? COLLATE NOCASE LIMIT 1', [k]);
      return r.length ? r[0][0] : '';
    };

    M.entryKeys = function (a) {
      var d = env.modHandle(a[0]); if (!d) return [];
      var p = (a[1] || '').trim(); p = strongKey(p) || p;
      var like = likeEsc(p);
      var lim = Math.max(1, Math.min(a[2], 200));
      var out = env.q(d, "SELECT key FROM entries WHERE key LIKE ? ESCAPE '\\' ORDER BY length(key),key COLLATE NOCASE LIMIT ?", [like + '%', lim])
        .map(function (r) { return r[0]; });
      if (out.length < lim) {
        var more = env.q(d, "SELECT key FROM entries WHERE key LIKE ? ESCAPE '\\' AND key NOT LIKE ? ESCAPE '\\' ORDER BY key COLLATE NOCASE LIMIT ?",
          ['%' + like + '%', like + '%', lim - out.length]).map(function (r) { return r[0]; });
        out = out.concat(more);
      }
      return out;
    };

    M.sections = function (a) {
      var d = env.modHandle(a[0]); if (!d) return [];
      return env.q(d, 'SELECT id,title FROM sections ORDER BY id', []).map(function (r) { return { id: r[0], title: r[1] }; });
    };

    M.section = function (a) {
      var d = env.modHandle(a[0]); if (!d) return {};
      var r = env.q(d, 'SELECT id,title,html FROM sections WHERE id=?', [a[1]]);
      return r.length ? { id: r[0][0], title: r[0][1], html: r[0][2] } : {};
    };

    M.images = function (a) {
      var d = env.modHandle(a[0]); if (!d) return [];
      return env.q(d, 'SELECT id,title FROM images ORDER BY id', []).map(function (r) { return { id: r[0], title: r[1] }; });
    };

    M.image = function (a) {
      var d = env.modHandle(a[0]); if (!d) return '';
      var r = env.q(d, 'SELECT mime,data FROM images WHERE id=?', [a[1]]);
      return r.length ? 'data:' + r[0][0] + ';base64,' + env.b64(r[0][1]) : '';
    };

    // ---- user data ----
    M.userChapter = function (a) {
      var u = env.userHandle(), b = a[0], c = a[1];
      var notes = {}, hl = {}, bm = [];
      env.q(u, 'SELECT verse,text FROM notes WHERE book=? AND chapter=?', [b, c]).forEach(function (r) { notes[String(r[0])] = r[1]; });
      env.q(u, 'SELECT verse,color FROM highlights WHERE book=? AND chapter=?', [b, c]).forEach(function (r) { hl[String(r[0])] = r[1]; });
      env.q(u, 'SELECT verse FROM bookmarks WHERE book=? AND chapter=?', [b, c]).forEach(function (r) { bm.push(r[0]); });
      return { notes: notes, hl: hl, bm: bm };
    };

    M.setNote = function (a) {
      var u = env.userHandle(), b = a[0], c = a[1], v = a[2], t = a[3];
      if (!(t || '').trim()) env.run(u, 'DELETE FROM notes WHERE book=? AND chapter=? AND verse=?', [b, c, v]);
      else env.run(u, 'INSERT OR REPLACE INTO notes VALUES(?,?,?,?,0)', [b, c, v, t]);
      env.persist(); return null;
    };

    M.setHighlight = function (a) {
      var u = env.userHandle(), b = a[0], c = a[1], v = a[2], color = a[3];
      if (!color) env.run(u, 'DELETE FROM highlights WHERE book=? AND chapter=? AND verse=?', [b, c, v]);
      else env.run(u, 'INSERT OR REPLACE INTO highlights VALUES(?,?,?,?,0)', [b, c, v, color]);
      env.persist(); return null;
    };

    M.toggleBookmark = function (a) {
      var u = env.userHandle(), b = a[0], c = a[1], v = a[2];
      var has = env.q(u, 'SELECT 1 FROM bookmarks WHERE book=? AND chapter=? AND verse=?', [b, c, v]).length;
      if (has) { env.run(u, 'DELETE FROM bookmarks WHERE book=? AND chapter=? AND verse=?', [b, c, v]); env.persist(); return false; }
      env.run(u, 'INSERT INTO bookmarks VALUES(?,?,?,0)', [b, c, v]); env.persist(); return true;
    };

    M.userList = function (a) {
      var u = env.userHandle(), kind = a[0], out = [];
      if (kind === 'notes')
        env.q(u, 'SELECT book,chapter,verse,text FROM notes ORDER BY book,chapter,verse', []).forEach(function (r) { out.push({ b: r[0], c: r[1], v: r[2], text: r[3] }); });
      else if (kind === 'highlights')
        env.q(u, 'SELECT book,chapter,verse,color FROM highlights ORDER BY book,chapter,verse', []).forEach(function (r) { out.push({ b: r[0], c: r[1], v: r[2], color: r[3] }); });
      else if (kind === 'bookmarks')
        env.q(u, 'SELECT book,chapter,verse FROM bookmarks ORDER BY book,chapter,verse', []).forEach(function (r) { out.push({ b: r[0], c: r[1], v: r[2] }); });
      return out;
    };

    M.getSetting = function (a) {
      var r = env.q(env.userHandle(), 'SELECT value FROM settings WHERE key=?', [a[0]]);
      return r.length ? r[0][0] : null;   // raw string; the UI JSON-parses it
    };

    M.setSetting = function (a) {
      env.run(env.userHandle(), 'INSERT OR REPLACE INTO settings VALUES(?,?)', [a[0], a[1]]);
      env.persist(); return null;
    };

    M.planDone = function (a) {
      return env.q(env.userHandle(), 'SELECT day FROM plan WHERE plan=? ORDER BY day', [a[0]]).map(function (r) { return r[0]; });
    };

    M.setPlanDay = function (a) {
      var u = env.userHandle(), plan = a[0], day = a[1], done = a[2];
      if (done) env.run(u, 'INSERT OR IGNORE INTO plan VALUES(?,?)', [plan, day]);
      else env.run(u, 'DELETE FROM plan WHERE plan=? AND day=?', [plan, day]);
      env.persist(); return null;
    };

    M.version = function () { return 'web'; };
    M.deleteModule = function () { return false; };
    M.share = function () { return null; };
    M.importFiles = function () { return null; };
    M.importFolder = function () { return null; };
    M.exportBackup = function () { return null; };
    M.restoreBackup = function () { return null; };

    return {
      names: Object.keys(M),
      call: function (name, args) {
        var fn = M[name];
        if (!fn) throw new Error('no bridge method: ' + name);
        return fn(args || []);
      }
    };
  }

  var api = { makeBridge: makeBridge, strongKey: strongKey, USER_SCHEMA: [
    'CREATE TABLE IF NOT EXISTS notes(book INT,chapter INT,verse INT,text TEXT,updated INT,PRIMARY KEY(book,chapter,verse))',
    'CREATE TABLE IF NOT EXISTS highlights(book INT,chapter INT,verse INT,color TEXT,updated INT,PRIMARY KEY(book,chapter,verse))',
    'CREATE TABLE IF NOT EXISTS bookmarks(book INT,chapter INT,verse INT,updated INT,PRIMARY KEY(book,chapter,verse))',
    'CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT)',
    'CREATE TABLE IF NOT EXISTS plan(plan TEXT,day INT,PRIMARY KEY(plan,day))'
  ] };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.BibleBridge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
