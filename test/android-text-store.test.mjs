import test from 'node:test';
import assert from 'node:assert/strict';
import { segmentTextPage, TextSentenceStore } from '../web/reader/src/text-store.ts';

const sentences = page => page.blocks.flatMap(block => block.sentences);
const tick = () => new Promise(resolve => setImmediate(resolve));

test('text-store preserves every UTF-16 character, line break and sentence offset', () => {
  for (const text of ['', '\n\n  \n', 'Dr. Smith reads 3.14. Next sentence!\n\nLast line.\n', '第一句。第二句！\n\n第三句？', '<script>alert(1)</script>\n']) {
    const result = segmentTextPage(7, text), items = sentences(result);
    assert.equal(result.blocks.map(block => block.text).join(''), text);
    assert.equal(items.map(sentence => sentence.text).join('').replace(/\s/gu, ''), text.replace(/\s/gu, ''));
    for (const [blockIndex, block] of result.blocks.entries()) for (const [index, sentence] of block.sentences.entries()) {
      assert.equal(sentence.id, `p7-b${blockIndex}-s${index}`);
      assert.deepEqual(sentence.rects, []);
      assert.equal(sentence.spanRange.startSpan, blockIndex);
      assert.equal(block.text.slice(sentence.spanRange.startChar, sentence.spanRange.endChar), sentence.text);
      assert.ok(sentence.text.trim(), 'blank lines are not model sentences');
    }
  }
  assert.deepEqual(sentences(segmentTextPage(1, 'Dr. Smith reads. Next sentence.')).map(s => s.text), ['Dr. Smith reads.', ' Next sentence.']);
});

test('text-store splits unpunctuated text safely and bounds dense punctuation metadata', () => {
  for (const text of ['a'.repeat(1999) + '😀' + '中'.repeat(1999), '中。'.repeat(2000), 'a\n'.repeat(2000)]) {
    const result = segmentTextPage(1, text), items = sentences(result);
    assert.equal(result.blocks.map(block => block.text).join(''), text);
    assert.equal(items.map(s => s.text).join('').replace(/\s/gu, ''), text.replace(/\s/gu, ''));
    assert.ok(result.blocks.length <= 128);
    assert.ok(items.every(s => s.text.length <= 2000 && s.text.isWellFormed()));
    assert.ok(items.every(s => new TextEncoder().encode(s.text).length <= 16384));
    assert.ok(JSON.stringify(result).length < 320000, 'fits page.textChunk transport budget');
    assert.deepEqual(result, segmentTextPage(1, text), 'stable canonical sentence IDs');
  }
});

test('text-store keeps novel paragraphs separate, recognizes page-leading headings and leaves whitespace pages untranslated', () => {
  for (const title of ['第一章 风雪夜', '第二章故事', 'Chapter IV: The road']) {
    const text = `\n${title}\r\n\r\n  First paragraph.\r\n\r\nSecond paragraph.\n`;
    const result = segmentTextPage(2, text);
    assert.equal(result.blocks.length, 3);
    assert.equal(result.blocks[0].role, 'heading'); assert.equal(result.blocks[0].level, 1);
    assert.equal(result.blocks[1].role, 'paragraph');
    assert.equal(result.blocks[1].sentences[0].text, 'First paragraph.');
    assert.equal(result.blocks.map(block => block.text).join(''), text);
    assert.equal(result.blocks[2].id, 'p2-b2');
  }
  for (const text of ['', '\n\n', ' \t\r\n\n']) assert.deepEqual(sentences(segmentTextPage(1, text)), []);
  const carriageReturns = segmentTextPage(1, 'First.\rSecond.\r\r');
  assert.equal(carriageReturns.blocks.length, 2);
  assert.equal(carriageReturns.blocks.map(block => block.text).join(''), 'First.\rSecond.\r\r');
  const dense = '第一章 短段\n' + '中。啊！\n'.repeat(798);
  const result = segmentTextPage(1, dense);
  assert.ok(result.blocks.length <= 128); assert.equal(result.blocks[0].role, 'heading');
  assert.equal(result.blocks.map(block => block.text).join(''), dense);
  assert.ok(JSON.stringify(result).length < 320000);
});

test('text-store waits for page.saved before publishing, deduplicates and retries writes', async () => {
  let save, reject, writes = 0, requests = 0, published = 0;
  const store = new TextSentenceStore(3, {
    async request(type, payload) { requests++; assert.equal(type, 'text.page'); return { type: 'text.page', payload: { page: payload.page, text: 'Saved text.\n\n' } }; },
    pageText() { writes++; return new Promise((resolve, fail) => { save = resolve; reject = fail; }); },
  });
  store.onPageReady(() => published++);
  const first = store.ensure(1); assert.equal(store.ensure(1), first);
  await tick(); assert.equal(published, 0); assert.equal(store.cached(1), undefined);
  reject(new Error('WRITE_FAILED')); await assert.rejects(first, /WRITE_FAILED/);
  const retry = store.ensure(1); await tick(); save(); const result = await retry;
  assert.equal(published, 1); assert.equal(requests, 2); assert.equal(writes, 2);
  assert.equal(store.get('p1-b0-s0'), sentences(result)[0]);
  const second = store.ensure(2); await tick(); save(); await second;
  assert.deepEqual(store.readyPages().map(p => p.page), [1, 2]);
  store.trim(2, 0, 1); assert.ok(store.cached(1), 'active job page stays pinned before its checkpoint');
  store.trim(2, 0); assert.equal(store.cached(1), undefined); assert.equal(store.get('p1-b0-s0'), undefined);
  store.reset(); assert.deepEqual(store.readyPages(), []);
});

test('text-store rejects protocol errors, wrong pages, oversize pages and invalid page numbers', async () => {
  for (const [response, error] of [
    [{ type: 'protocol.error', payload: { code: 'BAD_TEXT_INDEX' } }, /BAD_TEXT_INDEX/],
    [{ type: 'text.page', payload: { page: 2, text: 'wrong' } }, /INVALID_TEXT_PAGE_PAYLOAD/],
    [{ type: 'text.page', payload: { page: 1, text: 'x'.repeat(4001) } }, /INVALID_TEXT_PAGE_PAYLOAD/],
  ]) {
    const store = new TextSentenceStore(1, { request: async () => response, pageText: async () => assert.fail('must not persist invalid text') });
    await assert.rejects(store.ensure(1), error);
    for (const page of [0, 2, NaN, 1.5]) await assert.rejects(store.ensure(page), /INVALID_TEXT_PAGE/);
  }
});

test('text-store reset invalidates an in-flight page persistence barrier', async () => {
  let save;
  const store = new TextSentenceStore(1, {
    request: async () => ({ type: 'text.page', payload: { page: 1, text: 'Text.' } }),
    pageText: () => new Promise(resolve => { save = resolve; }),
  });
  const pending = store.ensure(1); await tick(); store.reset(); save();
  await assert.rejects(pending, /TEXT_SESSION_RESET/); assert.equal(store.cached(1), undefined);
});
