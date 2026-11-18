/* sw.js — service worker: precache the app shell, serve everything offline.
 * Module .db files are not precached (too large); they are cached on demand by bridge.js
 * into 'mybible-mods-v1' and served from there here. */
var SHELL = 'mybible-shell-v4';
var SHELL_FILES = [
  'index.html', 'app.css', 'app.js', 'books.js', 'morph.js',
  'bridge-core.js', 'bridge.js', 'boot.js',
  'sql-wasm.js', 'sql-wasm.wasm', 'catalog.json',
  'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png'
];

self.addEventListener('install', function (e) {
  e.waitUntil(
    caches.open(SHELL).then(function (c) {
      // Cache best-effort; a single 404 shouldn't abort the whole install.
      return Promise.all(SHELL_FILES.map(function (f) {
        return c.add(new Request(f, { cache: 'reload' })).catch(function () {});
      }));
    }).then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (k) {
        if (k !== SHELL && k !== 'mybible-mods-v1') return caches.delete(k);
      }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (e) {
  var req = e.request;
  if (req.method !== 'GET') return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin) return;  // let cross-origin pass through

  // Navigations → app shell (offline-first).
  if (req.mode === 'navigate') {
    e.respondWith(caches.match('index.html').then(function (r) { return r || fetch(req); }));
    return;
  }

  // Module databases → cache-first (populated by bridge.js or here).
  if (url.pathname.indexOf('/modules/') >= 0) {
    e.respondWith(
      caches.match(req).then(function (hit) {
        if (hit) return hit;
        return fetch(req).then(function (net) {
          if (net && net.ok) { var cp = net.clone(); caches.open('mybible-mods-v1').then(function (c) { c.put(req, cp); }); }
          return net;
        });
      })
    );
    return;
  }

  // Everything else (shell assets) → cache-first, fall back to network and cache it.
  e.respondWith(
    caches.match(req).then(function (hit) {
      return hit || fetch(req).then(function (net) {
        if (net && net.ok && net.type === 'basic') { var cp = net.clone(); caches.open(SHELL).then(function (c) { c.put(req, cp); }); }
        return net;
      });
    })
  );
});
