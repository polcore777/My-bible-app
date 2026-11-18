/* Parity test: run bridge-core against the real module DBs via node:sqlite, and diff the
 * output against the Python reference server (tools/testserver.py) for read-only methods.
 * Also exercises the user-data methods (notes/highlights/bookmarks/settings/plan) in isolation. */
'use strict';
const { DatabaseSync } = require('node:sqlite');
const path = require('path');
const http = require('http');
const BibleBridge = require('./bridge-core.js');

const MODS = path.resolve(__dirname, '../app/src/main/assets/modules');
const PORT = process.env.PORT || '8099';

const modCache = {};
function modHandle(id) {
  if (!(id in modCache)) {
    try { modCache[id] = new DatabaseSync(path.join(MODS, id + '.db'), { readOnly: true }); }
    catch (e) { modCache[id] = null; }
  }
  return modCache[id];
}
const catalog = require('./catalog.json').map(m => ({ id: m.id, type: m.type, title: m.title, abbr: m.abbr, strongs: m.strongs, builtin: m.builtin, rtl: m.rtl }));

const userDb = new DatabaseSync(':memory:');
BibleBridge.USER_SCHEMA.forEach(s => userDb.exec(s));

function q(h, sql, prm) {
  const st = h.prepare(sql);
  const rows = st.all(...(prm || []));
  return rows.map(o => Object.values(o));
}
function run(h, sql, prm) { h.prepare(sql).run(...(prm || [])); }

const bridge = BibleBridge.makeBridge({
  modHandle, userHandle: () => userDb, q, run,
  persist: () => {}, b64: u8 => Buffer.from(u8).toString('base64'),
  catalog: () => catalog
});

function py(method, args) {
  return new Promise((res, rej) => {
    const body = JSON.stringify({ m: method, a: args });
    const req = http.request({ host: '127.0.0.1', port: PORT, path: '/bridge', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } },
      r => { let d = ''; r.on('data', c => d += c); r.on('end', () => res(d)); });
    req.on('error', rej); req.write(body); req.end();
  });
}

// read-only cases compared against the Python reference
const CASES = [
  ['chapter', ['kjv', 43, 1]],
  ['chapter', ['ohienko', 43, 3]],
  ['chapter', ['septuagint', 1, 1]],
  ['verseText', ['kjv', 43, 3, 16]],
  ['verseText', ['ohienko', 43, 3, 16]],
  ['search', ['kjv', 'faith', 'nt', false, 20]],
  ['search', ['kjv', 'G2316', '', false, 50]],
  ['search', ['web', '"in the beginning"', 'ot', true, 10]],
  ['entry', ['strongs', 'G2316']],
  ['entry', ['ukrstrong', 'G1063']],
  ['entry', ['ukrstrong', 'G26']],
  ['entry', ['strongs', 'H7225']],
  ['entryKeys', ['strongs', 'G2', 10]],
  ['entryKeys', ['ukrstrong', 'G26', 8]],
  ['comments', ['robertson', 43, 3, 16]],
  ['comments', ['vincent', 40, 5, 3]],
  ['sections', ['chafer-grace']],
];

(async () => {
  let pass = 0, fail = 0;
  for (const [m, a] of CASES) {
    const local = bridge.call(m, a);
    let expected;
    try { const txt = await py(m, a); expected = txt === '' ? null : JSON.parse(txt); }
    catch (e) { console.log('  SKIP', m, JSON.stringify(a), '(python server not reachable:', e.message + ')'); continue; }
    const A = JSON.stringify(local), B = JSON.stringify(expected);
    if (A === B) { pass++; console.log('  ok  ', m, JSON.stringify(a), '→', A.length > 70 ? A.slice(0, 67) + '…' : A); }
    else {
      fail++; console.log('  FAIL', m, JSON.stringify(a));
      console.log('        local :', A.slice(0, 160));
      console.log('        python:', B.slice(0, 160));
    }
  }

  // section() needs a real id from sections()
  const secs = bridge.call('sections', ['chafer-grace']);
  if (secs.length) {
    const sid = secs[0].id;
    const local = JSON.stringify(bridge.call('section', ['chafer-grace', sid]));
    const exp = await py('section', ['chafer-grace', sid]).then(t => t === '' ? null : t).catch(() => '__err__');
    const okSec = exp !== '__err__' && local === JSON.stringify(JSON.parse(exp));
    (okSec ? pass++ : fail++); console.log((okSec ? '  ok   ' : '  FAIL ') + 'section chafer-grace #' + sid);
  }

  // ---- user-data round-trips (local only) ----
  console.log('\n  user-data:');
  const u = [];
  bridge.call('setSetting', ['state', JSON.stringify({ bible: 'kjv', peek: 'ohienko' })]);
  u.push(['getSetting state', bridge.call('getSetting', ['state']) === '{"bible":"kjv","peek":"ohienko"}']);
  bridge.call('setNote', [43, 3, 16, 'For God so loved — note']);
  u.push(['setNote/userChapter', bridge.call('userChapter', [43, 3]).notes['16'] === 'For God so loved — note']);
  const tgl1 = bridge.call('toggleBookmark', [43, 3, 16]);
  const tgl2 = bridge.call('toggleBookmark', [43, 3, 16]);
  u.push(['toggleBookmark on/off', tgl1 === true && tgl2 === false]);
  bridge.call('setHighlight', [1, 1, 1, 'yellow']);
  u.push(['setHighlight/userList', (bridge.call('userList', ['highlights'])[0] || {}).color === 'yellow']);
  bridge.call('setPlanDay', ['mcheyne', 5, true]);
  u.push(['setPlanDay/planDone', JSON.stringify(bridge.call('planDone', ['mcheyne'])) === '[5]']);
  for (const [name, okq] of u) { (okq ? pass++ : fail++); console.log('   ', okq ? 'ok  ' : 'FAIL', name); }

  console.log('\n  RESULT:', pass, 'passed,', fail, 'failed');
  process.exit(fail ? 1 : 0);
})();
