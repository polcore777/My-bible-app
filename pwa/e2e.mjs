/* End-to-end test: serve the assembled _site and drive it in real Chrome via Playwright.
 * Exercises boot (SW + sql.js + core download), reading, Strong's tap (morphology + Ukrainian
 * + English), Ukrainian peek, search, and on-demand loading of a non-core module. */
import { createRequire } from 'module';
import http from 'http';
import fs from 'fs';
import path from 'path';
const require = createRequire(import.meta.url);
const { chromium } = require('/opt/npm-tools/node_modules/playwright');

const ROOT = '/tmp/_site';
const PORT = 8777;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript',
  '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm', '.png': 'image/png', '.db': 'application/octet-stream', '.nojekyll': 'text/plain' };

const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const fp = path.join(ROOT, p);
  if (!fp.startsWith(ROOT) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) { res.writeHead(404); res.end('nf'); return; }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
  fs.createReadStream(fp).pipe(res);
});

const cyr = s => /[Ѐ-ӿ]/.test(s);
const grk = s => /[Ͱ-Ͽ]/.test(s);
let pass = 0, fail = 0;
const check = (name, cond, extra) => { cond ? pass++ : fail++; console.log(`  ${cond ? 'ok  ' : 'FAIL'} ${name}${extra && !cond ? '  — ' + extra : ''}`); };

await new Promise(r => server.listen(PORT, r));
const base = `http://127.0.0.1:${PORT}`;
const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
const page = await browser.newPage();
const errors = [];
page.on('pageerror', e => errors.push(e.message));
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });

try {
  await page.goto(`${base}/index.html`, { waitUntil: 'domcontentloaded' });

  // Boot completes: splash removed and verses rendered.
  await page.waitForSelectorModule?.(); // no-op guard
  await page.waitForFunction(() => !document.getElementById('boot') && document.querySelectorAll('#content .vs').length > 0, { timeout: 60000 });
  check('boot + first chapter rendered', true);

  const ref = await page.textContent('#refLabel');
  check('default reference is John 1', (ref || '').trim() === 'John 1', `got "${ref}"`);
  const ver = (await page.textContent('#btnVersion') || '').trim();
  check('version pill shows KJV', /KJV/i.test(ver), `got "${ver}"`);
  const nSup = await page.$$eval('#content sup.s', e => e.length);
  check('Strong\'s markers present in text', nSup > 0, `count=${nSup}`);

  // Jump to John 3:16 and tap the Strong's number G1063 (γάρ).
  await page.evaluate(() => window.App.goTo(43, 3, 16));
  await page.waitForFunction(() => (document.querySelector('#refLabel')?.textContent || '').trim() === 'John 3');
  const hasG1063 = await page.$$eval('#content sup.s', els => els.some(e => e.textContent.trim() === 'G1063'));
  check('John 3:16 contains G1063 marker', hasG1063);
  await page.evaluate(() => {
    const el = [...document.querySelectorAll('#content sup.s')].find(e => e.textContent.trim() === 'G1063');
    el.scrollIntoView(); el.click();
  });
  await page.waitForSelector('#sheets .sheet', { timeout: 8000 });
  const sheetTxt = await page.textContent('#sheets');
  check('Strong\'s sheet shows morphology/English (lemma γάρ)', grk(sheetTxt) || /γάρ|for/i.test(sheetTxt));
  check('Strong\'s sheet shows Ukrainian gloss (Cyrillic)', cyr(sheetTxt), 'no Cyrillic in sheet');
  // close sheet
  await page.evaluate(() => window.onBack && window.onBack());

  // Ukrainian peek: tap the peek marker on John 3:16.
  await page.waitForSelector('#content a.peek[data-v="16"]', { timeout: 8000 });
  await page.evaluate(() => document.querySelector('#content a.peek[data-v="16"]').click());
  await page.waitForSelector('#sheets .sheet', { timeout: 8000 });
  const peekTxt = await page.textContent('#sheets');
  check('Ukrainian peek shows Ohienko (Cyrillic)', cyr(peekTxt), 'no Cyrillic in peek');
  await page.evaluate(() => window.onBack && window.onBack());

  // Search.
  await page.evaluate(() => { window.App.setTab('search'); window.App.runSearch('faith', 'nt', false, 'kjv'); });
  await page.waitForFunction(() => /result|match|found|\d/.test(document.querySelector('#content')?.textContent || '') && document.querySelectorAll('#content .result, #content [data-r]').length > 0, { timeout: 10000 });
  const nRes = await page.$$eval('#content .result, #content [data-r]', e => e.length);
  check('search "faith" (NT) returns results', nRes > 0, `results=${nRes}`);

  // On-demand load of a non-core module: switch to Septuagint at Genesis 1 (tests async fetch+open+rerender).
  await page.evaluate(() => window.App.goTo(1, 1, 1));
  await page.waitForFunction(() => (document.querySelector('#refLabel')?.textContent || '').trim() === 'Genesis 1');
  await page.click('#btnVersion');
  await page.waitForSelector('#sheets [data-main="septuagint"]', { timeout: 8000 });
  await page.click('#sheets [data-main="septuagint"]');
  await page.waitForFunction(() => /[Ͱ-Ͽ]/.test(document.querySelector('#content')?.textContent || ''), { timeout: 40000 }).catch(() => {});
  const lxx = await page.textContent('#content');
  check('Septuagint (non-core) loaded on demand, Greek renders', grk(lxx || ''), 'no Greek after switch');

  check('no uncaught JS errors during run', errors.length === 0, errors.slice(0, 3).join(' | '));
} catch (e) {
  fail++; console.log('  FAIL harness exception —', e.message);
  console.log('  recent page errors:', errors.slice(0, 5).join(' | '));
} finally {
  await browser.close(); server.close();
  console.log(`\n  RESULT: ${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
}
