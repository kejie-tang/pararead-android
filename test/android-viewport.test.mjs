import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { TranslationClient } from '../web/reader/src/upstream/translation-client.ts';

const tick = () => new Promise(resolve => setImmediate(resolve));
function harness(range = 'nearby') {
  const sent = [], handlers = new Map();
  const viewer = { currentPageNumber: 1, pagesCount: 20 };
  const page = n => ({ page: n, blocks: [{ id: `p${n}-b0`, kind: 'text', sentences: [
    { id: `p${n}-b0-s0`, page: n, blockId: `p${n}-b0`, text: `Page ${n}.`, rects: [] },
  ] }] });
  const client = new TranslationClient({
    app: { pdfViewer: viewer, eventBus: { on(type, listener) { handlers.set(type, listener); } } },
    store: { onPageReady() {}, ensure: async n => page(n) },
    range, post: message => { sent.push(message); },
  });
  const turn = n => { viewer.currentPageNumber = n; handlers.get('pagechanging')({ pageNumber: n }); };
  const requests = n => sent.filter(m => m.type === 'translate' && m.sentences.some(s => s.id === `p${n}-b0-s0`));
  const finish = (request, cancelled = false) => client.handle({ type: 'translationDone', requestId: request.requestId, failures: [], cancelled });
  return { client, sent, turn, requests, finish };
}

test('jumping to a prefetched page takes ownership before the old viewport cancellation arrives', async () => {
  const h = harness(); h.client.refresh(); await tick();
  const prefetch = h.requests(2)[0];
  assert.equal(prefetch.priority, 2);
  h.turn(2); await tick();
  const current = h.requests(2).find(m => m.priority === 1);
  assert.ok(current, 'The visible page must be requested without clicking a sentence');
  assert.notEqual(current.requestId, prefetch.requestId);
  h.finish(prefetch, true);
  assert.equal(h.client.state('p2-b0-s0').status, 'pending', 'Old cancellation must not clear the current-page owner');
  h.client.handle({ type: 'translations', requestId: current.requestId, items: [{ id: 'p2-b0-s0', translation: '当前页自动译文' }] });
  h.finish(current);
  assert.equal(h.client.state('p2-b0-s0').translation, '当前页自动译文');
  h.client.reset();
});

test('returning quickly to an old pending page requests it for the new viewport', async () => {
  const h = harness('page'); h.client.refresh(); await tick();
  const old = h.requests(1)[0];
  h.turn(10); await tick();
  assert.equal(h.requests(10).length, 1);
  h.turn(1); await tick();
  assert.equal(h.requests(1).length, 2, 'An old pending state must not suppress the new current-page request');
  const current = h.requests(1).at(-1);
  h.finish(old, true);
  assert.equal(h.client.state('p1-b0-s0').status, 'pending');
  h.client.handle({ type: 'translations', requestId: current.requestId, items: [{ id: 'p1-b0-s0', translation: '返回后自动译文' }] });
  h.finish(current);
  assert.equal(h.client.state('p1-b0-s0').translation, '返回后自动译文');
  h.client.reset();
});

test('refreshing the same viewport does not submit duplicate pending translations', async () => {
  const h = harness('page');
  h.client.refresh(); h.client.refresh(); await tick();
  h.client.refresh(); await tick();
  assert.equal(h.requests(1).length, 1);
  h.client.reset();
});

test('automatic page work never steals an explicit click request after scrolling', async () => {
  const h = harness('page');
  const pending = h.client.translatePage(1); await tick();
  assert.equal(h.requests(1)[0].priority, 0);
  h.turn(10); await tick(); h.turn(1); await tick();
  assert.equal(h.requests(1).length, 1);
  h.finish(h.requests(1)[0]); await pending;
  h.client.reset();
});

test('jumping into a later chapter translates its page without selection and leaves manual mode explicit', async () => {
  const h = harness('page'); h.turn(15); await tick();
  assert.equal(h.requests(15).length, 1);
  assert.equal(h.requests(15)[0].priority, 1);
  assert.equal(h.sent.filter(m => m.type === 'translate').length, 1);
  h.client.reset();
  const manual = harness('manual'); manual.turn(15); await tick();
  assert.equal(manual.sent.length, 0, 'An explicitly selected manual mode still disables automatic translation');
});

test('visible-page work does not steal a whole-document request', async () => {
  const previousWindow = globalThis.window;
  globalThis.window = { paraRead: { evict() {} } };
  const h = harness('manual');
  try {
    h.client.handle({ type: 'translateAll', jobId: 44, startPage: 1 }); await tick();
    const whole = h.requests(1)[0]; assert.equal(whole.jobId, 44);
    h.client.handle({ type: 'settings', translateRange: 'page' }); await tick();
    assert.equal(h.requests(1).length, 1);
    h.client.handle({ type: 'cancelTranslateAll' }); h.finish(whole, true); await tick();
  } finally { h.client.reset(); globalThis.window = previousWindow; }
});

test('Android defaults disable hover independently of current-page automatic translation', async () => {
  const host = await readFile(new URL('../app/src/main/java/org/pararead/android/ReaderHost.kt', import.meta.url), 'utf8');
  const settings = await readFile(new URL('../app/src/main/java/org/pararead/android/Settings.kt', import.meta.url), 'utf8');
  assert.match(host, /\.put\("selectOnHover", false\)/);
  assert.match(settings, /getString\("translateRange", "page"\)/);
});

async function bridgeHarness() {
  const built = await build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/bridge.ts', import.meta.url))], bundle: true, format: 'iife', write: false });
  const sent = [], legacy = [];
  const window = { innerHeight: 800, addEventListener() {}, postMessage(m) { legacy.push(m); },
    ParaReadNative: { postMessage(s) { sent.push(JSON.parse(s)); } } };
  vm.runInNewContext(built.outputFiles[0].text, { window, document: { documentElement: { style: {} }, body: { style: {} } },
    location: { origin: 'https://appassets.androidplatform.net' }, TextEncoder, Uint8Array, Map, Set, AbortController, console,
    setTimeout() { return 1; }, clearTimeout() {} });
  const session = { version: 1, sessionId: 'session', documentId: 'doc', documentRevision: 'revision', configRevision: 1, viewportEpoch: 0 };
  const deliver = (type, payload, requestId = 'open') => window.ParaReadNative.onmessage({ data: JSON.stringify({ ...session, type, payload, requestId }) });
  deliver('session.open', { length: 1000, token: 'token', reading: {}, settings: {} });
  return { window, sent, legacy, deliver, post: window.acquireVsCodeApi().postMessage };
}

test('bridge reports skipped old-viewport chunks as cancelled, not successful completion', async () => {
  const h = await bridgeHarness();
  h.post({ type: 'translate', priority: 1, requestId: 1, sentences: Array.from({ length: 81 }, (_, i) => ({ id: `p1-b0-s${i}`, group: 'p1-b0', text: `Sentence ${i}.` })) });
  const first = h.sent.find(m => m.type === 'translation.request');
  h.window.paraRead.viewport(10);
  h.deliver('translation.finished', { failures: [], cancelled: false }, first.requestId); await tick();
  assert.equal(h.sent.filter(m => m.type === 'translation.request').length, 1);
  const done = h.legacy.find(m => m.type === 'translationDone');
  assert.equal(done.cancelled, true); assert.equal(done.failures.length, 0);
});

test('STALE_VIEWPORT is transient cancellation rather than a poisoned sentence error', async () => {
  const h = await bridgeHarness();
  h.post({ type: 'translate', priority: 1, requestId: 2, sentences: [{ id: 'p1-b0-s0', group: 'p1-b0', text: 'Test.' }] });
  const request = h.sent.find(m => m.type === 'translation.request');
  h.deliver('protocol.error', { code: 'STALE_VIEWPORT' }, request.requestId); await tick();
  const done = h.legacy.find(m => m.type === 'translationDone');
  assert.equal(done.cancelled, true); assert.equal(done.failures.length, 0);
});
