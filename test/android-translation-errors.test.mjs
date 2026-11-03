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

test('a failed sentence shows the localized reason inline instead of its source or draft', () => {
  setStrings({ translationFailed: '翻译失败，点击重试：{0}' });
  try {
    for (const message of ['PROTECTED_TOKEN_MISMATCH', 'TRUNCATED_STREAM_length', 'TRANSLATION_LANGUAGE_OR_SOURCE_COPY', 'HOST_TIMEOUT']) {
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

test('failure details are assigned as text, never interpreted as markup', () => {
  const element = span();
  Object.defineProperty(element, 'innerHTML', { set() { assert.fail('error text must not be HTML'); } });
  const message = '<img src=x onerror=alert(1)>';
  renderSentence(element, sentence, { status: 'error', message }, true);
  assert.equal(element.textContent.trim(), t('translationFailed', message));
});

function harness() {
  const neighbour = { ...sentence, id: 'p1-b0-s1', text: 'The saved translation remains available.' };
  const page = { page: 1, blocks: [{ id: 'p1-b0', kind: 'text', sentences: [sentence, neighbour] }] };
  const sent = [], elements = new Map(page.blocks[0].sentences.map(s => [s.id, span()]));
  const client = new TranslationClient({
    app: { pdfViewer: { currentPageNumber: 1, pagesCount: 1 }, eventBus: { on() {} } },
    store: { onPageReady() {}, ensure: async () => page }, post: m => sent.push(m), range: 'page',
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
    h.client.handle({ type: 'translationDone', requestId: request.requestId, failures: [{ id: sentence.id, message: 'PROTECTED_TOKEN_MISMATCH' }] });
    assert.equal(h.elements.get(sentence.id).textContent.trim(), t('translationFailed', 'PROTECTED_TOKEN_MISMATCH'));
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
