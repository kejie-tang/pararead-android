import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import assert from 'node:assert/strict';
const root = resolve(import.meta.dirname, '../app/build/generated/readerAssets/reader');
const sample = await readFile(`${root}/sample.pdf`);
const ranges = [];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname.startsWith('/documents/')) {
      const begin = Number(url.searchParams.get('begin')); const end = Number(url.searchParams.get('end'));
      assert.ok(begin >= 0 && end <= sample.length && end - begin <= 262144);
      ranges.push([begin, end]); res.setHeader('Content-Type', 'application/octet-stream'); res.end(sample.subarray(begin, end)); return;
    }
    const file = resolve(root, `.${url.pathname === '/' ? '/index.html' : url.pathname}`);
    if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    const type = { '.mjs': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.ftl': 'text/plain' }[extname(file)];
    if (type) res.setHeader('Content-Type', type);
    res.end(await readFile(file));
  } catch (e) { res.writeHead(404).end(String(e)); }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
const errors = []; let result; let page;
try {
  page = await browser.newPage({ viewport: { width: 1280, height: 800 }, hasTouch: true });
  page.on('requestfailed', r=>console.log('REQUEST FAILED', r.url(), r.failure()));
  page.on('response', r=>{ if(r.status()>=400) console.log('HTTP ERROR',r.status(),r.url()); });
  page.on('pageerror', e => errors.push(String(e)));
  page.on('console', m => { console.log('CONSOLE', m.type(), m.text().slice(0, 700)); if (m.type() === 'error') errors.push(m.text().slice(0, 300)); });
  await page.addInitScript(({ length }) => {
    window.__nativeMessages = [];
    const WorkerBase = window.Worker;
    window.Worker = class extends WorkerBase { constructor(...args) { super(...args); this.addEventListener('error',e=>console.error('WorkerError',e.message,e.filename,e.lineno)); } };
    const session = { version: 1, sessionId: 'test-session', documentId: 'test-document', documentRevision: 'sha256:test', configRevision: 1, viewportEpoch: 0 };
    const send = (m, type, payload) => setTimeout(() => window.ParaReadNative.onmessage({ data: JSON.stringify({ ...session, ...m, type, payload }) }), 0);
    window.ParaReadNative = { postMessage(raw) {
      const m = JSON.parse(raw); window.__nativeMessages.push(m);
      if (m.type === 'reader.ready') send(m, 'session.open', { length, token: 'test', reading: {}, settings: { targetLanguage: 'zh-CN', translateRange: localStorage.getItem('test-range') ?? 'page' }, debug: true });
      if (m.type === 'page.cacheGet') send(m, 'page.cached', { page: null });
      // Simulate the native durable cache across WebView reloads (not a production storage path).
      if (m.type === 'translation.cacheGet') {
        m.payload.sentences.forEach((s, sequence) => {
          const translation = localStorage.getItem('test-cache:' + s.text);
          if (translation) send(m, 'translation.committed', { sequence, items: [{ id: s.id, translation }] });
        });
        send(m, 'translation.finished', { failures: [], cancelled: false });
      }
      if (m.type === 'translation.request') {
        m.payload.sentences.forEach((s, sequence) => {
          const translation = '【模拟译文】' + s.text;
          localStorage.setItem('test-cache:' + s.text, translation);
          send(m, 'translation.committed', { sequence, items: [{ id: s.id, translation }] });
        });
        send(m, 'translation.finished', { failures: [], cancelled: false });
      }
    } };
  }, { length: sample.length });
  console.log('Loading');
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.__bilingual?.store.readyPages().length > 0, { timeout: 45000 });
  await page.waitForFunction(() => document.querySelector('.bilingualSentence')?.textContent.includes('模拟译文'), { timeout: 15000 });
  await page.waitForFunction(() => window.__bilingual.store.readyPages().find(p=>p.page===1)?.blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences).every(s=>window.__bilingual.translations.state(s.id).status==='done'), { timeout: 15000 });
  console.log('Rendered and translated');
  const first = await page.evaluate(async () => {
    const b = window.__bilingual; const p = await b.store.ensure(1);
    const s = p.blocks.filter(b => b.kind === 'text').flatMap(b => b.sentences).find(s => s.text.length > 70);
    if (!s) throw new Error('Sample has no body sentence');
    const pv = window.PDFViewerApplication.pdfViewer.getPageView(0);
    const [x, y] = pv.viewport.convertToViewportPoint(s.rects[0].x + 3, s.rects[0].y + s.rects[0].h / 2);
    const box = pv.div.getBoundingClientRect();
    return { id: s.id, x: box.left + x + pv.div.clientLeft, y: box.top + y + pv.div.clientTop };
  });
  console.log('Touch sentence',first);
  await page.touchscreen.tap(first.x, first.y);
  await page.waitForFunction(id => window.__bilingual.selection.current?.sentence.id === id, first.id);
  await page.locator(`.bilingualSentence[data-id="${first.id}"]`).click();
  await page.waitForFunction(id => window.__bilingual.selection.current?.source === 'panel' && window.__bilingual.selection.current?.sentence.id === id, first.id);
  await page.waitForTimeout(100);
  const alignment = await page.evaluate(id => {
    const s=window.__bilingual.store.get(id),pv=PDFViewerApplication.pdfViewer.getPageView(s.page-1),r=s.rects[0];
    const [x,y]=pv.viewport.convertToViewportPoint(r.x,r.y+r.h);
    return window.__bilingual.panel.sentenceElement(id).getBoundingClientRect().top-(pv.div.getBoundingClientRect().top+pv.div.clientTop+y);
  },first.id);
  assert.ok(Math.abs(alignment)<=4, `same-height alignment delta=${alignment}`);
  // Exercise the real incremental PDF.js writer, then reload combined bytes with the real parser.
  console.log('Both directions selected');
  const save = await page.evaluate(async () => {
    const doc = window.PDFViewerApplication.pdfDocument;
    doc.annotationStorage.setValue('pdfjs_internal_editor_900', { annotationType: 3, pageIndex: 0, rect: [70, 660, 230, 690], rotation: 0, color: [0, 0, 0], fontSize: 12, value: 'ParaRead save test' });
    const suffix = await doc.saveDocument();
    const base = await fetch('/sample.pdf').then(r => r.arrayBuffer());
    const combined = new Uint8Array(base.byteLength + suffix.length); combined.set(new Uint8Array(base)); combined.set(suffix, base.byteLength);
    const reopened = await globalThis.pdfjsLib.getDocument({ data: combined }).promise;
    const annotations = await (await reopened.getPage(1)).getAnnotations();
    return { suffixLength: suffix.length, pages: reopened.numPages, values: annotations.map(a => a.contentsObj?.str ?? a.textContent ?? a.contents) };
  });
  assert.ok(save.suffixLength > 0 && save.suffixLength < sample.length, JSON.stringify(save));
  assert.ok(JSON.stringify(save.values).includes('ParaRead save test'), JSON.stringify(save));
  await mkdir(resolve(import.meta.dirname, '../docs/reports'), { recursive: true });
  await page.screenshot({ path: resolve(import.meta.dirname, '../docs/reports/reader-landscape.png') });
  await page.setViewportSize({ width: 800, height: 1280 });
  console.log('Portrait toolbar bounds', JSON.stringify(await page.evaluate(() => ({
    pane: document.querySelector('#outerContainer').getBoundingClientRect().toJSON(),
    groups: [...document.querySelectorAll('#toolbarViewer > *')].map(el => ({ id: el.id, box: el.getBoundingClientRect().toJSON() })),
  }))));
  await page.waitForFunction(() => {
    const pane = document.querySelector('#outerContainer').getBoundingClientRect();
    return [...document.querySelectorAll('#toolbarViewer > *')].every(el => {
      const box = el.getBoundingClientRect();
      return box.left >= pane.left - 1 && box.right <= pane.right + 1;
    });
  }, undefined, { timeout: 3000 });
  await page.screenshot({ path: resolve(import.meta.dirname, '../docs/reports/reader-portrait.png') });
  // Reopening in manual mode must hydrate the panel, not silently invoke translation again.
  await page.evaluate(() => localStorage.setItem('test-range', 'manual'));
  await page.reload();
  await page.waitForFunction(() => window.paraRead?.opened && window.__bilingual?.store.readyPages().find(p => p.page === 1)
    ?.blocks.filter(b => b.kind === 'text').flatMap(b => b.sentences).every(s => window.__bilingual.translations.state(s.id).status === 'done'));
  await page.waitForFunction(() => document.querySelector('.bilingualSentence')?.textContent.includes('模拟译文'));
  assert.equal(await page.evaluate(() => window.__nativeMessages.filter(m => m.type === 'translation.request').length), 0);
  assert.ok(await page.evaluate(() => window.__nativeMessages.some(m => m.type === 'translation.cacheGet')));
  result = { status: 'passed', manualReopenRestoresWithoutInference: true, environment: 'Desktop Chromium, touch emulation; not Android or device', sample: 'Original ParaRead Reading Guide (Apache-2.0), 6 pages', rangeRequests: ranges.length, largestRange: Math.max(...ranges.map(([a, b]) => b - a)), bidirectionalSelection: true, portraitToolbarWithinPane: true, alignmentDeltaPx: alignment, incrementalSave: save, errors };
  assert.equal(errors.filter(e => !e.includes('404')).length, 0, errors.join('\n'));
  console.log(JSON.stringify(result, null, 2));
} catch (e) {
  console.error(await page.evaluate(() => ({messages: window.__nativeMessages?.slice(-8), pages: window.PDFViewerApplication?.pdfDocument?.numPages, rendered: window.__bilingual?.store.readyPages().length, content: document.body.innerText.slice(-500)})));
  result = { status: 'failed', error: String(e), errors, rangeRequests: ranges.length };
  console.error(e.stack); console.error(JSON.stringify(result, null, 2)); process.exitCode = 1;
} finally {
  await writeFile(resolve(import.meta.dirname, '../docs/reports/android-reader-browser.json'), JSON.stringify(result, null, 2));
  await browser.close(); await new Promise(r => server.close(r));
}
