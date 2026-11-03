import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { TranslationClient } from '../web/reader/src/upstream/translation-client.ts';

const tick = () => new Promise(resolve => setImmediate(resolve));
function harness({ range = 'manual', restoreCached } = {}) {
  const sent = [], handlers = new Map();
  const viewer = { currentPageNumber: 1, pagesCount: 20 };
  const page = n => ({ page: n, blocks: [{ id: `p${n}-b0`, kind: 'text', sentences: [0, 1].map(i => ({
    id: `p${n}-b0-s${i}`, page: n, blockId: `p${n}-b0`, text: `Page ${n}, sentence ${i}.`, rects: [],
  })) }] });
  let publish;
  const client = new TranslationClient({
    app: { pdfViewer: viewer, eventBus: { on(type, listener) { handlers.set(type, listener); } } },
    store: { onPageReady(listener) { publish = listener; }, ensure: async n => page(n) },
    range, restoreCached, post: message => { sent.push(message); },
  });
  const turn = n => { viewer.currentPageNumber = n; handlers.get('pagechanging')({ pageNumber: n }); };
  return { client, sent, page, viewer, turn, publish: n => publish(page(n)) };
}

test('manual reopening restores saved sentences without a translation request', async () => {
  const lookups = [];
  const h = harness({ restoreCached: async sentences => {
    lookups.push(sentences);
    return [{ id: sentences[0].id, translation: '已保存的译文' }];
  } });
  h.publish(1); h.client.refresh(); await tick();
  assert.equal(lookups.length, 1, 'render and refresh share a single restore');
  assert.deepEqual(h.client.state('p1-b0-s0'), { status: 'done', translation: '已保存的译文' });
  assert.equal(h.client.state('p1-b0-s1').status, 'none');
  assert.deepEqual(h.sent, [], 'cache misses must not trigger inference in manual mode');
  // A fresh client models leaving and reopening the WebView.
  const reopened = harness({ restoreCached: async () => [{ id: 'p1-b0-s0', translation: '已保存的译文' }] });
  reopened.client.refresh(); await tick();
  assert.equal(reopened.client.state('p1-b0-s0').translation, '已保存的译文');
  assert.deepEqual(reopened.sent, []);
});

test('marked imperfect translations reopen as done without inference in manual or automatic modes', async () => {
  const translation = '已保存的译文 *****（protected_token_mismatch, translation_language_or_source_copy）';
  for (const range of ['manual', 'page']) {
    const h = harness({ range, restoreCached: async sentences => sentences.map(s => ({ id: s.id, translation })) });
    try {
      h.client.refresh(); await tick();
      assert.deepEqual(h.client.state('p1-b0-s0'), { status: 'done', translation });
      assert.deepEqual(h.sent, []);
    } finally { h.client.reset(); }
  }
});

test('automatic mode restores first and translates only missing sentences', async () => {
  let complete;
  const h = harness({ range: 'page', restoreCached: () => new Promise(resolve => { complete = resolve; }) });
  h.client.refresh(); await tick();
  assert.equal(h.sent.length, 0);
  complete([{ id: 'p1-b0-s0', translation: '缓存命中' }]); await tick();
  assert.equal(h.sent.length, 1);
  assert.deepEqual(h.sent[0].sentences.map(s => s.id), ['p1-b0-s1']);
  assert.equal(h.client.state('p1-b0-s0').status, 'done');
  h.client.reset();
});

test('returning to an evicted page restores it again without retranslation', async () => {
  let lookups = 0;
  const h = harness({ restoreCached: async sentences => {
    lookups++;
    return sentences.map(s => ({ id: s.id, translation: '本地译文' }));
  } });
  h.client.refresh(); await tick();
  h.client.trim(15, 5);
  assert.equal(h.client.state('p1-b0-s0').status, 'none');
  h.client.refresh(); await tick();
  assert.equal(lookups, 2);
  assert.equal(h.client.state('p1-b0-s0').status, 'done');
  assert.deepEqual(h.sent, []);
});

test('manual page changes restore the new page but never request missing translations', async () => {
  const h = harness({ restoreCached: async sentences => [{ id: sentences[0].id, translation: '已保存' }] });
  h.turn(10); await tick();
  assert.equal(h.client.state('p10-b0-s0').status, 'done');
  assert.equal(h.client.state('p10-b0-s1').status, 'none');
  assert.deepEqual(h.sent, []);
});

test('late cache replies after reset or eviction cannot repopulate stale state', async () => {
  for (const reset of [true, false]) {
    let complete;
    const h = harness({ restoreCached: () => new Promise(resolve => { complete = resolve; }) });
    h.client.refresh(); await tick();
    if (reset) h.client.reset(); else h.client.trim(15, 5);
    complete([{ id: 'p1-b0-s0', translation: '旧会话' }]); await tick();
    assert.equal(h.client.state('p1-b0-s0').status, 'none');
    assert.deepEqual(h.sent, []);
  }
});

test('failed cache lookups can be retried and unrelated sentence IDs are ignored', async () => {
  let calls = 0;
  const h = harness({ restoreCached: async () => {
    if (++calls === 1) throw new Error('database unavailable');
    return [{ id: 'p1-b0-s0', translation: '恢复成功' }, { id: 'p9-b0-s0', translation: '错误页' }];
  } });
  h.client.refresh(); await tick(); h.client.refresh(); await tick();
  assert.equal(calls, 2);
  assert.equal(h.client.state('p1-b0-s0').status, 'done');
  assert.equal(h.client.state('p9-b0-s0').status, 'none');
  assert.deepEqual(h.sent, []);
});

test('explicit translation also waits for cache and skips already saved sentences', async () => {
  const h = harness({ restoreCached: async () => [{ id: 'p1-b0-s0', translation: '保存结果' }] });
  const pending = h.client.translatePage(1); await tick();
  assert.deepEqual(h.sent[0].sentences.map(s => s.id), ['p1-b0-s1']);
  h.client.handle({ type: 'translationDone', requestId: h.sent[0].requestId, failures: [] });
  await pending;
});

for (const allCached of [false, true]) test(`whole-document translation reuses saved pages and only requests misses (all cached: ${allCached})`, async () => {
  const previousWindow = globalThis.window;
  globalThis.window = { paraRead: { evict() {} } };
  const lookups = [];
  const h = harness({ restoreCached: async sentences => {
    lookups.push(sentences.map(s => s.id));
    return sentences.filter(s => allCached || s.id !== 'p2-b0-s1').map(s => ({ id: s.id, translation: '已保存的正确译文' }));
  } });
  h.viewer.pagesCount = 3;
  try {
    h.client.handle({ type: 'translateAll', jobId: 99 }); await tick();
    const requests = h.sent.filter(m => m.type === 'translate');
    assert.equal(requests.length, allCached ? 0 : 1);
    if (!allCached) {
      assert.deepEqual(requests[0].sentences.map(s => s.id), ['p2-b0-s1']);
      h.client.handle({ type: 'translations', requestId: requests[0].requestId, items: [{ id: 'p2-b0-s1', translation: '补充缺失译文' }] });
      h.client.handle({ type: 'translationDone', requestId: requests[0].requestId, failures: [] });
      await tick();
    }
    assert.equal(lookups.length, 3);
    assert.deepEqual(h.sent.filter(m => m.type === 'translateAllProgress').map(m => [m.done, m.failed]), [[1, false], [2, false], [3, false]]);
    assert.equal(h.sent.find(m => m.type === 'translateAllDone')?.status, 'COMPLETED');
    assert.equal(h.client.state('p1-b0-s0').translation, '已保存的正确译文');
  } finally { h.client.reset(); globalThis.window = previousWindow; }
});

test('cancellation notice expires without hiding errors or leaving a blank status row', async () => {
  const main = await readFile(new URL('../app/src/main/java/org/pararead/android/MainActivity.kt', import.meta.url), 'utf8');
  assert.match(main, /LaunchedEffect\(status\)/);
  assert.match(main, /readerNoticeDuration\(notice\)/);
  assert.match(main, /if \(status == notice\) status = ""/);
  assert.match(main, /status\.isNotBlank\(\)/);
});

test('library pause notice expires only in UI and a changed job cancels its timer', async () => {
  const library = await readFile(new URL('../app/src/main/java/org/pararead/android/LibraryScreen.kt', import.meta.url), 'utf8');
  const progress = library.slice(library.indexOf('@Composable private fun DocumentTranslationProgress'),
    library.indexOf('@Composable private fun SavedTranslationLabel'));
  assert.match(progress, /libraryPausedNoticeDuration\(job, state, needsReview\)/);
  assert.match(progress, /remember\(item\.document\.id, item\.jobJson, noticeDuration\)/);
  assert.match(progress, /LaunchedEffect\(item\.document\.id, item\.jobJson, noticeDuration\)/);
  assert.match(progress, /delay\(duration\)\s+noticeVisible = false/);
  const hidden = progress.indexOf('if (noticeDuration != null && !noticeVisible) return');
  assert.ok(hidden >= 0 && hidden < progress.indexOf('Column('), 'hide the bar and its spacing, not only the text');
  assert.doesNotMatch(progress, /dao\.|saveJob|updateJob|deleteJob|onCancelTranslation\(/, 'the timeout must not change the durable job');
  const menu = library.slice(library.indexOf('DropdownMenu(expanded = menu'), library.indexOf('@Composable private fun DocumentTranslationProgress'));
  assert.match(menu, /WholeJobStatus\.CANCELLED/);
  assert.match(menu, /R\.string\.translation_resume/);
  assert.match(menu, /onTranslateAll\(\)/, 'the resume action remains available after the notice disappears');
});

test('cache bridge batches restoration, validates sessions and never posts translation.request', async () => {
  const protocol = await readFile(new URL('../core/src/main/kotlin/org/pararead/core/Protocol.kt', import.meta.url), 'utf8');
  assert.match(protocol, /"translation\.cacheGet"/, 'native protocol must admit the cache-only request');
  const host = await readFile(new URL('../app/src/main/java/org/pararead/android/ReaderHost.kt', import.meta.url), 'utf8');
  assert.match(host, /"translation\.cacheGet" -> restoreTranslations\(m\)/);
  const restore = host.slice(host.indexOf('private suspend fun restoreTranslations'), host.indexOf('private suspend fun translate('));
  assert.match(restore, /dao\.translations\(keys\.values\.distinct\(\)\)/, 'one bounded batch query, not per-sentence IO');
  assert.match(restore, /TranslationQuality\.accepts/, 'bad English copies must still be rejected');
  assert.doesNotMatch(restore, /translationQueue|provider\./, 'restoration must not call inference');
  const built = await build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/bridge.ts', import.meta.url))],
    bundle: true, format: 'iife', write: false });
  const sent = [], legacy = [], timers = new Map(); let timer = 0;
  const window = { innerHeight: 800, addEventListener() {}, postMessage(m) { legacy.push(m); },
    ParaReadNative: { postMessage(s) { sent.push(JSON.parse(s)); } } };
  vm.runInNewContext(built.outputFiles[0].text, { window,
    document: { documentElement: { style: {} }, body: { style: {} } }, location: { origin: 'https://appassets.androidplatform.net' },
    TextEncoder, Uint8Array, Map, Set, AbortController, console,
    setTimeout(fn) { timers.set(++timer, fn); return timer; }, clearTimeout(id) { timers.delete(id); } });
  const session = { version: 1, sessionId: 'session', documentId: 'doc', documentRevision: 'revision', configRevision: 1, viewportEpoch: 0 };
  const deliver = (type, payload, requestId = 'open', extra = {}) => window.ParaReadNative.onmessage({
    data: JSON.stringify({ ...session, type, payload, requestId, ...extra }),
  });
  deliver('session.open', { length: 1000, token: 'token', reading: {}, settings: {} });
  const pending = window.paraRead.cachedTranslations(Array.from({ length: 81 }, (_, i) => ({
    id: `p1-b0-s${i}`, text: `Sentence ${i}.`, blockId: 'p1-b0',
  })));
  let processed = 0;
  for (const count of [40, 40, 1]) {
    const request = sent.filter(m => m.type === 'translation.cacheGet')[processed++];
    assert.equal(request.payload.sentences.length, count);
    const item = { id: request.payload.sentences[0].id, translation: '已保存' };
    deliver('translation.committed', { sequence: 0, items: [item] }, request.requestId, { configRevision: 2 });
    deliver('translation.committed', { sequence: 0, items: [item] }, request.requestId);
    deliver('translation.committed', { sequence: 0, items: [item] }, request.requestId);
    // Scrolling must not cancel cache reads or skip the remaining batches.
    window.paraRead.viewport(2);
    deliver('translation.finished', { failures: [] }, request.requestId);
    await tick();
  }
  const restored = await pending;
  assert.equal(restored.length, 3);
  assert.equal(sent.filter(m => m.type === 'translation.request').length, 0);
  assert.equal(sent.filter(m => m.type === 'request.cancel').length, 0);
  assert.equal(legacy.length, 0, 'cache restoration must not own or finish inference requests');
  const failed = window.paraRead.cachedTranslations([{ id: 'p1-b0-s0', text: 'Retry me.', blockId: 'p1-b0' }]);
  deliver('protocol.error', { code: 'DATABASE_ERROR' }, sent.at(-1).requestId);
  await assert.rejects(failed, /DATABASE_ERROR/);
  assert.equal(timers.size, 0);
});
