import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { renderSentence } from '../web/reader/src/upstream/panel.ts';
import { setStrings, t } from '../web/reader/src/upstream/i18n.ts';
import { TranslationClient } from '../web/reader/src/upstream/translation-client.ts';

const sentence = { id: 'p1-b0-s0', page: 1, blockId: 'p1-b0', text: 'The reader remembers the last page.', rects: [] };
const span = () => ({ dataset: {}, textContent: '', title: '' });
const tick = () => new Promise(resolve => setImmediate(resolve));

test('inline errors use secondary text without the upstream red underline', async () => {
  const css = await readFile(new URL('../web/reader/android.css', import.meta.url), 'utf8');
  const rules = [...css.matchAll(/\.bilingualSentence\[data-status="error"\]\s*\{([^}]+)\}/g)];
  assert.equal(rules.length, 1, 'one error style shared by light and dark mode');
  assert.match(rules[0][1], /color:\s*var\(--vscode-descriptionForeground,\s*GrayText\)/);
  assert.match(rules[0][1], /text-decoration:\s*none\s*;/);
  assert.match(rules[0][1], /overflow-wrap:\s*anywhere\s*;/);
  assert.doesNotMatch(rules[0][1], /errorForeground|opacity|font-size/);
});

test('a failed sentence without retained text still shows the localized reason inline', () => {
  setStrings({ translationFailed: '翻译失败，点击重试：{0}' });
  try {
    for (const message of ['HTTP_500', 'TRUNCATED_STREAM_length', 'INFERENCE_FAILED', 'HOST_TIMEOUT']) {
      const element = span();
      renderSentence(element, sentence, { status: 'pending', draft: '读者记得最后一页' }, false);
      assert.match(element.textContent, /读者记得最后一页/);
      renderSentence(element, sentence, { status: 'error', message }, false);
      assert.equal(element.dataset.status, 'error');
      assert.equal(element.textContent.trim(), `翻译失败，点击重试：${message}`);
      assert.equal(element.title, element.textContent.trim());
      assert.ok(!element.textContent.includes(sentence.text));
      assert.ok(!element.textContent.includes('读者记得最后一页'));
    }
  } finally { setStrings({}); }
});

test('waiting, cancelled, streamed and accepted text retain their existing rendering', () => {
  const element = span();
  renderSentence(element, sentence, { status: 'error', message: 'REQUEST_FAILED' }, true);
  for (const state of [{ status: 'none' }, { status: 'pending' }]) {
    renderSentence(element, sentence, state, true);
    assert.equal(element.textContent, `${sentence.text} `);
    assert.equal(element.title, '');
  }
  renderSentence(element, sentence, { status: 'pending', draft: '读者记得' }, false);
  assert.equal(element.textContent, '读者记得 ▍');
  renderSentence(element, sentence, { status: 'done', translation: '读者记得最后一页。' }, false);
  assert.equal(element.textContent, '读者记得最后一页。');
  renderSentence(element, sentence, { status: 'done', translation: 'Translated.' }, true);
  assert.equal(element.textContent, 'Translated. ');
});

test('imperfect translations keep the exact warning as ordinary done text in both readers', () => {
  for (const spaced of [false, true]) {
    const element = span();
    Object.defineProperty(element, 'innerHTML', { set() { assert.fail('warning must be plain text, never markup'); } });
    const translation = '读者记得最后一页。（http_500）';
    renderSentence(element, sentence, { status: 'done', translation }, spaced);
    assert.equal(element.dataset.status, 'done');
    assert.equal(element.textContent, translation + (spaced ? ' ' : ''));
    assert.equal(element.title, '');
  }
});

test('legacy cached warnings display without placeholder stars in PDF and TXT without altering stored state', async () => {
  for (const spaced of [false, true]) {
    for (const [translation, expected] of [
      ['译文 *****（http_500）', '译文（http_500）'],
      ['译文（http_500）', '译文（http_500）'],
      ['旧译文 *****（protected_token_mismatch）', '旧译文'],
      ['Old translation（translation_language_or_source_copy）', 'Old translation'],
      ['旧译文 *****（protected_token_mismatch, http_500, translation_language_or_source_copy）', '旧译文（http_500）'],
      ['译文 *****（http_500）（host_timeout, http_500）', '译文（http_500, host_timeout）'],
      ['正文 *** 与 *****（http_500） 不是尾缀', '正文 *** 与 *****（http_500） 不是尾缀'],
    ]) {
      const element = span();
      Object.defineProperty(element, 'innerHTML', { set() { assert.fail('warning must stay ordinary text'); } });
      renderSentence(element, sentence, { status: 'done', translation }, spaced);
      assert.equal(element.textContent, expected + (spaced ? ' ' : ''));
      assert.equal(element.dataset.status, 'done');
      assert.equal(element.title, '');
    }
  }
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const request = h.sent.find(m => m.type === 'translate');
    const translation = '已缓存译文 *****（http_500）';
    h.client.handle({ type: 'translations', requestId: request.requestId, items: [
      { id: sentence.id, translation }, { id: h.neighbour.id, translation: '正常邻句' },
    ] });
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [] });
    h.client.refresh(); await tick();
    assert.deepEqual(h.client.state(sentence.id), { status: 'done', translation });
    assert.equal(h.elements.get(sentence.id).textContent, '已缓存译文（http_500）');
    assert.equal(h.sent.filter(m => m.type === 'translate').length, 1);
  } finally { h.client.reset(); }
});

test('a warning commit settles successfully without becoming an error or generating another request', async () => {
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const request = h.sent.find(m => m.type === 'translate');
    const translation = '读者记得最后一页。（http_500）';
    h.client.handle({ type: 'translations', requestId: request.requestId, items: [{ id: sentence.id, translation }] });
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [] });
    await tick();
    assert.equal(h.elements.get(sentence.id).textContent, translation);
    assert.equal(h.elements.get(sentence.id).dataset.status, 'done');
    assert.equal(h.elements.get(sentence.id).title, '');
    assert.equal(h.sent.filter(m => m.type === 'translate').length, 1);
  } finally { h.client.reset(); }
});

test('failure details are assigned as text, never interpreted as markup', () => {
  const element = span();
  Object.defineProperty(element, 'innerHTML', { set() { assert.fail('error text must not be HTML'); } });
  const message = '<img src=x onerror=alert(1)>';
  renderSentence(element, sentence, { status: 'error', message }, true);
  assert.equal(element.textContent.trim(), t('translationFailed', message));
});

function harness(options = {}) {
  const neighbour = { ...sentence, id: 'p1-b0-s1', text: 'The saved translation remains available.' };
  const page = { page: 1, blocks: [{ id: 'p1-b0', kind: 'text', sentences: [sentence, neighbour] }] };
  const sent = [], elements = new Map(page.blocks[0].sentences.map(s => [s.id, span()]));
  const client = new TranslationClient({
    app: { pdfViewer: { currentPageNumber: 1, pagesCount: 1 }, eventBus: { on() {} } },
    store: { onPageReady() {}, ensure: async () => page }, post: m => sent.push(m), range: 'page', ...options,
  });
  client.onChange((id, state) => {
    const source = page.blocks[0].sentences.find(s => s.id === id);
    renderSentence(elements.get(id), source, state, false);
  });
  return { client, sent, elements, neighbour };
}

test('draft failure becomes visible and retry success replaces it without changing a good neighbour', async () => {
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const request = h.sent.find(m => m.type === 'translate');
    h.client.handle({ type: 'translationDraft', requestId: request.requestId, items: [{ id: sentence.id, translation: '读者记得' }] });
    assert.equal(h.elements.get(sentence.id).textContent, '读者记得 ▍');
    h.client.handle({ type: 'translations', requestId: request.requestId, items: [{ id: h.neighbour.id, translation: '已保存的译文仍然可用。' }] });
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
    assert.equal(h.elements.get(sentence.id).textContent, '读者记得（http_500）');
    assert.deepEqual(h.client.state(sentence.id), { status: 'error', message: 'HTTP_500', translation: '读者记得' });
    assert.equal(h.elements.get(sentence.id).dataset.status, 'done');
    assert.equal(h.elements.get(sentence.id).title, '');
    assert.equal(h.elements.get(h.neighbour.id).textContent, '已保存的译文仍然可用。');

    const pending = h.client.translateBlockOf(sentence); await tick();
    const retry = h.sent.filter(m => m.type === 'translate').at(-1);
    assert.deepEqual(retry.sentences.map(s => s.id), [sentence.id]);
    assert.equal(h.elements.get(sentence.id).dataset.status, 'pending');
    assert.equal(h.elements.get(sentence.id).title, '');
    h.client.handle({ type: 'translations', requestId: retry.requestId, items: [{ id: sentence.id, translation: '读者记得最后一页。' }] });
    h.client.handle({ type: 'translationDone', requestId: retry.requestId, failures: [] });
    await pending;
    assert.equal(h.elements.get(sentence.id).textContent, '读者记得最后一页。');
    assert.equal(h.elements.get(sentence.id).title, '');
    assert.equal(h.elements.get(h.neighbour.id).textContent, '已保存的译文仍然可用。');
  } finally { h.client.reset(); }
});

for (const message of ['TRUNCATED_STREAM_length', 'HTTP_500', 'HOST_TIMEOUT']) {
  for (const spaced of [false, true]) test(`panel retains plain translation text for ${message} (spaced: ${spaced})`, () => {
    const element = span();
    Object.defineProperty(element, 'innerHTML', { set() { assert.fail('retained text must never be HTML'); } });
    renderSentence(element, sentence, { status: 'error', message: 'OLD_ERROR' }, spaced);
    const translation = '<img src=x onerror=alert(1)> 不完美译文';
    renderSentence(element, sentence, { status: 'error', message, translation }, spaced);
    assert.equal(element.textContent, `${translation}（${message.toLowerCase()}）${spaced ? ' ' : ''}`);
    assert.equal(element.dataset.status, 'done', 'use normal translation styling, not error color or underline');
    assert.equal(element.title, '');
  });

  for (const type of ['translationDraft', 'translations']) test(`client retains ${type} on ${message} while leaving a done neighbour alone`, async () => {
    const h = harness();
    try {
      h.client.refresh(); await tick();
      const request = h.sent.find(m => m.type === 'translate');
      const translation = '不完美译文';
      h.client.handle({ type, requestId: request.requestId, items: [{ id: sentence.id, translation }] });
      h.client.handle({ type: 'translations', requestId: request.requestId, items: [{ id: h.neighbour.id, translation: '正常邻句' }] });
      h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [{ id: sentence.id, message }] });
      assert.deepEqual(h.client.state(sentence.id), { status: 'error', message, translation });
      assert.equal(h.elements.get(sentence.id).textContent, `${translation}（${message.toLowerCase()}）`);
      assert.equal(h.elements.get(sentence.id).dataset.status, 'done');
      assert.equal(h.elements.get(sentence.id).title, '');
      assert.deepEqual(h.client.state(h.neighbour.id), { status: 'done', translation: '正常邻句' });
      h.client.refresh(); await tick();
      assert.equal(h.sent.filter(m => m.type === 'translate').length, 1, 'only explicit retry may repeat a failed request');
    } finally { h.client.reset(); }
  });
}

test('panel normalizes arbitrary error codes and merges only trailing warning suffixes', () => {
  const cases = [
    ['译文', ' __HTTP 500 / Read.Timeout:EOF-1__ ', '译文（http_500_read.timeout:eof-1）'],
    ['译文', 'İ HOST_TIMEOUT', '译文（i_host_timeout）'],
    ['译文', '错误 ! __', '译文（translation_failed）'],
    ['译文', 'A'.repeat(170), `译文（${'a'.repeat(160)}）`],
    ['译文（http_500, custom.code:retry-1）', 'HTTP_500', '译文（http_500, custom.code:retry-1）'],
    ['译文（truncated_stream_length）（http_500, truncated_stream_length）', 'HOST_TIMEOUT',
      '译文（truncated_stream_length, http_500, host_timeout）'],
    ['译文（host_timeout）', 'HOST_TIMEOUT', '译文（host_timeout）'],
    ['译文（HTTP_500）  ', 'HOST_TIMEOUT', '译文（http_500, host_timeout）'],
    ['译文 *****（http_500）', 'HOST_TIMEOUT', '译文（http_500, host_timeout）'],
    ['旧译文 *****（protected_token_mismatch, translation_language_or_source_copy）', 'HOST_TIMEOUT', '旧译文（host_timeout）'],
    ['旧译文（protected_token_mismatch, http_500）', 'HOST_TIMEOUT', '旧译文（http_500, host_timeout）'],
    ['译文 *****（http_500）（host_timeout）', 'HOST_TIMEOUT', '译文（http_500, host_timeout）'],
    ['正文 *** 与 *****（http_500） 不是尾缀', 'HOST_TIMEOUT', '正文 *** 与 *****（http_500） 不是尾缀（host_timeout）'],
    ['正文（http_500） 不是尾缀', 'HOST_TIMEOUT', '正文（http_500） 不是尾缀（host_timeout）'],
  ];
  for (const [translation, message, expected] of cases) {
    const element = span();
    renderSentence(element, sentence, { status: 'error', message, translation }, false);
    assert.equal(element.textContent, expected);
    assert.equal(element.dataset.status, 'done');
    assert.equal(element.title, '');
  }
});

test('empty or whitespace-only translations and drafts keep the original failure hint', async () => {
  for (const translation of [undefined, '', ' \n\t ']) {
    const element = span();
    renderSentence(element, sentence, { status: 'error', message: 'HTTP_500', translation }, false);
    assert.equal(element.dataset.status, 'error');
    assert.equal(element.textContent.trim(), t('translationFailed', 'HTTP_500'));
    assert.equal(element.title, t('translationFailed', 'HTTP_500'));
    for (const type of ['translationDraft', 'translations']) {
      const h = harness();
      try {
        h.client.refresh(); await tick();
        const request = h.sent.find(m => m.type === 'translate');
        if (translation !== undefined) h.client.handle({ type, requestId: request.requestId, items: [{ id: sentence.id, translation }] });
        h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
        assert.deepEqual(h.client.state(sentence.id), { status: 'error', message: 'HTTP_500' });
        assert.equal(h.elements.get(sentence.id).textContent.trim(), t('translationFailed', 'HTTP_500'));
      } finally { h.client.reset(); }
    }
  }
});

test('retained text stays an internal failure for whole-document progress', async () => {
  const h = harness({ range: 'manual', onPageCompleted() {} });
  try {
    h.client.handle({ type: 'translateAll', jobId: 12 }); await tick();
    const request = h.sent.find(m => m.type === 'translate');
    h.client.handle({ type: 'translationDraft', requestId: request.requestId, items: [{ id: sentence.id, translation: '未提交草稿' }] });
    h.client.handle({ type: 'translations', requestId: request.requestId, items: [{ id: h.neighbour.id, translation: '正常邻句' }] });
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
    await tick();
    assert.equal(h.client.state(sentence.id).status, 'error');
    assert.equal(h.elements.get(sentence.id).dataset.status, 'done');
    assert.equal(h.sent.find(m => m.type === 'translateAllProgress').failed, true);
  } finally { h.client.reset(); }
});

test('stale owner, unknown request and settled messages cannot replace the current draft or failure', async () => {
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const old = h.sent.find(m => m.type === 'translate');
    const retryPending = h.client.translateBlockOf(sentence); await tick();
    const current = h.sent.filter(m => m.type === 'translate').at(-1);
    assert.notEqual(current.requestId, old.requestId);
    h.client.handle({ type: 'translationDraft', requestId: current.requestId, items: [{ id: sentence.id, translation: '当前草稿' }] });
    for (const requestId of [old.requestId, 99999]) {
      h.client.handle({ type: 'translationDraft', requestId, items: [{ id: sentence.id, translation: '旧草稿' }] });
      h.client.handle({ type: 'translations', requestId, items: [{ id: sentence.id, translation: '旧译文' }] });
      h.client.handle({ type: 'translationDone', requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
      assert.deepEqual(h.client.state(sentence.id), { status: 'pending', draft: '当前草稿' });
    }
    h.client.handle({ type: 'translationDone', requestId: current.requestId, failures: [{ id: sentence.id, message: 'HOST_TIMEOUT' }] });
    await retryPending;
    for (const requestId of [old.requestId, current.requestId]) {
      h.client.handle({ type: 'translationDraft', requestId, items: [{ id: sentence.id, translation: '迟到草稿' }] });
      h.client.handle({ type: 'translations', requestId, items: [{ id: sentence.id, translation: '迟到译文' }] });
      h.client.handle({ type: 'translationDone', requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
    }
    assert.deepEqual(h.client.state(sentence.id), { status: 'error', message: 'HOST_TIMEOUT', translation: '当前草稿' });
    assert.equal(h.elements.get(sentence.id).textContent, '当前草稿（host_timeout）');
  } finally { h.client.reset(); }
});

test('reset fences old-generation failures and drafts from a new request', async () => {
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const old = h.sent.find(m => m.type === 'translate');
    h.client.reset(); h.client.refresh(); await tick();
    const current = h.sent.filter(m => m.type === 'translate').at(-1);
    h.client.handle({ type: 'translationDraft', requestId: current.requestId, items: [{ id: sentence.id, translation: '新文档草稿' }] });
    h.client.handle({ type: 'translationDraft', requestId: old.requestId, items: [{ id: sentence.id, translation: '旧文档草稿' }] });
    h.client.handle({ type: 'translationDone', requestId: old.requestId, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
    assert.deepEqual(h.client.state(sentence.id), { status: 'pending', draft: '新文档草稿' });
  } finally { h.client.reset(); }
});

test('reading-only sessions ignore all host translation results and failures', async () => {
  const h = harness({ readingOnly: true });
  try {
    h.client.refresh(); await tick();
    h.client.handle({ type: 'translationDraft', requestId: 1, items: [{ id: sentence.id, translation: '草稿' }] });
    h.client.handle({ type: 'translations', requestId: 1, items: [{ id: sentence.id, translation: '译文' }] });
    h.client.handle({ type: 'translationDone', requestId: 1, failures: [{ id: sentence.id, message: 'HTTP_500' }] });
    assert.deepEqual(h.client.state(sentence.id), { status: 'none' });
    assert.equal(h.sent.length, 0);
  } finally { h.client.reset(); }
});

test('cancellation clears a draft without falsely displaying a translation error', async () => {
  const h = harness();
  try {
    h.client.refresh(); await tick();
    const request = h.sent.find(m => m.type === 'translate');
    h.client.handle({ type: 'translationDraft', requestId: request.requestId, items: [{ id: sentence.id, translation: '临时草稿' }] });
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [], cancelled: true });
    assert.equal(h.elements.get(sentence.id).dataset.status, 'none');
    assert.equal(h.elements.get(sentence.id).textContent, `${sentence.text} `);
    assert.equal(h.elements.get(sentence.id).title, '');
  } finally { h.client.reset(); }
});
