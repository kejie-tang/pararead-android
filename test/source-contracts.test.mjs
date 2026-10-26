// Tests exercise the pinned, unmodified ParaRead implementation.
// Cases marked GAP confirm existing limitations; they are not Android fixes.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { HeuristicSegmenter } from '../.references/pararead/src/webview/segmenter/heuristic.ts';
import { findSentenceEnds, lineJoin } from '../.references/pararead/src/webview/segmenter/protect.ts';
import { TranslationCache, cacheKey } from '../.references/pararead/src/translation/cache.ts';
import { parseTranslations } from '../.references/pararead/src/translation/parse.ts';
import { glossaryFor } from '../.references/pararead/src/translation/config.ts';
import { Translator } from '../.references/pararead/src/translation/translator.ts';
import { OpenAICompatibleClient } from '../.references/pararead/src/translation/client.ts';
import { TranslationClient } from '../.references/pararead/src/webview/translation-client.ts';
import { SelectionController } from '../.references/pararead/src/webview/selection.ts';

const item = (str, x, y, size = 10, fontName = 'body') => ({
  str, transform: [size, 0, 0, size, x, y], width: str.length * size / 2,
  height: size, fontName, hasEOL: true,
});
const pageInput = {
  page: 7, view: [0, 0, 612, 792], styles: { body: { fontFamily: 'serif' }, code: { fontFamily: 'monospace' } },
  items: [
    item('A Research Title', 50, 750, 20),
    item('We propose a model. Results improve by 3.5%.', 50, 700),
    item('This is the second column. See Fig. 2.', 330, 700),
    item('const count = 42;', 50, 640, 10, 'code'),
  ],
};
const options = { model: 'audit-model', targetLanguage: 'zh-CN', sourceLanguage: 'en', glossary: {}, maxCharsPerRequest: 200 };
const inputs = [
  { id: 'p1-b1-s1', text: 'We propose a model.', group: 'p1-b1' },
  { id: 'p1-b1-s2', text: 'It improves the results.', group: 'p1-b1' },
];

async function withCache(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pararead-source-audit-'));
  try { await run(dir); } finally { await rm(dir, { recursive: true, force: true }); }
}

function fakeClient(response) {
  const calls = [];
  return {
    calls,
    async complete(messages, settings) {
      const { sentences } = JSON.parse(messages[1].content);
      calls.push({ sentences, settings });
      return { content: response?.(sentences, calls.length) ?? JSON.stringify({ translations: sentences.map(s => ({ id: s.id, translation: `译文(${s.text})` })) }) };
    },
  };
}

test('sentence IDs, spans and PDF rectangles are deterministic', () => {
  const segmenter = new HeuristicSegmenter();
  const page = segmenter.segmentPage(pageInput);
  assert.deepEqual(page, segmenter.segmentPage(pageInput));
  const sentences = page.blocks.flatMap(b => b.sentences);
  assert.ok(sentences.length >= 4);
  assert.equal(new Set(sentences.map(s => s.id)).size, sentences.length);
  for (const sentence of sentences) {
    assert.match(sentence.id, /^p7-b\d+-s\d+$/);
    assert.ok(sentence.rects.length > 0);
    assert.ok(sentence.rects.every(r => r.w > 0 && r.h > 0));
    assert.ok(sentence.spanRange.startSpan >= 0 && sentence.spanRange.endSpan < pageInput.items.length);
  }
  assert.ok(page.blocks.some(b => b.role === 'heading'));
  assert.ok(page.blocks.some(b => b.kind === 'code' && b.sentences.length === 0));
});

test('ordered two-column content keeps the left column before the right', () => {
  const text = new HeuristicSegmenter().segmentPage(pageInput).blocks.flatMap(b => b.sentences).map(s => s.text).join('|');
  assert.ok(text.indexOf('We propose') < text.indexOf('This is the second column'));
});

test('abbreviations, decimal values and URLs survive sentence splitting', () => {
  const text = 'Accuracy rose to 93.5 with v1.2.3 from github.com/a/b [12]. Next.';
  const ends = findSentenceEnds(text);
  let begin = 0;
  const pieces = ends.map(end => { const part = text.slice(begin, end).trim(); begin = end; return part; });
  assert.deepEqual(pieces, ['Accuracy rose to 93.5 with v1.2.3 from github.com/a/b [12].', 'Next.']);
  assert.equal(lineJoin('s', '-', 'l'), 'merge');
});

test('language-specific glossary entries override flat entries', () => {
  assert.deepEqual(glossaryFor({ model: '模型', 'zh-CN': { model: '专用模型' }, ja: { model: 'モデル' } }, 'zh-CN'), { model: '专用模型' });
});

test('cache identity ignores layout whitespace and separates languages and models', () => {
  const key = { text: 'A model.', model: 'one', targetLanguage: 'zh-CN', promptVersion: '1', glossaryHash: 'g' };
  assert.equal(cacheKey(key), cacheKey({ ...key, text: ' A  model.\n' }));
  assert.notEqual(cacheKey(key), cacheKey({ ...key, model: 'two' }));
  assert.notEqual(cacheKey(key), cacheKey({ ...key, targetLanguage: 'ja' }));
});

test('reopening the disk cache returns translations without another model call', async () => {
  await withCache(async dir => {
    const client = fakeClient();
    const first = new Translator(client, new TranslationCache(dir), options);
    const result = await first.translate(inputs);
    await first.flush();
    assert.equal(result.failures.length, 0);
    const reopened = new Translator(client, new TranslationCache(dir), options);
    const callsBefore = client.calls.length;
    assert.equal((await reopened.translate(inputs)).results.length, inputs.length);
    assert.equal(client.calls.length, callsBefore);
    const files = await readdir(dir);
    const shard = JSON.parse(await readFile(join(dir, files[0]), 'utf8'));
    assert.ok(Object.values(shard).every(entry => typeof entry.zh === 'string' && typeof entry.t === 'number'));
  });
});

test('application-generated batch IDs are mapped back to the original sentence IDs', async () => {
  await withCache(async dir => {
    const client = fakeClient();
    const translator = new Translator(client, new TranslationCache(dir), options);
    const { results } = await translator.translate(inputs);
    await translator.flush();
    assert.deepEqual(client.calls[0].sentences.map(s => s.id), ['s1', 's2']);
    assert.deepEqual(results.map(s => s.id).sort(), inputs.map(s => s.id).sort());
  });
});

test('missing translations retry individually and failures never enter the cache', async () => {
  await withCache(async dir => {
    const client = fakeClient((sentences, call) => call === 1
      ? JSON.stringify({ translations: [{ id: 's1', translation: '已验证译文' }] }) : '{}');
    const cache = new TranslationCache(dir);
    const translator = new Translator(client, cache, options);
    const { results, failures } = await translator.translate(inputs);
    await translator.flush();
    assert.equal(client.calls.length, 2);
    assert.deepEqual(results.map(s => s.id), [inputs[0].id]);
    assert.deepEqual(failures.map(s => s.id), [inputs[1].id]);
    assert.equal(await cache.get(translator.keyFor(inputs[1].text)), undefined);
  });
});

test('duplicate source sentences share a single model request', async () => {
  await withCache(async dir => {
    const client = fakeClient();
    const translator = new Translator(client, new TranslationCache(dir), options);
    const { results } = await translator.translate([inputs[0], { ...inputs[0], id: 'p2-b1-s1' }]);
    await translator.flush();
    assert.equal(results.length, 2);
    assert.equal(client.calls[0].sentences.length, 1);
  });
});

test('parser rejects empty values, unknown IDs and malformed JSON', () => {
  assert.deepEqual(parseTranslations('{"translations":[{"id":"a","zh":"甲"},{"id":"x","zh":"?"},{"id":"b","zh":" "}]}', ['a', 'b']).missing, ['b']);
  assert.deepEqual(parseTranslations('bad json', ['a']).missing, ['a']);
});

test('GAP: duplicate reply IDs silently overwrite earlier translations', () => {
  const parsed = parseTranslations('{"translations":[{"id":"a","translation":"first"},{"id":"a","translation":"second"}]}', ['a']);
  assert.equal(parsed.found.get('a'), 'second');
  assert.deepEqual(parsed.missing, []);
});

test('GAP: upstream HTTP translation client requests a non-streaming completion', async () => {
  const bodies = [];
  const client = new OpenAICompatibleClient({
    baseUrl: 'http://127.0.0.1:8080/v1', apiKey: '', model: 'audit-model', temperature: 0.3,
    timeoutMs: 1000, extraBody: {}, retries: 0,
    fetch: async (_url, init) => {
      bodies.push(JSON.parse(init.body));
      return new Response(JSON.stringify({ choices: [{ message: { content: '{}' } }] }), { status: 200 });
    },
  });
  await client.complete([{ role: 'user', content: 'test' }], { maxTokens: 100 });
  assert.equal(bodies[0].stream, false);
});

test('GAP: changing page queues a new range without cancelling the previous request', async () => {
  const handlers = new Map();
  const messages = [];
  const sentence = page => ({ ...inputs[0], id: `p${page}-b1-s1`, blockId: `p${page}-b1` });
  const store = {
    onPageReady() {},
    async ensure(page) { return { page, blocks: [{ kind: 'text', sentences: [sentence(page)] }] }; },
  };
  const client = new TranslationClient({
    app: { eventBus: { on: (type, fn) => handlers.set(type, fn) }, pdfViewer: { currentPageNumber: 1, pagesCount: 20 } },
    store, range: 'page', post: m => messages.push(m),
  });
  client.refresh();
  await new Promise(resolve => setImmediate(resolve));
  handlers.get('pagechanging')({ pageNumber: 10 });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(messages.length, 2);
  assert.ok(messages.every(m => m.type === 'translate'));
  client.reset();
});

test('stale translation replies are ignored after a document reset', async () => {
  const messages = [];
  const store = { onPageReady() {}, async ensure() { return { blocks: [{ kind: 'text', sentences: [{ ...inputs[0], blockId: 'p1-b1' }] }] }; } };
  const client = new TranslationClient({ app: { eventBus: { on() {} }, pdfViewer: { currentPageNumber: 1, pagesCount: 1 } }, store, range: 'page', post: m => messages.push(m) });
  client.refresh();
  await new Promise(resolve => setImmediate(resolve));
  client.reset();
  client.handle({ type: 'translations', requestId: messages[0].requestId, items: [{ id: inputs[0].id, translation: 'stale' }] });
  assert.equal(client.state(inputs[0].id).status, 'none');
});

test('PDF and panel selections share the same stable sentence reference', () => {
  const selection = new SelectionController();
  selection.pin(inputs[0], 'pdf');
  assert.equal(selection.current.source, 'pdf');
  selection.pin(inputs[0], 'panel');
  assert.equal(selection.current.sentence.id, inputs[0].id);
  assert.equal(selection.current.source, 'panel');
  selection.clear();
  assert.equal(selection.current, null);
});

test('GAP: bundled PDF.js range transport reserves a full-length byte buffer', async () => {
  const worker = await readFile(new URL('../.references/pararead/assets/pdf.js/build/pdf.worker.mjs', import.meta.url), 'utf8');
  const chunked = worker.slice(worker.indexOf('class ChunkedStream extends Stream'));
  assert.ok(chunked.slice(0, 400).includes('super(new Uint8Array(length), 0, length, null)'));
  assert.match(worker, /handler\.on\("SaveDocument"[\s\S]{0,500}pdfManager\.requestLoadedStream\(\)/);
});
