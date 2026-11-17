/* My Bible — app logic. Talks to the Android side through window.Android (see Bridge.java). */
(function () {
  'use strict';

  // ------------------------------------------------------------------ bridge
  const B = (name, ...args) => {
    if (!window.Android || typeof window.Android[name] !== 'function') throw new Error('Bridge missing: ' + name);
    const r = window.Android[name](...args);
    return r == null || r === '' ? null : JSON.parse(r);
  };
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const content = $('#content');

  // ------------------------------------------------------------------- state
  const DEFAULTS = {
    bible: 'kjv', parallel: [], b: 43, c: 1, v: 1, commentary: '', dict: '', strongsDict: 'strongs',
    fs: 19, theme: 'light', strongs: true, redLetter: true, tab: 'read', planStart: '', searchHist: []
  };
  let S = Object.assign({}, DEFAULTS);
  try { Object.assign(S, B('getSetting', 'state') || {}); } catch (e) { /* first run */ }
  const save = () => { try { B('setSetting', 'state', JSON.stringify(S)); } catch (e) { } };

  let MODS = [];
  const loadModules = () => {
    MODS = B('modules') || [];
    const bibles = byType('bible');
    if (!bibles.find(m => m.id === S.bible) && bibles.length) S.bible = bibles[0].id;
    S.parallel = S.parallel.filter(id => bibles.find(m => m.id === id) && id !== S.bible);
    const cm = byType('commentary');
    if (!cm.find(m => m.id === S.commentary)) S.commentary = cm.length ? cm[0].id : '';
    const dicts = byType('dictionary');
    if (!dicts.find(m => m.id === S.strongsDict)) { const sd = dicts.find(m => m.strongs); S.strongsDict = sd ? sd.id : ''; }
    if (!dicts.find(m => m.id === S.dict)) S.dict = dicts.length ? dicts[0].id : '';
  };
  const byType = t => MODS.filter(m => m.type === t);
  const mod = id => MODS.find(m => m.id === id);
  const abbr = id => (mod(id) || {}).abbr || id;

  // ------------------------------------------------------------------- utils
  let toastTimer;
  function toast(msg, ms = 2200) {
    const t = $('#toast');
    t.textContent = msg; t.classList.remove('hidden');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
  }
  const plainOf = html => { const d = document.createElement('div'); d.innerHTML = html; $$('sup.s', d).forEach(s => s.remove()); $$('br', d).forEach(b => b.replaceWith(' ')); return d.textContent.replace(/\s+/g, ' ').trim(); };

  /** Turns Scripture references and Strong's numbers inside module text into links. */
  function linkify(root, opts = {}) {
    const strongRe = /\b([HG])0*(\d{1,5})\b/g;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: n => n.parentElement.closest('a,sup.s,script,style,.lemma') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => {
      const text = node.nodeValue;
      if (text.length < 3) return;
      const hits = Books.findRefs(text).map(r => Object.assign(r, { kind: 'ref' }));
      if (opts.strongs !== false) {
        let m; strongRe.lastIndex = 0;
        while ((m = strongRe.exec(text))) hits.push({ kind: 'strong', start: m.index, end: m.index + m[0].length, key: m[1] + (+m[2]) });
      }
      if (!hits.length) return;
      hits.sort((a, b) => a.start - b.start);
      const frag = document.createDocumentFragment();
      let pos = 0;
      hits.forEach(h => {
        if (h.start < pos) return;
        frag.appendChild(document.createTextNode(text.slice(pos, h.start)));
        const a = document.createElement('a');
        if (h.kind === 'ref') { a.className = 'ref'; a.dataset.ref = [h.b, h.c, h.v, h.c2, h.v2].join('.'); }
        else { a.className = 'sref'; a.dataset.s = h.key; }
        a.textContent = text.slice(h.start, h.end);
        frag.appendChild(a);
        pos = h.end;
      });
      frag.appendChild(document.createTextNode(text.slice(pos)));
      node.parentNode.replaceChild(frag, node);
    });
    return root;
  }
  const modHtml = (html, opts) => { const d = document.createElement('div'); d.className = 'mod'; d.innerHTML = html || ''; return linkify(d, opts); };

  // ------------------------------------------------------------------ sheets
  const sheets = [];
  function openSheet(title, body, opts = {}) {
    const el = document.createElement('div');
    el.className = 'sheet' + (opts.full ? ' full' : '');
    el.innerHTML = `<div class="sheet-head">${sheets.length ? '<button class="back" aria-label="Back">‹</button>' : ''}<h2></h2>${opts.action ? `<button class="act">${esc(opts.action)}</button>` : ''}<button class="close" aria-label="Close">✕</button></div><div class="sheet-body"></div>`;
    $('h2', el).textContent = title;
    const bodyEl = $('.sheet-body', el);
    if (typeof body === 'string') bodyEl.innerHTML = body; else if (body) bodyEl.appendChild(body);
    $('.close', el).onclick = closeAllSheets;
    const back = $('.back', el); if (back) back.onclick = closeSheet;
    if (opts.action) $('.act', el).onclick = () => opts.onAction(el);
    if (sheets.length) sheets[sheets.length - 1].classList.add('hidden');
    $('#sheets').appendChild(el);
    $('#scrim').classList.remove('hidden');
    sheets.push(el);
    return el;
  }
  function closeSheet() {
    const el = sheets.pop(); if (el) el.remove();
    if (sheets.length) sheets[sheets.length - 1].classList.remove('hidden');
    else $('#scrim').classList.add('hidden');
  }
  function closeAllSheets() { while (sheets.length) closeSheet(); }
  $('#scrim').onclick = closeAllSheets;

  // ---------------------------------------------------------------- settings
  function applyLook() {
    document.body.className = 'theme-' + S.theme + (S.strongs ? '' : ' no-strongs') + (S.redLetter ? ' red-letter' : '');
    document.documentElement.style.setProperty('--fs', S.fs + 'px');
  }

  // --------------------------------------------------------------------- tabs
  function setTab(t) {
    S.tab = t; save();
    $$('#tabs button').forEach(b => b.classList.toggle('on', b.dataset.tab === t));
    clearSelection();
    $('#top').classList.toggle('hidden', !(t === 'read' || t === 'study'));
    content.scrollTop = 0;
    ({ read: renderRead, study: renderStudy, search: renderSearch, notes: renderNotes, more: renderMore })[t]();
  }
  $$('#tabs button').forEach(b => b.onclick = () => setTab(b.dataset.tab));

  // --------------------------------------------------------------------- read
  let sel = new Set();
  function updateTop() {
    $('#refLabel').textContent = Books.name(S.b) + ' ' + S.c + (S.tab === 'study' ? ':' + S.v : '');
    $('#btnVersion').textContent = [S.bible, ...S.parallel].map(abbr).join(' + ') || 'No Bible';
  }

  function renderRead(scrollToVerse) {
    updateTop();
    const ids = [S.bible, ...S.parallel].filter(mod);
    if (!ids.length) { content.innerHTML = '<div class="empty">No Bible installed yet.<br><br>Go to More → My modules to add one.</div>'; return; }
    const chapters = ids.map(id => B('chapter', id, S.b, S.c) || []);
    const user = B('userChapter', S.b, S.c) || { notes: {}, hl: {}, bm: [] };
    const maxV = Math.max(0, ...chapters.map(ch => ch.length ? ch[ch.length - 1][0] : 0));
    const maps = chapters.map(ch => new Map(ch.map(r => [r[0], r[1]])));
    const multi = ids.length > 1;
    let h = `<div class="scripture"><div class="ch-head">${esc(Books.name(S.b))} ${S.c}</div>`;
    if (multi) h += `<div class="par-head" style="--cols:${ids.length}"><span></span>${ids.map(id => `<span>${esc(abbr(id))}</span>`).join('')}</div>`;
    if (!maxV) h += `<div class="empty">${esc(abbr(S.bible))} doesn't include ${esc(Books.name(S.b))} ${S.c}.</div>`;
    for (let v = 0; v <= maxV; v++) {
      if (!maps.some(m => m.has(v))) continue;
      const hl = user.hl[v] ? ' hl-' + user.hl[v] : '';
      const marks = (user.notes[v] ? '✎' : '') + (user.bm.includes(v) ? '★' : '');
      const vn = v ? `<span class="vn">${v}</span>` : '';
      if (!multi) {
        const rtl = mod(ids[0]).rtl ? ' rtl' : '';
        h += `<div class="vs${hl}${rtl}" data-v="${v}">${vn}<span class="vt">${maps[0].get(v) || ''}</span>${marks ? `<span class="marks">${marks}</span>` : ''}</div>`;
      } else {
        h += `<div class="vs par${hl}" data-v="${v}" style="--cols:${ids.length}">${vn || '<span></span>'}${ids.map((id, i) =>
          `<div class="pv${mod(id).rtl ? ' rtl' : ''}"><span class="lab">${esc(abbr(id))}</span><span class="vt">${maps[i].get(v) || '<span class="muted">—</span>'}</span></div>`).join('')}${marks ? `<span class="marks">${marks}</span>` : ''}</div>`;
      }
    }
    h += `</div><div class="ch-nav"><button id="prevCh">‹ Previous</button><button id="nextCh">Next ›</button></div>`;
    content.innerHTML = h;
    $('#prevCh').onclick = () => step(-1);
    $('#nextCh').onclick = () => step(1);
    if (scrollToVerse) {
      const el = $(`.vs[data-v="${scrollToVerse}"]`, content);
      if (el) { el.scrollIntoView({ block: 'center' }); el.classList.add('flash'); }
    } else content.scrollTop = 0;
  }

  function step(d) {
    let b = S.b, c = S.c + d;
    if (c < 1) { b--; if (b < 1) b = 66; c = Books.chapters(b); }
    else if (c > Books.chapters(b)) { b++; if (b > 66) b = 1; c = 1; }
    S.b = b; S.c = c; S.v = 1; save();
    clearSelection(); renderRead();
  }

  function goTo(b, c, v) {
    S.b = b; S.c = c || 1; S.v = v || 1; save();
    closeAllSheets();
    if (S.tab !== 'read') { S.tab = 'read'; $$('#tabs button').forEach(x => x.classList.toggle('on', x.dataset.tab === 'read')); $('#top').classList.remove('hidden'); }
    clearSelection();
    renderRead(v || 0);
  }

  // swipe for chapters
  let touch = null;
  content.addEventListener('touchstart', e => { if (S.tab === 'read' && e.touches.length === 1) touch = { x: e.touches[0].clientX, y: e.touches[0].clientY, t: Date.now() }; }, { passive: true });
  content.addEventListener('touchend', e => {
    if (!touch || S.tab !== 'read') return;
    const t = e.changedTouches[0], dx = t.clientX - touch.x, dy = t.clientY - touch.y;
    if (Math.abs(dx) > 90 && Math.abs(dy) < 60 && Date.now() - touch.t < 600 && !window.getSelection().toString()) step(dx < 0 ? 1 : -1);
    touch = null;
  }, { passive: true });

  // verse selection
  function clearSelection() { sel.clear(); $$('.vs.sel').forEach(e => e.classList.remove('sel')); $('#selbar').classList.add('hidden'); }
  function toggleVerse(v) {
    if (sel.has(v)) sel.delete(v); else sel.add(v);
    $$('.vs', content).forEach(e => e.classList.toggle('sel', sel.has(+e.dataset.v)));
    if (!sel.size) { $('#selbar').classList.add('hidden'); return; }
    const vs = [...sel].sort((a, b) => a - b);
    $('#selLabel').textContent = Books.name(S.b) + ' ' + S.c + ':' + vs.join(', ');
    $('#selbar').classList.remove('hidden');
    S.v = vs[0]; save();
  }
  const selected = () => [...sel].sort((a, b) => a - b);

  $('#selbar').addEventListener('click', e => {
    const act = e.target.closest('button') && e.target.closest('button').dataset.act;
    if (!act) return;
    const vs = selected();
    if (!vs.length) return;
    if (act === 'clear') return clearSelection();
    if (act === 'study') { S.v = vs[0]; save(); return setTab('study'); }
    if (act === 'compare') return showCompare(S.b, S.c, vs[0]);
    if (act === 'highlight') return pickHighlight(vs);
    if (act === 'note') return editNote(S.b, S.c, vs[0]);
    if (act === 'bookmark') {
      let on = false;
      vs.forEach(v => { on = B('toggleBookmark', S.b, S.c, v); });
      toast(on ? 'Bookmarked' : 'Bookmark removed'); clearSelection(); return renderRead(vs[0]);
    }
    if (act === 'copy') {
      const text = vs.map(v => { const h = B('verseText', S.bible, S.b, S.c, v); return (vs.length > 1 ? v + ' ' : '') + plainOf(h || ''); }).join(' ');
      const ref = Books.name(S.b) + ' ' + S.c + ':' + (vs.length > 1 ? vs[0] + '-' + vs[vs.length - 1] : vs[0]);
      B('share', `"${text}" — ${ref} (${abbr(S.bible)})`);
      clearSelection();
    }
  });

  function pickHighlight(vs) {
    const colors = ['yellow', 'green', 'blue', 'pink', 'orange'];
    const body = document.createElement('div');
    body.innerHTML = `<div class="colors">${colors.map(c => `<button data-c="${c}" style="background:var(--hl-${c})" aria-label="${c}"></button>`).join('')}</div><div class="btn-row"><button class="btn" data-c="">Remove highlight</button></div>`;
    body.onclick = e => {
      const b = e.target.closest('button'); if (!b) return;
      vs.forEach(v => B('setHighlight', S.b, S.c, v, b.dataset.c));
      closeAllSheets(); clearSelection(); renderRead(vs[0]);
    };
    openSheet('Highlight', body);
  }

  function editNote(b, c, v, after) {
    const user = B('userChapter', b, c) || { notes: {} };
    const body = document.createElement('div');
    body.innerHTML = `<div class="scripture small" style="margin-bottom:10px">${B('verseText', S.bible, b, c, v) || ''}</div><textarea placeholder="Write your note…"></textarea><div class="btn-row"><button class="btn primary save">Save</button><button class="btn danger del">Delete note</button></div>`;
    const ta = $('textarea', body); ta.value = user.notes[v] || '';
    $('.save', body).onclick = () => { B('setNote', b, c, v, ta.value.trim()); closeAllSheets(); toast('Note saved'); clearSelection(); (after || (() => renderRead(v)))(); };
    $('.del', body).onclick = () => { B('setNote', b, c, v, ''); closeAllSheets(); clearSelection(); (after || (() => renderRead(v)))(); };
    openSheet('Note — ' + Books.refText(b, c, v), body);
    setTimeout(() => ta.focus(), 200);
  }

  function showCompare(b, c, v) {
    const bibles = byType('bible');
    const body = document.createElement('div');
    body.innerHTML = bibles.map(m => {
      const h = B('verseText', m.id, b, c, v);
      return `<div class="card"><h3>${esc(m.abbr)} <span class="small">· ${esc(m.title)}</span></h3><div class="scripture${m.rtl ? ' rtl' : ''}">${h || '<span class="muted">Not in this Bible</span>'}</div></div>`;
    }).join('');
    openSheet('Compare ' + Books.refText(b, c, v), body);
  }

  // ------------------------------------------------- reference + Strong's popups
  function showRef(ref) {
    const [b, c, v, c2, v2] = ref.split('.').map(Number);
    const body = document.createElement('div');
    let h = '';
    for (let ch = c; ch <= (c2 || c); ch++) {
      const rows = B('chapter', S.bible, b, ch) || [];
      rows.forEach(([vn, html]) => {
        const inRange = (ch > c || vn >= v) && (ch < (c2 || c) || vn <= (v2 || v));
        if (inRange) h += `<div class="vs"><span class="vn">${vn}</span>${html}</div>`;
      });
    }
    body.innerHTML = `<div class="scripture">${h || '<span class="muted">Not found in ' + esc(abbr(S.bible)) + '</span>'}</div><div class="btn-row"><button class="btn primary go">Open in Bible</button></div>`;
    $('.go', body).onclick = () => goTo(b, c, v);
    openSheet(Books.refText(b, c, v, v2, c2) + ' (' + abbr(S.bible) + ')', body);
  }

  function showStrongs(key) {
    const k = key.toUpperCase().replace(/^([HG])0*/, '$1');
    const dicts = byType('dictionary').filter(m => m.strongs);
    const order = [S.strongsDict, ...dicts.map(d => d.id).filter(id => id !== S.strongsDict)];
    let html = '', used = '';
    for (const id of order) { if (!id) continue; html = B('entry', id, k); if (html) { used = id; break; } }
    const body = document.createElement('div');
    if (html) {
      body.appendChild(modHtml(html));
      const more = document.createElement('div');
      more.className = 'btn-row';
      more.innerHTML = `<button class="btn find">Find ${esc(k)} in the Bible</button>`;
      $('.find', more).onclick = () => { closeAllSheets(); setTab('search'); runSearch(k, 'all', true); };
      body.appendChild(more);
    } else body.innerHTML = `<div class="empty">No dictionary entry for ${esc(k)}.<br>Add a Strong's dictionary under More → My modules.</div>`;
    openSheet(k + (used ? ' · ' + abbr(used) : ''), body);
  }

  document.addEventListener('click', e => {
    const s = e.target.closest('sup.s, a.sref');
    if (s) { e.preventDefault(); e.stopPropagation(); return showStrongs(s.dataset.s || s.textContent.trim()); }
    const r = e.target.closest('a.ref');
    if (r) { e.preventDefault(); e.stopPropagation(); return showRef(r.dataset.ref); }
    const a = e.target.closest('a[href]');
    if (a) { e.preventDefault(); return; } // module links are not navigable
    if (S.tab === 'read' && content.contains(e.target)) {
      const vs = e.target.closest('.vs');
      if (vs && !window.getSelection().toString()) toggleVerse(+vs.dataset.v);
    }
  }, true);

  // ---------------------------------------------------- book/chapter picker
  $('#btnRef').onclick = () => {
    const body = document.createElement('div');
    const books = n => Books.list.filter(b => n ? b.n > 39 : b.n <= 39).map(b => `<button data-b="${b.n}" class="${b.n === S.b ? 'on' : ''}">${esc(b.short)}</button>`).join('');
    body.innerHTML = `<div class="field"><input type="text" placeholder="Go to… e.g. John 3:16" enterkeyhint="go"><button class="btn primary">Go</button></div>
      <div class="testament">Old Testament</div><div class="grid">${books(false)}</div>
      <div class="testament">New Testament</div><div class="grid">${books(true)}</div>`;
    const inp = $('input', body);
    const go = () => { const r = Books.parseRef(inp.value); if (r) goTo(r.b, r.c, r.v); else toast('Try something like "John 3:16"'); };
    $('.field .btn', body).onclick = go;
    inp.onkeydown = e => { if (e.key === 'Enter') go(); };
    body.addEventListener('click', e => {
      const bb = e.target.closest('button[data-b]'); if (!bb) return;
      const b = +bb.dataset.b, n = Books.chapters(b);
      if (n === 1) return goTo(b, 1, 0);
      const cb = document.createElement('div');
      cb.innerHTML = `<div class="grid nums">${Array.from({ length: n }, (_, i) => `<button data-c="${i + 1}" class="${b === S.b && i + 1 === S.c ? 'on' : ''}">${i + 1}</button>`).join('')}</div>`;
      cb.onclick = ev => { const c = ev.target.closest('button[data-c]'); if (c) goTo(b, +c.dataset.c, 0); };
      openSheet(Books.name(b), cb);
    });
    openSheet('Go to', body);
  };

  // ---------------------------------------------------------- version picker
  $('#btnVersion').onclick = () => {
    const bibles = byType('bible');
    const body = document.createElement('div');
    const draw = () => {
      body.innerHTML = `<p class="muted small">Tap a Bible to read it. Tick others to show them side by side.</p><ul class="list">${bibles.map(m => `
        <li><div class="row"><button class="grow" data-main="${esc(m.id)}" style="text-align:left"><b>${esc(m.abbr)}</b> ${m.id === S.bible ? '✓' : ''}<div class="sub">${esc(m.title)}</div></button>
        ${m.id === S.bible ? '' : `<label class="chk"><input type="checkbox" data-par="${esc(m.id)}" ${S.parallel.includes(m.id) ? 'checked' : ''}> Side by side</label>`}</div></li>`).join('')}</ul>`;
    };
    draw();
    body.addEventListener('click', e => {
      const m = e.target.closest('[data-main]');
      if (m) { S.bible = m.dataset.main; S.parallel = S.parallel.filter(x => x !== S.bible); save(); closeAllSheets(); renderCurrent(); }
    });
    body.addEventListener('change', e => {
      const p = e.target.dataset.par; if (!p) return;
      if (e.target.checked) { if (S.parallel.length >= 3) { e.target.checked = false; return toast('Up to 4 Bibles at once'); } S.parallel.push(p); }
      else S.parallel = S.parallel.filter(x => x !== p);
      save(); renderCurrent();
    });
    openSheet('Bibles', body);
  };

  // ------------------------------------------------------------ text settings
  $('#btnText').onclick = () => {
    const body = document.createElement('div');
    body.innerHTML = `
      <div class="card"><h3>Text size</h3><input type="range" min="14" max="32" step="1" value="${S.fs}"><div class="scripture" style="margin-top:6px">In the beginning was the Word.</div></div>
      <div class="card"><h3>Theme</h3><div class="seg">${['light', 'sepia', 'dark'].map(t => `<button data-t="${t}" class="${S.theme === t ? 'on' : ''}">${t[0].toUpperCase() + t.slice(1)}</button>`).join('')}</div></div>
      <div class="card"><label class="chk"><input type="checkbox" id="optS" ${S.strongs ? 'checked' : ''}> Show Strong's numbers</label>
      <label class="chk"><input type="checkbox" id="optR" ${S.redLetter ? 'checked' : ''}> Words of Jesus in red</label></div>`;
    $('input[type=range]', body).oninput = e => { S.fs = +e.target.value; applyLook(); save(); };
    body.addEventListener('click', e => { const t = e.target.closest('[data-t]'); if (t) { S.theme = t.dataset.t; applyLook(); save(); $$('[data-t]', body).forEach(x => x.classList.toggle('on', x === t)); } });
    $('#optS', body).onchange = e => { S.strongs = e.target.checked; applyLook(); save(); };
    $('#optR', body).onchange = e => { S.redLetter = e.target.checked; applyLook(); save(); };
    openSheet('Text & display', body);
  };

  // -------------------------------------------------------------------- study
  function renderStudy() {
    updateTop();
    const cms = byType('commentary');
    const dicts = byType('dictionary');
    const ref = Books.refText(S.b, S.c, S.v);
    let h = `<div class="card"><div class="scripture">${B('verseText', S.bible, S.b, S.c, S.v) || ''}</div>
      <div class="btn-row"><button class="btn" id="stPrev">‹ Verse</button><button class="btn" id="stNext">Verse ›</button><button class="btn" id="stNote">✎ My note</button><button class="btn" id="stCmp">Compare</button></div></div>`;
    const note = (B('userChapter', S.b, S.c) || { notes: {} }).notes[S.v];
    if (note) h += `<div class="card"><h3>My note</h3><div class="mod">${esc(note).replace(/\n/g, '<br>')}</div></div>`;
    h += `<div class="page-title" style="font-size:18px">Commentary</div>`;
    if (!cms.length) h += `<div class="card muted">No commentaries yet. Add one under More → My modules.</div>`;
    else h += `<div class="chips">${cms.map(m => `<button class="chip ${m.id === S.commentary ? 'on' : ''}" data-cm="${esc(m.id)}">${esc(m.abbr)}</button>`).join('')}</div><div id="cmBody"></div>`;
    h += `<div class="page-title" style="font-size:18px">Dictionary</div>`;
    if (!dicts.length) h += `<div class="card muted">No dictionaries installed.</div>`;
    else h += `<div class="chips">${dicts.map(m => `<button class="chip ${m.id === S.dict ? 'on' : ''}" data-dc="${esc(m.id)}">${esc(m.abbr)}</button>`).join('')}</div>
      <div class="field"><input type="search" id="dictQ" placeholder="Look up a word or Strong's number"></div><ul class="list" id="dictKeys"></ul>`;
    content.innerHTML = h;
    $('#stPrev').onclick = () => moveVerse(-1);
    $('#stNext').onclick = () => moveVerse(1);
    $('#stNote').onclick = () => editNote(S.b, S.c, S.v, renderStudy);
    $('#stCmp').onclick = () => showCompare(S.b, S.c, S.v);
    $$('[data-cm]').forEach(b => b.onclick = () => { S.commentary = b.dataset.cm; save(); renderStudy(); });
    $$('[data-dc]').forEach(b => b.onclick = () => { S.dict = b.dataset.dc; save(); renderStudy(); });
    if (cms.length) drawCommentary();
    if (dicts.length) {
      const q = $('#dictQ');
      let tm;
      q.oninput = () => { clearTimeout(tm); tm = setTimeout(() => drawKeys(q.value), 150); };
      q.onkeydown = e => { if (e.key === 'Enter') openEntry(S.dict, q.value.trim()); };
    }
    document.title = ref;
  }

  function moveVerse(d) {
    const rows = B('chapter', S.bible, S.b, S.c) || [];
    const vs = rows.map(r => r[0]);
    const i = vs.indexOf(S.v);
    if (d > 0 && i >= 0 && i < vs.length - 1) S.v = vs[i + 1];
    else if (d < 0 && i > 0) S.v = vs[i - 1];
    else {
      let b = S.b, c = S.c + d;
      if (c < 1) { b = b > 1 ? b - 1 : 66; c = Books.chapters(b); } else if (c > Books.chapters(b)) { b = b < 66 ? b + 1 : 1; c = 1; }
      S.b = b; S.c = c;
      const r2 = B('chapter', S.bible, b, c) || [];
      S.v = d > 0 ? (r2.length ? r2[0][0] : 1) : (r2.length ? r2[r2.length - 1][0] : 1);
    }
    save(); renderStudy();
  }

  function drawCommentary() {
    const box = $('#cmBody');
    const rows = B('comments', S.commentary, S.b, S.c, S.v) || [];
    const verse = rows.filter(r => r.v1 > 0), chap = rows.filter(r => r.ch1 > 0 && r.v1 === 0), book = rows.filter(r => r.ch1 === 0);
    box.innerHTML = '';
    const add = (title, list, open) => {
      if (!list.length) return;
      const card = document.createElement('div'); card.className = 'card';
      const d = document.createElement('details'); if (open) d.open = true;
      d.innerHTML = `<summary><b>${esc(title)}</b></summary>`;
      list.forEach(r => d.appendChild(modHtml(r.html)));
      card.appendChild(d); box.appendChild(card);
    };
    add(verse.length ? verse.map(r => Books.refText(S.b, r.ch1, r.v1, r.v2, r.ch2)).join(', ') : '', verse, true);
    add('On ' + Books.name(S.b) + ' ' + S.c, chap, !verse.length);
    add('Introduction to ' + Books.name(S.b), book, false);
    if (!rows.length) box.innerHTML = `<div class="card muted">${esc(abbr(S.commentary))} has nothing on ${esc(Books.refText(S.b, S.c, S.v))}.</div>`;
  }

  function drawKeys(q) {
    const ul = $('#dictKeys'); if (!ul) return;
    if (!q.trim()) { ul.innerHTML = ''; return; }
    const keys = B('entryKeys', S.dict, q.trim(), 40) || [];
    ul.innerHTML = keys.map(k => `<li><button data-k="${esc(k)}"><span class="grow">${esc(k)}</span><span class="chev">›</span></button></li>`).join('') || '<li class="muted small" style="padding:10px 4px">No matches</li>';
    ul.onclick = e => { const b = e.target.closest('[data-k]'); if (b) openEntry(S.dict, b.dataset.k); };
  }

  function openEntry(dictId, key) {
    if (!key) return;
    if (/^[HG]0*\d{1,5}$/i.test(key) && mod(dictId) && mod(dictId).strongs) return showStrongs(key);
    const html = B('entry', dictId, key);
    if (!html) return toast('Not found: ' + key);
    const body = document.createElement('div'); body.appendChild(modHtml(html));
    openSheet(key + ' · ' + abbr(dictId), body);
  }

  // ------------------------------------------------------------------- search
  let lastSearch = null;
  function renderSearch() {
    const bibles = byType('bible');
    content.innerHTML = `<div class="page-title">Search</div>
      <div class="field"><input type="search" id="q" placeholder="Words, a phrase in quotes, or H7225" enterkeyhint="search"><button class="btn primary" id="go">Search</button></div>
      <div class="field"><select id="sv">${bibles.map(m => `<option value="${esc(m.id)}" ${m.id === S.bible ? 'selected' : ''}>${esc(m.abbr)}</option>`).join('')}</select>
      <select id="ss"><option value="all">Whole Bible</option><option value="ot">Old Testament</option><option value="nt">New Testament</option><option value="book">${esc(Books.name(S.b))}</option></select></div>
      <label class="chk small"><input type="checkbox" id="sw" checked> Whole words only</label>
      <div id="sres"></div>`;
    const go = () => runSearch($('#q').value, $('#ss').value, $('#sw').checked, $('#sv').value);
    $('#go').onclick = go;
    $('#q').onkeydown = e => { if (e.key === 'Enter') { e.target.blur(); go(); } };
    if (lastSearch) {
      $('#q').value = lastSearch.q; $('#ss').value = lastSearch.scope; $('#sw').checked = lastSearch.whole;
      drawResults(lastSearch);
    } else if (S.searchHist.length) {
      $('#sres').innerHTML = `<div class="testament">Recent</div><div class="chips" style="flex-wrap:wrap">${S.searchHist.map(q => `<button class="chip" data-q="${esc(q)}">${esc(q)}</button>`).join('')}</div>`;
      $$('[data-q]').forEach(b => b.onclick = () => { $('#q').value = b.dataset.q; go(); });
    }
  }

  function runSearch(q, scope, whole, bible) {
    q = (q || '').trim();
    if (!q) return;
    bible = bible || S.bible;
    if ($('#q')) $('#q').value = q;
    const sc = scope === 'book' ? 'book:' + S.b : scope;
    const res = B('search', bible, q, sc, !!whole, 500) || { total: 0, results: [] };
    S.searchHist = [q, ...S.searchHist.filter(x => x !== q)].slice(0, 12); save();
    lastSearch = { q, scope, whole, bible, res };
    drawResults(lastSearch);
  }

  function drawResults(ls) {
    const box = $('#sres'); if (!box) return;
    const { res, q } = ls;
    const strong = /^[HG]\d+$/i.test(q);
    const words = strong ? [] : (q.match(/"[^"]+"|\S+/g) || []).map(w => w.replace(/"/g, '').toLowerCase()).filter(Boolean);
    const mark = html => {
      const d = document.createElement('div'); d.innerHTML = html;
      if (strong) { $$('sup.s', d).forEach(s => { if (s.textContent.toUpperCase() === q.toUpperCase().replace(/^([HG])0*/, '$1')) { s.style.cssText = 'background:var(--hl-yellow);color:var(--fg)'; const prev = s.previousSibling; if (prev && prev.nodeType === 3) { const m = /(\S+)\s*$/.exec(prev.nodeValue); if (m) { const mk = document.createElement('mark'); mk.textContent = m[1]; prev.nodeValue = prev.nodeValue.slice(0, m.index); s.before(mk); } } } }); return d.innerHTML; }
      if (!words.length) return html;
      const re = new RegExp('(' + words.map(w => w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')', 'gi');
      const walker = document.createTreeWalker(d, NodeFilter.SHOW_TEXT, { acceptNode: n => n.parentElement.closest('sup.s') ? 2 : 1 });
      const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
      nodes.forEach(n => {
        if (!re.test(n.nodeValue)) return; re.lastIndex = 0;
        const span = document.createElement('span'); span.innerHTML = esc(n.nodeValue).replace(re, '<mark>$1</mark>'); n.replaceWith(...span.childNodes);
      });
      return d.innerHTML;
    };
    box.innerHTML = `<p class="muted small">${res.total} verse${res.total === 1 ? '' : 's'} found${res.total > res.results.length ? ' — showing the first ' + res.results.length : ''} in ${esc(abbr(ls.bible))}</p>` +
      res.results.map(([b, c, v, h]) => `<div class="result" data-r="${b}.${c}.${v}"><div class="rr">${esc(Books.refText(b, c, v))}</div><div class="scripture small">${mark(h)}</div></div>`).join('');
    box.onclick = e => { if (e.target.closest('sup.s')) return; const r = e.target.closest('[data-r]'); if (r) { const [b, c, v] = r.dataset.r.split('.').map(Number); goTo(b, c, v); } };
  }

  // -------------------------------------------------------------------- notes
  let notesTab = 'notes';
  function renderNotes() {
    const items = B('userList', notesTab) || [];
    const labels = { notes: 'Notes', bookmarks: 'Bookmarks', highlights: 'Highlights' };
    let h = `<div class="page-title">My study</div><div class="seg">${Object.keys(labels).map(k => `<button data-nt="${k}" class="${k === notesTab ? 'on' : ''}">${labels[k]}</button>`).join('')}</div>`;
    if (!items.length) h += `<div class="empty">${notesTab === 'notes' ? 'No notes yet. Tap a verse, then Note.' : notesTab === 'bookmarks' ? 'No bookmarks yet. Tap a verse, then Bookmark.' : 'No highlights yet. Tap a verse, then Highlight.'}</div>`;
    else h += items.map(it => `<div class="result" data-r="${it.b}.${it.c}.${it.v}"><div class="rr">${esc(Books.refText(it.b, it.c, it.v))}${it.color ? ` <span style="display:inline-block;width:12px;height:12px;border-radius:50%;background:var(--hl-${esc(it.color)})"></span>` : ''}</div>
      <div class="${notesTab === 'notes' ? 'mod' : 'scripture small'}">${notesTab === 'notes' ? esc(it.text).replace(/\n/g, '<br>') : (B('verseText', S.bible, it.b, it.c, it.v) || '')}</div></div>`).join('');
    h += `<div class="card" style="margin-top:20px"><h3>Back up</h3><p class="small muted">Save your notes, highlights and bookmarks to a file (Google Drive, email…), or bring them back from one.</p><div class="btn-row"><button class="btn" id="bk">Back up now</button><button class="btn" id="rs">Restore…</button></div></div>`;
    content.innerHTML = h;
    $$('[data-nt]').forEach(b => b.onclick = () => { notesTab = b.dataset.nt; renderNotes(); });
    $$('.result', content).forEach(r => r.onclick = e => { if (e.target.closest('sup.s,a')) return; const [b, c, v] = r.dataset.r.split('.').map(Number); goTo(b, c, v); });
    $('#bk').onclick = () => B('exportBackup');
    $('#rs').onclick = () => B('restoreBackup');
  }
  window.onRestoreDone = json => { const r = JSON.parse(json); toast(r.ok ? `Restored ${r.count} items` : ('Restore failed: ' + r.error), 3500); if (S.tab === 'notes') renderNotes(); };

  // --------------------------------------------------------------------- more
  function renderMore() {
    const books = byType('book'), imgs = byType('images');
    content.innerHTML = `<div class="page-title">More</div>
      <ul class="list">
        ${books.map(m => `<li><button data-book="${esc(m.id)}"><span class="ti">📘</span><span class="grow">${esc(m.title)}<div class="sub">Book</div></span><span class="chev">›</span></button></li>`).join('')}
        ${imgs.map(m => `<li><button data-img="${esc(m.id)}"><span class="ti">🗺️</span><span class="grow">${esc(m.title)}<div class="sub">Pictures & maps</div></span><span class="chev">›</span></button></li>`).join('')}
        <li><button id="mPlan"><span class="ti">📅</span><span class="grow">Bible in a year<div class="sub">Reading plan</div></span><span class="chev">›</span></button></li>
        <li><button id="mMods"><span class="ti">🧩</span><span class="grow">My modules<div class="sub">${MODS.length} installed · add e-Sword or MySword files</div></span><span class="chev">›</span></button></li>
        <li><button id="mText"><span class="ti">Aa</span><span class="grow">Text & display</span><span class="chev">›</span></button></li>
        <li><button id="mAbout"><span class="ti">ℹ️</span><span class="grow">About</span><span class="chev">›</span></button></li>
      </ul>`;
    $$('[data-book]').forEach(b => b.onclick = () => openBook(b.dataset.book));
    $$('[data-img]').forEach(b => b.onclick = () => openImages(b.dataset.img));
    $('#mPlan').onclick = openPlan;
    $('#mMods').onclick = openModules;
    $('#mText').onclick = () => $('#btnText').click();
    $('#mAbout').onclick = openAbout;
  }

  function openBook(id) {
    const secs = B('sections', id) || [];
    const body = document.createElement('div');
    body.innerHTML = `<ul class="list">${secs.map(s => `<li><button data-s="${s.id}"><span class="grow">${esc(s.title || 'Section ' + s.id)}</span><span class="chev">›</span></button></li>`).join('')}</ul>`;
    body.onclick = e => { const b = e.target.closest('[data-s]'); if (b) openSection(id, secs, secs.findIndex(s => String(s.id) === b.dataset.s)); };
    openSheet((mod(id) || {}).title || 'Book', body, { full: true });
  }
  function openSection(id, secs, i) {
    const s = B('section', id, secs[i].id);
    const body = document.createElement('div');
    body.appendChild(modHtml(s.html));
    const nav = document.createElement('div'); nav.className = 'ch-nav';
    nav.innerHTML = `<button class="p" ${i ? '' : 'disabled'}>‹ Previous</button><button class="n" ${i < secs.length - 1 ? '' : 'disabled'}>Next ›</button>`;
    $('.p', nav).onclick = () => { closeSheet(); openSection(id, secs, i - 1); };
    $('.n', nav).onclick = () => { closeSheet(); openSection(id, secs, i + 1); };
    body.appendChild(nav);
    openSheet(s.title || '', body, { full: true });
  }

  function openImages(id) {
    const list = B('images', id) || [];
    const body = document.createElement('div');
    body.innerHTML = `<ul class="list">${list.map(s => `<li><button data-i="${s.id}"><span class="grow">${esc(s.title)}</span><span class="chev">›</span></button></li>`).join('')}</ul>`;
    body.onclick = e => {
      const b = e.target.closest('[data-i]'); if (!b) return;
      const v = document.createElement('div'); v.className = 'imgview';
      v.innerHTML = `<img alt=""><p class="muted small">Tap the picture to zoom</p>`;
      $('img', v).src = B('image', id, +b.dataset.i);
      $('img', v).onclick = () => v.classList.toggle('zoom');
      openSheet(b.textContent.trim(), v, { full: true });
    };
    openSheet((mod(id) || {}).title || 'Pictures', body, { full: true });
  }

  // reading plan: the whole Bible in 365 days, in order
  const PLAN = (() => {
    const all = []; Books.list.forEach(b => { for (let c = 1; c <= b.chapters; c++) all.push([b.n, c]); });
    const days = [];
    for (let d = 0; d < 365; d++) days.push(all.slice(Math.floor(d * all.length / 365), Math.floor((d + 1) * all.length / 365)));
    return days;
  })();
  function planLabel(chs) {
    const parts = []; let cur = null;
    chs.forEach(([b, c]) => { if (cur && cur.b === b && cur.to === c - 1) cur.to = c; else { cur = { b, from: c, to: c }; parts.push(cur); } });
    return parts.map(p => Books.name(p.b) + ' ' + p.from + (p.to > p.from ? '–' + p.to : ''));
  }
  function openPlan() {
    const body = document.createElement('div');
    const draw = () => {
      const done = new Set(B('planDone', 'year') || []);
      let today = 0;
      if (S.planStart) today = Math.floor((Date.now() - new Date(S.planStart + 'T00:00:00').getTime()) / 86400000) + 1;
      body.innerHTML = `<div class="card"><b>${done.size} of 365 days read</b><div class="small muted">${S.planStart ? 'Started ' + esc(S.planStart) + (today >= 1 && today <= 365 ? ' · today is day ' + today : '') : 'Pick a start day to see where you should be.'}</div>
        <div class="btn-row"><button class="btn" id="pStart">${S.planStart ? 'Restart from today' : 'Start today'}</button>${today >= 1 && today <= 365 ? '<button class="btn" id="pToday">Jump to today</button>' : ''}</div></div>` +
        PLAN.map((chs, i) => `<div class="plan-day ${i + 1 === today ? 'today' : ''}" id="pd${i + 1}"><input type="checkbox" data-d="${i + 1}" ${done.has(i + 1) ? 'checked' : ''} aria-label="Day ${i + 1} done"><div class="grow"><b>Day ${i + 1}</b><br>${planLabel(chs).map((l, j) => `<a data-go="${i}.${j}">${esc(l)}</a>`).join(' ')}</div></div>`).join('');
      const st = $('#pStart', body); st.onclick = () => { if (S.planStart && !confirm('Start the plan over from today? Ticked days stay ticked.')) return; const d = new Date(); S.planStart = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); save(); draw(); };
      const tj = $('#pToday', body); if (tj) tj.onclick = () => $('#pd' + today, body).scrollIntoView({ block: 'center' });
    };
    draw();
    body.addEventListener('change', e => { const d = e.target.dataset.d; if (d) B('setPlanDay', 'year', +d, e.target.checked); });
    body.addEventListener('click', e => {
      const a = e.target.closest('[data-go]'); if (!a) return;
      const [i] = a.dataset.go.split('.').map(Number);
      const first = PLAN[i].find(([b]) => Books.name(b) === a.textContent.split(' ').slice(0, -1).join(' ')) || PLAN[i][0];
      goTo(first[0], first[1], 0);
    });
    openSheet('Bible in a year', body, { full: true });
  }

  // ------------------------------------------------------------ module manager
  const TYPE_LABEL = { bible: 'Bible', commentary: 'Commentary', dictionary: 'Dictionary', book: 'Book', images: 'Pictures' };
  let importLog = '';
  function openModules() {
    const body = document.createElement('div');
    const draw = () => {
      body.innerHTML = `<div class="card"><h3>Add modules</h3><p class="small muted">Pick e-Sword files (.bbl, .cmt, .dct, .top, .map and the newer x/i versions) or MySword .mybible files. You can also pick a whole folder, like a copy of your e-Sword folder.</p>
        <div class="btn-row"><button class="btn primary" id="addF">Add files</button><button class="btn" id="addD">Add a folder</button></div><div class="progress" id="prog"></div><div id="ilog">${importLog}</div></div>` +
        Object.keys(TYPE_LABEL).map(t => {
          const ms = byType(t); if (!ms.length) return '';
          return `<div class="testament">${TYPE_LABEL[t]}${ms.length > 1 ? 's' : ''}</div><ul class="list">${ms.map(m => `<li><div class="row"><span class="grow"><b>${esc(m.abbr)}</b> · ${esc(m.title)}${m.builtin ? '<div class="sub">Built in</div>' : ''}</span>${m.builtin ? '' : `<button class="btn danger" data-del="${esc(m.id)}">Remove</button>`}</div></li>`).join('')}</ul>`;
        }).join('');
      $('#addF', body).onclick = () => { $('#prog', body).textContent = 'Choose files…'; B('importFiles'); };
      $('#addD', body).onclick = () => { $('#prog', body).textContent = 'Choose a folder…'; B('importFolder'); };
    };
    draw();
    body.addEventListener('click', e => {
      const d = e.target.closest('[data-del]'); if (!d) return;
      if (!confirm('Remove ' + abbr(d.dataset.del) + ' from this phone?')) return;
      B('deleteModule', d.dataset.del); loadModules(); draw();
    });
    window.onImportProgress = msg => { const p = $('#prog', body); if (p) p.textContent = msg; };
    window.onImportDone = json => {
      const res = JSON.parse(json);
      loadModules();
      importLog = res.length ? '<ul class="list small">' + res.map(r => `<li style="padding:6px 0">${r.ok ? '<span class="ok">✓</span>' : '<span class="bad">✕</span>'} ${esc(r.file)}${r.ok ? ` — ${esc(TYPE_LABEL[r.type] || r.type)}: ${esc(r.title)}` : ` — ${esc(r.error)}`}</li>`).join('') + '</ul>' : '';
      draw();
      const ok = res.filter(r => r.ok).length;
      toast(res.length ? `Added ${ok} of ${res.length}` : 'Nothing added');
    };
    openSheet('My modules', body, { full: true });
  }

  function openAbout() {
    const body = document.createElement('div');
    let ver = ''; try { ver = B('version') || ''; } catch (e) { }
    body.innerHTML = `<div class="mod"><p><b>My Bible</b> ${esc(ver)}</p><p>Your own Bible study app. It reads unlocked e-Sword modules (old and new formats) and MySword modules, and keeps your notes on this phone.</p>
      <p class="small"><b>Built-in texts</b><br>King James Version — public domain. Text from the Open Bibles project; Strong's number placement from the "kjv" JSON project.<br>
      Strong's Hebrew and Greek Dictionaries — James Strong (public domain); digital edition © Open Scriptures, CC-BY-SA.</p>
      <p class="small muted">Locked (password-protected) modules can't be opened by this app.</p></div>`;
    openSheet('About', body);
  }

  // ------------------------------------------------------------ back button
  window.onBack = () => {
    if (sheets.length) { closeSheet(); return true; }
    if (sel.size) { clearSelection(); return true; }
    if (S.tab !== 'read') { setTab('read'); return true; }
    return false;
  };

  function renderCurrent() { loadModules(); setTab(S.tab || 'read'); }

  // ------------------------------------------------------------------- start
  try {
    loadModules();
    applyLook();
    setTab(['read', 'study', 'search', 'notes', 'more'].includes(S.tab) ? S.tab : 'read');
  } catch (e) {
    content.innerHTML = '<div class="empty">Something went wrong starting up:<br>' + esc(e.message) + '</div>';
  }
  window.App = { goTo, runSearch, showStrongs, setTab, state: () => S };
})();
