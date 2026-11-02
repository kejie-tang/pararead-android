import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { startBackgroundTranslation } from '../web/reader/src/background-translation.ts';
import { SentenceStore } from '../web/reader/src/upstream/sentence-store.ts';

const tick = () => new Promise(resolve => setImmediate(resolve));
const bridgeBundle = build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/bridge.ts', import.meta.url))],
  bundle: true, format: 'iife', write: false });
async function harness({ fetch, onSend, bundle, settings = { backgroundTranslation: true } } = {}) {
  const sent = [], listeners = new Map(), documentListeners = new Map(), timers = new Map(); let timer = 0;
  const origin = 'https://appassets.androidplatform.net';
  const window = { origin, innerHeight: 800,
    addEventListener(type, listener) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(listener); },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener); },
    postMessage(data) { queueMicrotask(() => { for (const listener of listeners.get('message') ?? []) listener({ origin, data }); }); },
    ParaReadNative: { postMessage(text) { const message = JSON.parse(text); sent.push(message); onSend?.(message); } },
  };
  const context = vm.createContext({ window, document: { documentElement: { style: {} }, body: { style: {} },
    querySelector() { return { dataset: { config: '{"url":""}' } }; },
    addEventListener(type, listener) { if (!documentListeners.has(type)) documentListeners.set(type, new Set()); documentListeners.get(type).add(listener); },
    dispatchEvent(event) { for (const listener of documentListeners.get(event.type) ?? []) listener(event); },
  },
    location: { origin, href: `${origin}/reader/index.html` }, TextEncoder, Uint8Array, Map, Set, AbortController, console, URL, fetch,
    setTimeout(fn) { timers.set(++timer, fn); return timer; }, clearTimeout(id) { timers.delete(id); },
  });
  context.acquireVsCodeApi = () => window.acquireVsCodeApi();
  if (bundle) context.pdfjsLib = bundle.pdfjs;
  vm.runInContext(bundle?.text ?? (await bridgeBundle).outputFiles[0].text, context);
  const session = { version: 1, sessionId: 'session', documentId: 'doc', documentRevision: 'rev', configRevision: 1, viewportEpoch: 0 };
  const deliver = (type, payload, requestId = 'open', extra = {}) => window.ParaReadNative.onmessage({
    data: JSON.stringify({ ...session, type, payload, requestId, ...extra }),
  });
  deliver('session.open', { length: 1000, token: 'token', reading: {}, settings });
  return { window, sent, timers, listeners, deliver, session: await window.paraRead.ready, bridge: window.paraRead,
    post: window.acquireVsCodeApi().postMessage };
}

test('page text waits for matching page.saved on the final chunk and rejects errors, limits and timeout', async () => {
  const h = await harness();
  let saved = false;
  const pending = h.bridge.pageText({ page: 2, text: '中'.repeat(6000) }).then(() => { saved = true; });
  const chunks = h.sent.filter(m => m.type === 'page.textChunk');
  assert.equal(chunks.length, 2);
  await tick(); assert.equal(saved, false);
  h.deliver('page.saved', { page: 2 }, chunks.at(-1).requestId, { documentRevision: 'stale' });
  await tick(); assert.equal(saved, false);
  h.deliver('page.saved', { page: 2 }, chunks.at(-1).requestId); await pending;
  assert.equal(saved, true);
  for (const [type, payload, error] of [
    ['protocol.error', { code: 'DATABASE_ERROR' }, /DATABASE_ERROR/],
    ['page.saved', { page: 9 }, /EXPECTED_PAGE_SAVED/],
    ['job.saved', { page: 2 }, /EXPECTED_PAGE_SAVED/],
  ]) {
    const failed = h.bridge.pageText({ page: 2 });
    h.deliver(type, payload, h.sent.at(-1).requestId);
    await assert.rejects(failed, error);
  }
  const count = h.sent.length;
  await assert.rejects(h.bridge.pageText({ page: 2, text: 'a'.repeat(320000) }), /PAGE_TEXT_TOO_LARGE/);
  assert.equal(h.sent.length, count, 'oversize pages must not partially enter native chunk assembly');
  const timedOut = h.bridge.pageText({ page: 2 });
  [...h.timers.values()][0]();
  await assert.rejects(timedOut, /HOST_TIMEOUT/);
});

test('SentenceStore publishes only after page persistence and retries rejected writes', async () => {
  const h = await harness(); globalThis.window = h.window;
  const result = { page: 1, blocks: [] };
  h.bridge.pageCached = async () => null;
  const store = new SentenceStore({ pdfDocument: { getPage: async () => ({ view: [0, 0, 100, 100],
    getTextContent: async () => ({ items: [], styles: {} }) }) } }, { segmentPage: () => result });
  let published = 0; store.onPageReady(() => published++);
  const first = store.ensure(1);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(published, 0); assert.equal(store.cached(1), undefined);
  h.deliver('protocol.error', { code: 'WRITE_FAILED' }, h.sent.at(-1).requestId);
  await assert.rejects(first, /WRITE_FAILED/); assert.equal(published, 0);
  const retry = store.ensure(1);
  await new Promise(resolve => setTimeout(resolve, 10));
  h.deliver('page.saved', { page: 1 }, h.sent.at(-1).requestId);
  assert.equal(await retry, result); assert.equal(published, 1);
});

// Two locally generated pages, no network fixtures or third-party sample rights.
function smallPdf() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>'];
  for (const [index, text] of ['First page sentence.', 'Second page sentence.'].entries()) {
    const stream = `BT /F1 12 Tf 20 100 Td (${text}) Tj ET`;
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 7 0 R >> >> /Contents ${4 + index * 2} 0 R >>`,
      `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  }
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  let pdf = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, i) => { offsets.push(pdf.length); pdf += `${i + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = pdf.length;
  pdf += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  pdf += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  return new TextEncoder().encode(pdf + `trailer\n<< /Root 1 0 R /Size ${offsets.length} >>\nstartxref\n${xref}\n%%EOF\n`);
}

async function realPdfjs() {
  // Only constructed at PDF.js module evaluation; rendering APIs must never be used.
  globalThis.DOMMatrix ??= class DOMMatrix {};
  globalThis.Path2D ??= class Path2D { constructor() { throw new Error('NO_RENDERING'); } };
  return import('../.references/pararead/assets/pdf.js/build/pdf.mjs');
}

async function until(predicate) {
  for (let i = 0; i < 200; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  assert.fail('background operation did not reach its barrier');
}

test('real PDF.js range-only headless job enforces page.saved and job.saved, cleans completed pages and supports partial completion', async () => {
  const pdfjs = await realPdfjs(), bytes = smallPdf();
  let h, pendingPage, pendingJob, finished, destroyed = 0; const cleaned = [], extracted = [], fetches = [];
  h = await harness({ fetch: async url => {
    fetches.push(url); const query = new URL(url, 'https://test').searchParams;
    return new Response(bytes.slice(Number(query.get('begin')), Number(query.get('end'))));
  }, onSend: m => {
    if (!h) return;
    if (m.type === 'session.opened') h.deliver('reader.command', { type: 'translateAll', jobId: 81 });
    if (m.type === 'page.cacheGet') h.deliver('page.cached', { page: null }, m.requestId);
    if (m.type === 'page.textChunk') pendingPage = m;
    if (m.type === 'translation.cacheGet') h.deliver('translation.finished', { failures: [] }, m.requestId);
    if (m.type === 'translation.request') {
      if (m.payload.page === 1) h.deliver('translation.committed', { sequence: 0,
        items: m.payload.sentences.map(s => ({ id: s.id, translation: '本地协议译文' })) }, m.requestId);
      h.deliver('translation.finished', { failures: m.payload.page === 1 ? [] : m.payload.sentences.map(s => ({ id: s.id, message: 'test failure' })) }, m.requestId);
    }
    if (m.type === 'job.progress') pendingJob = m;
    if (m.type === 'job.finished') { finished = m; h.deliver('job.saved', { status: 'COMPLETED' }, m.requestId); }
  } });
  h.session.length = bytes.length; globalThis.window = h.window;
  const library = { PDFDataRangeTransport: pdfjs.PDFDataRangeTransport, getDocument(options) {
    assert.equal(options.disableStream, true); assert.equal(options.disableAutoFetch, true);
    assert.ok(options.range instanceof pdfjs.PDFDataRangeTransport); assert.equal(options.data, undefined);
    const task = pdfjs.getDocument(options);
    return { promise: task.promise.then(doc => ({ numPages: doc.numPages, getPage: async n => {
      extracted.push(n); const page = await doc.getPage(n);
      const cleanup = page.cleanup.bind(page); page.cleanup = () => { cleaned.push(n); return cleanup(); }; return page;
    } })), destroy: async () => { destroyed++; await task.destroy(); } };
  } };
  Object.defineProperty(h.window, 'PDFViewerApplication', { get() { throw new Error('NO_VIEWER'); } });
  const stop = await startBackgroundTranslation({ pdfjs: library, session: h.session, config: { useSystemFonts: true, verbosity: 0 }, bridge: h.bridge, post: h.post });
  await until(() => pendingPage);
  assert.deepEqual(extracted, [1]); assert.equal(h.sent.some(m => m.type === 'translation.request'), false);
  assert.equal(pendingPage.payload.page, 1);
  h.deliver('page.saved', { page: 1 }, pendingPage.requestId);
  await until(() => pendingJob);
  assert.deepEqual(extracted, [1]); assert.deepEqual(cleaned, []);
  h.deliver('job.saved', { status: 'RUNNING' }, pendingJob.requestId);
  await until(() => pendingPage.payload.page === 2);
  assert.ok(cleaned.includes(1)); assert.deepEqual(extracted, [1, 2]);
  h.deliver('page.saved', { page: 2 }, pendingPage.requestId);
  await until(() => pendingJob.payload.done === 2);
  assert.equal(pendingJob.payload.failed, true);
  h.deliver('job.saved', { status: 'RUNNING' }, pendingJob.requestId);
  await until(() => finished && destroyed);
  assert.equal(finished.payload.status, 'COMPLETED'); assert.ok(cleaned.includes(2));
  assert.ok(fetches.length > 0); assert.equal(h.listeners.get('message').size, 0);
  assert.equal(h.sent.some(m => ['reading.save', 'session.error'].includes(m.type)), false);
  await stop(); assert.equal(destroyed, 1);
});

test('range and loading failures have a single session.error fatal exit and destroy the loading task', async () => {
  for (const rangeFailure of [true, false]) {
    const h = await harness({ fetch: async () => new Response('', { status: 503 }) });
    globalThis.window = h.window; let destroyed = 0, rejectLoad;
    const loading = new Promise((_, reject) => { rejectLoad = reject; });
    class RangeTransport { onDataRange() { assert.fail('failed range delivered bytes'); } }
    const pdfjs = { PDFDataRangeTransport: RangeTransport, getDocument({ range }) {
      if (rangeFailure) range.requestDataRange(0, 100);
      else rejectLoad(new Error('INVALID_PDF'));
      return { promise: loading, async destroy() { destroyed++; rejectLoad(new Error('DESTROYED')); } };
    } };
    await startBackgroundTranslation({ pdfjs, session: h.session, config: {}, bridge: h.bridge, post: h.post });
    assert.equal(destroyed, 1);
    const errors = h.sent.filter(m => m.type === 'session.error');
    assert.equal(errors.length, 1); assert.match(errors[0].payload.message, rangeFailure ? /RANGE_HTTP_503/ : /INVALID_PDF/);
    assert.equal(h.sent.some(m => m.type === 'session.opened'), false);
  }
});

test('background cancellation and extraction/page/checkpoint errors release PDF.js without advancing a partial page', async () => {
  for (const mode of ['cancel', 'extract', 'page-write', 'checkpoint']) {
    let h, pendingPage, pendingJob, destroyed = 0, finished;
    h = await harness({ onSend: m => {
      if (!h) return;
      if (m.type === 'session.opened') h.deliver('reader.command', { type: 'translateAll', jobId: 90, startPage: 2 });
      if (m.type === 'page.cacheGet') h.deliver('page.cached', { page: null }, m.requestId);
      if (m.type === 'page.textChunk') pendingPage = m;
      if (m.type === 'job.progress') pendingJob = m;
      if (m.type === 'job.finished') { finished = m; h.deliver('job.saved', { status: m.payload.status }, m.requestId); }
    } });
    globalThis.window = h.window;
    const extracted = [], cleaned = [];
    const pdfjs = { PDFDataRangeTransport: class {}, getDocument() { return {
      promise: Promise.resolve({ numPages: 3, getPage: async n => {
        extracted.push(n);
        if (mode === 'extract') throw new Error('DAMAGED_PAGE');
        return { view: [0, 0, 100, 100], getTextContent: async () => ({ items: [], styles: {} }),
          cleanup() { cleaned.push(n); return true; } };
      } }), destroy: async () => { destroyed++; },
    }; } };
    const stop = await startBackgroundTranslation({ pdfjs, session: h.session, config: {}, bridge: h.bridge, post: h.post });
    if (mode !== 'extract') {
      await until(() => pendingPage);
      if (mode === 'cancel') h.deliver('reader.command', { type: 'cancelTranslateAll', jobId: 90 });
      h.deliver(mode === 'page-write' ? 'protocol.error' : 'page.saved',
        mode === 'page-write' ? { code: 'PAGE_DATABASE_ERROR' } : { page: 2 }, pendingPage.requestId);
      if (mode === 'checkpoint') {
        await until(() => pendingJob);
        assert.deepEqual(cleaned, [], 'page resources live until job.saved');
        h.deliver('protocol.error', { code: 'JOB_DATABASE_ERROR' }, pendingJob.requestId);
      }
    }
    await until(() => destroyed);
    assert.deepEqual(extracted, [2], 'resume never extracts durable page 1 or advances to page 3');
    if (mode === 'cancel') {
      assert.equal(finished.payload.status, 'CANCELLED');
      assert.equal(h.sent.some(m => ['job.progress', 'session.error'].includes(m.type)), false);
    } else {
      assert.equal(h.sent.filter(m => m.type === 'session.error').length, 1);
      if (mode !== 'checkpoint') assert.equal(h.sent.some(m => m.type === 'job.progress'), false);
    }
    await stop(); assert.equal(destroyed, 1);
    assert.equal(h.listeners.get('message')?.size ?? 0, 0);
  }
});

test('interactive lazy import configures PDF.js at webviewerloaded before its initialization', async () => {
  const result = await build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/entry.ts', import.meta.url))],
    bundle: true, format: 'iife', write: false, plugins: [{ name: 'interactive-viewer', setup(b) {
      b.onResolve({ filter: /\/viewer\.mjs$/ }, () => ({ path: 'viewer', namespace: 'interactive' }));
      b.onLoad({ filter: /.*/, namespace: 'interactive' }, () => ({ contents: `
        const options = new Map();
        window.PDFViewerApplicationOptions = { set: (key, value) => options.set(key, value) };
        document.dispatchEvent({ type: 'webviewerloaded' });
        if (options.get('defaultUrl') !== '' || options.get('disablePreferences') !== true ||
            options.get('enableScripting') !== false || options.get('maxCanvasPixels') !== 4194304) {
          throw new Error('VIEWER_NOT_CONFIGURED_BEFORE_INITIALIZATION');
        }
        window.PDFViewerApplication = { get initializedPromise() { throw new Error('INTERACTIVE_INITIALIZED_REACHED'); } };
      ` }));
    } }] });
  const h = await harness({ settings: {}, bundle: { text: result.outputFiles[0].text, pdfjs: { GlobalWorkerOptions: {} } } });
  await tick();
  assert.match(h.sent.at(-1).payload.message, /INTERACTIVE_INITIALIZED_REACHED/);
});

test('main branches before viewer import and initializedPromise; background build has no static viewer dependency', async () => {
  // Same external resolution as scripts/build_reader.mjs, with no generated asset writes.
  const production = await build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/entry.ts', import.meta.url))],
    bundle: true, format: 'esm', platform: 'browser', target: 'es2022', write: false,
    plugins: [{ name: 'pdfjs-external', setup(b) {
      b.onResolve({ filter: /\/viewer\.mjs$/ }, () => ({ path: './pdfjs/web/viewer.mjs', external: true }));
    } }] });
  assert.match(production.outputFiles[0].text, /await import\("\.\/pdfjs\/web\/viewer\.mjs"\)/);
  assert.doesNotMatch(production.outputFiles[0].text, /import [^\n]*from ["'][^"']*viewer\.mjs/);
  const result = await build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/entry.ts', import.meta.url))],
    bundle: true, format: 'iife', write: false, plugins: [{ name: 'guard-viewer', setup(b) {
      b.onResolve({ filter: /\/viewer\.mjs$/ }, args => {
        assert.equal(args.kind, 'dynamic-import');
        return { path: args.path, namespace: 'forbidden-viewer' };
      });
      b.onLoad({ filter: /.*/, namespace: 'forbidden-viewer' }, () => ({ contents: 'throw new Error("VIEWER_IMPORTED_IN_BACKGROUND");' }));
    } }] });
  const h = await harness({ bundle: { text: result.outputFiles[0].text, pdfjs: {
    GlobalWorkerOptions: {}, getDocument() { return { promise: Promise.reject(new Error('HEADLESS_LOAD_REACHED')),
      destroy: async () => {} }; }, PDFDataRangeTransport: class {},
  } } });
  await tick();
  assert.equal(h.sent.filter(m => m.type === 'session.error').length, 1);
  assert.match(h.sent.at(-1).payload.message, /HEADLESS_LOAD_REACHED/);
});
