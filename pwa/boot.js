/* boot.js — orchestrates first-run download, offline shell, and app startup for the web app. */
(function () {
  'use strict';

  // Downloaded + opened before the app shows. Ukrainian-first, plus KJV+ and both Strong's,
  // so the core reading + word-study flow works offline immediately. Everything else is
  // cached in the background afterwards and opens on demand.
  var CORE = ['kjv', 'ohienko', 'ukrstrong', 'strongs'];

  var boot = document.getElementById('boot');
  var bar = document.getElementById('bootBar');
  var statusEl = document.getElementById('bootStatus');
  var subEl = document.getElementById('bootSub');
  var errEl = document.getElementById('bootErr');

  function setBar(p) { bar.style.width = Math.max(0, Math.min(100, p)) + '%'; }
  function setStatus(t) { statusEl.textContent = t || ''; }

  function titleOf(id) {
    var c = (window.MyBible.catalog() || []).find(function (m) { return m.id === id; });
    return c ? c.title : id;
  }

  function maybeIosHint() {
    var ua = navigator.userAgent || '';
    var isIOS = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && 'ontouchend' in document);
    var standalone = window.navigator.standalone === true ||
      (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
    if (isIOS && !standalone && /Safari/.test(ua) && !/CriOS|FxiOS|EdgiOS/.test(ua)) {
      var h = document.getElementById('iosHint');
      if (h) setTimeout(function () { h.style.display = 'block'; }, 1200);
    }
  }

  function registerSW() {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('sw.js').catch(function () { /* non-fatal */ });
    }
  }

  function run() {
    registerSW();
    window.MyBible.init().then(function () {
      // Core set, plus whatever modules the saved settings already reference (returning users).
      var refs = window.MyBible.referencedIds(window.MyBible.savedState());
      var need = CORE.slice();
      refs.forEach(function (id) { if (need.indexOf(id) < 0) need.push(id); });

      subEl.innerHTML = 'Loading your library…<br>This only downloads once, then works offline.';
      return window.MyBible.ensure(need, function (done, total, id) {
        setBar(done / total * 92);
        setStatus('Loading ' + titleOf(id) + '  (' + done + '/' + total + ')');
      });
    }).then(function () {
      setBar(96); setStatus('Starting…');
      return window.MyBible.start();            // loads morph.js, books.js, app.js
    }).then(function () {
      setBar(100);
      setTimeout(function () { boot.classList.add('hide'); }, 250);
      setTimeout(function () { if (boot && boot.parentNode) boot.parentNode.removeChild(boot); }, 900);
      maybeIosHint();
      // Quietly cache the rest of the library for full offline use.
      if (window.requestIdleCallback) requestIdleCallback(bgCache); else setTimeout(bgCache, 2500);
    }).catch(function (e) {
      setStatus('');
      errEl.innerHTML = 'Couldn\'t finish loading.<br>' +
        (navigator.onLine ? 'Please reload to try again.' : 'You appear to be offline — connect once to finish the first-time setup.') +
        '<br><span style="color:#666">' + (e && e.message ? e.message : e) + '</span>';
    });
  }

  function bgCache() {
    window.MyBible.backgroundCacheAll(CORE, function (done, total) {
      // optional: could surface a subtle "N of M modules ready offline" indicator
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
