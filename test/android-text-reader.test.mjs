import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { parseTextPage, restoreTextReading } from '../web/reader/src/text-reader.ts';

const tick = () => new Promise(resolve => setImmediate(resolve));
const bundle = build({ entryPoints: [fileURLToPath(new URL('../web/reader/src/text-entry.ts', import.meta.url))], bundle: true, format: 'iife', write: false });

// Deliberately small DOM mock: these are Web protocol/UI tests, not Android device tests.
class Element {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.style = {}; this.dataset = {}; this.attrs = {}; this.value = ''; this.scrollTop = 0; this.scrollHeight = 2000; this.hidden = false; this.listeners = new Map(); this.classes = new Set(); this.classList = { toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name) }; }
  append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
  replaceChildren(...children) { this.children = []; this.append(...children); this.scrollTop = 0; }
  setAttribute(key, value) { this.attrs[key] = value; }
  removeAttribute(key) { delete this.attrs[key]; }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); }
  querySelector(selector) { return this.children.find(child => selector === 'option[value="more"]' && child.value === 'more'); }
  closest() { return ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(this.tagName) ? this : null; }
  getBoundingClientRect() { return { top: 0, bottom: 50 }; }
  set innerHTML(_) { assert.fail('TXT must never interpret source as HTML'); }
}
function find(root, predicate) { if (predicate(root)) return root; for (const child of root.children) { const found = find(child, predicate); if (found) return found; } }
async function harness({ background = false, readingOnly = false, reading = {}, language = 'en', chapters = [], chapterCount = chapters.length, pages = 3, range = 'manual',
  textForPage = page => `Page ${page}.\n\n<script>not markup</script>\n`, onSend } = {}) {
  const sent = [], listeners = new Map(), timers = new Map(); let timer = 0, deliver;
  const origin = 'https://appassets.androidplatform.net';
  const document = { documentElement: { style: {}, lang: language }, body: new Element('body'), createElement: tag => new Element(tag), title: '' };
  const window = { origin, innerHeight: 800,
    addEventListener(type, listener) { if (!listeners.has(type)) listeners.set(type, new Set()); listeners.get(type).add(listener); },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener); },
    postMessage(data) { queueMicrotask(() => { for (const listener of listeners.get('message') ?? []) listener({ origin, data }); }); },
    ParaReadNative: { postMessage(text) {
      const m = JSON.parse(text); sent.push(m);
      if (onSend?.(m, deliver) === false) return;
      if (m.type === 'text.page') deliver('text.page', { page: m.payload.page, text: textForPage(m.payload.page) }, m.requestId);
      if (m.type === 'page.textChunk' && m.payload.chunkIndex === m.payload.chunkCount - 1) deliver('page.saved', { page: m.payload.page }, m.requestId);
      if (m.type === 'reading.save') deliver('reading.saved', {}, m.requestId);
      if (m.type === 'translation.cacheGet') deliver('translation.finished', { failures: [] }, m.requestId);
      if (m.type === 'translation.request') {
        deliver('translation.committed', { sequence: 0, items: m.payload.sentences.map(s => ({ id: s.id, translation: '译文。' })) }, m.requestId);
        deliver('translation.finished', { failures: [] }, m.requestId);
      }
      if (m.type === 'job.progress') deliver('job.saved', { status: 'RUNNING' }, m.requestId);
      if (m.type === 'job.finished') deliver('job.saved', { status: m.payload.status }, m.requestId);
    } },
  };
  Object.defineProperty(window, 'PDFViewerApplication', { get() { assert.fail('TXT must not touch PDF.js'); } });
  const context = vm.createContext({ window, document, location: { origin }, TextEncoder, Uint8Array, Map, Set, console,
    setTimeout(fn) { timers.set(++timer, fn); return timer; }, clearTimeout(id) { timers.delete(id); },
    acquireVsCodeApi: () => window.acquireVsCodeApi(),
  });
  vm.runInContext((await bundle).outputFiles[0].text, context);
  deliver = (type, payload, requestId = 'open') => window.ParaReadNative.onmessage({ data: JSON.stringify({ version: 1, type, payload,
    requestId, sessionId: 'session', documentId: 'doc', documentRevision: 'revision', viewportEpoch: 0, configRevision: 1 }) });
  deliver('session.open', { format: 'txt', text: { pages, chapters, chapterCount, encoding: 'UTF-8', indexVersion: 1 }, reading,
    settings: { backgroundTranslation: background, readingOnly, translateRange: range, uiLanguage: language } });
  await tick(); await tick();
  const command = payload => deliver('reader.command', payload);
  const hide = () => { for (const listener of [...(listeners.get('pagehide') ?? [])]) listener({}); };
  return { sent, window, document, listeners, timers, deliver, command, hide,
    element: predicate => find(document.body, predicate),
    async settle() { await tick(); await tick(); },
  };
}

test('text-reader validates page input and restores bounded font, page and scroll state', () => {
  for (const value of ['', '0', '-1', '1.2', '1e2', 'Infinity', '<img>', '4']) assert.equal(parseTextPage(value, 3), undefined);
  assert.equal(parseTextPage(' 02 ', 3), 2);
  assert.deepEqual(restoreTextReading({ page: 99, txt: { fontSize: 60, showSource: false, scrollTop: -1 } }, 3),
    { page: 3, txt: { fontSize: 32, showSource: false, scrollTop: 0 } });
  assert.deepEqual(restoreTextReading({ page: NaN, txt: { fontSize: '18', scrollTop: Infinity } }, 3),
    { page: 1, txt: { fontSize: 18, showSource: true, scrollTop: 0 } });
});

test('text-reader restores manual mode cache without automatic translation and supports navigation/settings/fullscreen', async () => {
  const h = await harness({ reading: { page: 2, txt: { fontSize: 22, scrollTop: 140, showSource: false } } });
  const content = h.element(el => el.tagName === 'MAIN');
  assert.equal(content.style.fontSize, '22px'); assert.equal(content.scrollTop, 140);
  assert.equal(h.sent.some(m => m.type === 'translation.request'), false);
  assert.equal(h.sent.some(m => m.type === 'translation.cacheGet'), true);
  assert.equal(h.sent.some(m => m.type === 'reading.save'), false, 'restoring must not overwrite saved scroll');
  assert.match(h.element(el => el.className === 'bilingualSource' && el.textContent.includes('<script>')).textContent, /<script>not markup<\/script>/);
  assert.equal(h.element(el => el.className === 'textReader').classes.has('textHideSource'), true);
  const input = h.element(el => el.inputMode === 'numeric'); input.value = 'bad';
  h.element(el => el.tagName === 'FORM').onsubmit({ preventDefault() {} });
  assert.equal(input.attrs['aria-invalid'], 'true');
  assert.match(h.element(el => el.className === 'textStatus').textContent, /Invalid/);
  input.value = '3'; h.element(el => el.tagName === 'FORM').onsubmit({ preventDefault() {} }); await h.settle();
  assert.equal(content.scrollTop, 0); assert.equal(h.sent.filter(m => m.type === 'reading.save').at(-1).payload.page, 3);
  assert.equal(h.element(el => el.textContent === 'Next').disabled, true);
  const font = h.element(el => el.type === 'range'); font.value = '30'; content.scrollTop = 100; font.oninput();
  assert.equal(input.value, '3'); assert.equal(content.style.fontSize, '30px'); assert.equal(content.scrollTop, 100);
  const before = h.sent.filter(m => m.type === 'text.page').length;
  for (const listener of h.listeners.get('keydown')) listener({ key: 'ArrowLeft', target: input });
  assert.equal(h.sent.filter(m => m.type === 'text.page').length, before, 'typing never turns a page');
  for (const listener of h.listeners.get('keydown')) listener({ key: 'ArrowLeft', target: content, preventDefault() {} });
  await h.settle(); assert.equal(input.value, '2');
  h.command({ type: 'readerFullscreen', enabled: true }); await h.settle();
  assert.equal(h.element(el => el.tagName === 'HEADER').hidden, true);
  h.element(el => el.className === 'textShowControls').onclick();
  assert.equal(h.element(el => el.tagName === 'HEADER').hidden, false);
  h.command({ type: 'translateCurrentPage' }); await h.settle();
  assert.equal(h.sent.filter(m => m.type === 'translation.request').at(-1).payload.priority, 0);
  h.command({ type: 'settings', translateRange: 'nearby' }); await h.settle();
  assert.ok(h.sent.some(m => m.type === 'text.page' && m.payload.page === 1));
  h.hide(); const saved = h.sent.filter(m => m.type === 'reading.save').at(-1).payload;
  assert.equal(saved.page, 2); assert.equal(saved.txt.fontSize, 30);
  assert.equal(h.listeners.get('message').size, 0);
});

test('text-reader ignores stale page loads after rapid navigation and retains the intended restore anchor on early exit', async () => {
  const pending = [];
  const h = await harness({ reading: { page: 1, txt: { scrollTop: 300 } }, onSend(m) {
    if (m.type === 'text.page') { pending.push(m); return false; }
  } });
  h.element(el => el.textContent === 'Next').onclick();
  h.element(el => el.textContent === 'Next').onclick();
  assert.deepEqual(pending.map(m => m.payload.page), [1, 2, 3]);
  for (const m of pending.toReversed()) h.deliver('text.page', { page: m.payload.page, text: `Only page ${m.payload.page}.` }, m.requestId);
  await h.settle();
  assert.equal(h.element(el => el.className === 'bilingualSource').textContent, 'Only page 3.');
  assert.equal(h.element(el => el.inputMode === 'numeric').value, '3'); h.hide();
  const restoring = await harness({ reading: { page: 2, txt: { scrollTop: 300 } }, onSend(m) { if (m.type === 'text.page') return false; } });
  restoring.hide();
  assert.equal(restoring.sent.filter(m => m.type === 'reading.save').at(-1).payload.txt.scrollTop, 300);
});

test('text-reader renders each natural paragraph as a source/translation pair and retains heading and blank lines', async () => {
  const text = '第一章 风雪\n\n  First paragraph.\n\nSecond paragraph.\n';
  const h = await harness({ textForPage: () => text, language: 'zh' });
  const content = h.element(el => el.tagName === 'MAIN');
  assert.equal(content.children.length, 3);
  assert.equal(content.children[0].attrs.role, 'heading');
  assert.equal(content.children.map(section => section.children.find(el => el.className === 'bilingualSource').textContent).join(''), text);
  h.command({ type: 'translateCurrentPage' }); await h.settle();
  assert.equal(content.children.length, 3, 'translations cannot collapse distinct paragraphs');
  for (const section of content.children) {
    assert.equal(section.children[0].className, 'bilingualTarget');
    assert.ok(section.children[0].children.some(el => el.textContent === '\n\n' || el.textContent === '\n'));
    assert.equal(section.children[1].className, 'bilingualSource');
  }
  assert.equal(h.sent.filter(m => m.type === 'translation.request').at(-1).payload.sentences.length, 3);
  h.hide();
});

test('text-reader read-only displays only original text and preserves controls/preferences without translation/cache requests', async () => {
  const h = await harness({ readingOnly: true, range: 'nearby', reading: { page: 2, txt: { fontSize: 24, showSource: true, scrollTop: 80 } } });
  const content = h.element(el => el.tagName === 'MAIN');
  assert.equal(content.style.fontSize, '24px'); assert.equal(content.scrollTop, 80);
  assert.equal(h.element(el => el.className === 'bilingualSource'), undefined);
  assert.equal(h.element(el => el.className === 'bilingualSentence'), undefined, 'original-only text has no translation click targets');
  assert.equal(content.children.map(section => section.children[0].textContent).join(''), 'Page 2.\n\n<script>not markup</script>\n');
  assert.equal(h.element(el => el.textContent === 'Translate page').hidden, true);
  assert.equal(h.element(el => el.type === 'checkbox').parent.hidden, true);
  for (const type of ['translateCurrentPage', 'translateAll', 'retryFailed']) h.command({ type, jobId: 12 });
  h.command({ type: 'settings', readingOnly: false, translateRange: 'nearby' });
  h.element(el => el.textContent === 'Next').onclick(); await h.settle();
  assert.equal(h.element(el => el.inputMode === 'numeric').value, '3');
  const font = h.element(el => el.type === 'range'); font.value = '28'; font.oninput();
  assert.equal(content.style.fontSize, '28px');
  h.hide();
  assert.equal(h.sent.some(m => ['translation.request', 'translation.cacheGet', 'job.progress', 'job.finished'].includes(m.type)), false);
  const saved = h.sent.filter(m => m.type === 'reading.save').at(-1).payload;
  assert.equal(saved.page, 3); assert.equal(saved.txt.fontSize, 28); assert.equal(saved.txt.showSource, true, 'read-only must not overwrite bilingual original-visibility preference');
});

test('text-reader mode flush saves unthrottled font and scroll before acknowledging safe replacement', async () => {
  const h = await harness({ readingOnly: true });
  const content = h.element(el => el.tagName === 'MAIN');
  const font = h.element(el => el.type === 'range'); font.value = '29'; font.oninput();
  content.scrollTop = 333; content.listeners.get('scroll')();
  assert.equal(h.sent.some(m => m.type === 'reading.save'), false, 'ordinary throttle has not fired yet');
  h.command({ type: 'flushBeforeModeSwitch', flushId: 'txt-mode' }); await h.settle();
  const saved = h.sent.find(m => m.type === 'reading.save');
  assert.equal(saved.payload.txt.fontSize, 29); assert.equal(saved.payload.txt.scrollTop, 333);
  const flushed = h.sent.find(m => m.type === 'reader.flushed');
  assert.equal(flushed.payload.success, true); assert.equal(flushed.payload.flushId, 'txt-mode');
  assert.equal(h.document.body.inert, true);
  h.command({ type: 'resumeAfterModeFlush', flushId: 'txt-mode' }); assert.equal(h.document.body.inert, false);
  h.hide();
});

test('text-reader explicit background translation ignores a foreground read-only preference', async () => {
  const h = await harness({ readingOnly: true, background: true, onSend(m, deliver) {
    if (m.type === 'session.opened') deliver('reader.command', { type: 'translateAll', jobId: 56 });
  } });
  assert.equal(h.sent.find(m => m.type === 'job.finished').payload.status, 'COMPLETED');
  assert.ok(h.sent.some(m => m.type === 'translation.request'));
  assert.equal(h.document.body.children.length, 0);
});

test('text-reader blank whole-job pages checkpoint without a model request or synthetic empty sentence', async () => {
  const h = await harness({ background: true, textForPage: () => ' \n\n', onSend(m, deliver) {
    if (m.type === 'session.opened') deliver('reader.command', { type: 'translateAll', jobId: 55 });
  } });
  assert.equal(h.sent.some(m => m.type === 'translation.request' || m.type === 'translation.cacheGet'), false);
  assert.deepEqual(h.sent.filter(m => m.type === 'job.progress').map(m => [m.payload.done, m.payload.failed]), [[1, false], [2, false], [3, false]]);
  assert.equal(h.sent.find(m => m.type === 'job.finished').payload.status, 'COMPLETED');
  assert.equal(h.sent.some(m => m.type === 'reading.save'), false);
  assert.equal(h.listeners.get('message').size, 0);
});

test('text-reader foreground whole-book progress uses the existing priority protocol without replacing the visible page', async () => {
  const h = await harness({ range: 'page' });
  assert.ok(h.sent.some(m => m.type === 'translation.request' && m.payload.priority === 1));
  h.command({ type: 'translateAll', jobId: 21 }); await h.settle();
  assert.deepEqual(h.sent.filter(m => m.type === 'job.progress').map(m => m.payload.done), [1, 2, 3]);
  assert.ok(h.sent.some(m => m.type === 'translation.request' && m.payload.priority === 3));
  assert.equal(h.element(el => el.inputMode === 'numeric').value, '1');
  assert.equal(h.element(el => el.className === 'textStatus').textContent, 'Translation finished');
  assert.equal(h.listeners.get('message').size, 1); h.hide();
});

test('text-reader inline translation failures reuse renderSentence without interpreting failure markup', async () => {
  const h = await harness({ onSend(m, deliver) {
    if (m.type === 'translation.request') { deliver('protocol.error', { code: '<img> failed' }, m.requestId); return false; }
  } });
  h.command({ type: 'translateCurrentPage' }); await h.settle();
  const sentence = h.element(el => el.className === 'bilingualSentence');
  assert.equal(sentence.dataset.status, 'error');
  assert.match(sentence.textContent, /Translation failed, click to retry: <img> failed/); h.hide();
});

test('text-reader localizes controls and lazily loads further chapter batches without truncating', async () => {
  const chapters = Array.from({ length: 100 }, (_, n) => ({ title: `章 ${n + 1}`, page: 1 }));
  const offsets = [];
  const h = await harness({ language: 'zh', chapters, chapterCount: 205, onSend(m, deliver) {
    if (m.type === 'text.chapters') {
      offsets.push(m.payload.offset); assert.equal(m.payload.count, 100);
      const next = Array.from({ length: Math.min(100, 205 - m.payload.offset) }, (_, n) => ({ title: `章 ${m.payload.offset + n + 1}`, page: 2 }));
      deliver('text.chapters', { chapters: next, total: 205 }, m.requestId);
    }
  } });
  const select = h.element(el => el.tagName === 'SELECT');
  assert.equal(select.children[0].textContent, '无章节 / 开始');
  for (let i = 0; i < 2; i++) { select.value = 'more'; select.onchange(); await h.settle(); }
  assert.deepEqual(offsets, [100, 200]); assert.equal(select.children.length, 206);
  assert.equal(select.children.some(el => el.value === 'more'), false);
  select.value = '2'; select.onchange(); await h.settle();
  assert.equal(h.sent.filter(m => m.type === 'reading.save').at(-1).payload.txt.scrollTop, 0);
  assert.ok(h.element(el => el.textContent === '上一页')); h.hide();
});

test('text-reader session.opened accepts immediate whole-job command and preserves both durable barriers in headless mode', async () => {
  let pageBarrier, jobBarrier, finishBarrier;
  const h = await harness({ background: true, chapterCount: 1000, onSend(m, deliver) {
    if (m.type === 'session.opened') deliver('reader.command', { type: 'translateAll', jobId: 17 });
    if (m.type === 'page.textChunk') { pageBarrier = m; return false; }
    if (m.type === 'job.progress') { jobBarrier = m; return false; }
    if (m.type === 'job.finished') { finishBarrier = m; return false; }
  } });
  assert.equal(h.document.body.children.length, 0);
  assert.equal(h.sent.some(m => m.type === 'translation.request'), false);
  for (let page = 1; page <= 3; page++) {
    assert.equal(pageBarrier.payload.page, page);
    h.deliver('page.saved', { page }, pageBarrier.requestId); await h.settle();
    assert.equal(jobBarrier.payload.done, page); assert.equal(jobBarrier.payload.failed, false);
    assert.equal(h.sent.filter(m => m.type === 'text.page').length, page, 'job.saved precedes next extraction');
    h.deliver('job.saved', { status: 'RUNNING' }, jobBarrier.requestId); await h.settle();
  }
  assert.equal(finishBarrier.payload.status, 'COMPLETED');
  assert.equal(h.listeners.get('message').size, 1, 'cleanup must wait for final job.saved');
  h.deliver('job.saved', { status: 'COMPLETED' }, finishBarrier.requestId); await h.settle();
  assert.equal(h.listeners.get('message').size, 0);
  assert.equal(h.sent.some(m => ['reading.save', 'text.chapters', 'session.error', 'page.cacheGet'].includes(m.type)), false);
  h.hide();
});

test('text-reader headless cancellation and persistence failure never advance an incomplete page', async () => {
  for (const failure of [false, true]) {
    let pageBarrier;
    const h = await harness({ background: true, onSend(m, deliver) {
      if (m.type === 'session.opened') deliver('reader.command', { type: 'translateAll', jobId: 31 });
      if (m.type === 'page.textChunk') { pageBarrier = m; return false; }
    } });
    if (failure) h.deliver('protocol.error', { code: 'TXT_WRITE_FAILED' }, pageBarrier.requestId);
    else { h.command({ type: 'cancelTranslateAll', jobId: 31 }); h.deliver('page.saved', { page: 1 }, pageBarrier.requestId); }
    await h.settle();
    assert.equal(h.sent.filter(m => m.type === 'text.page').length, 1);
    assert.equal(h.sent.some(m => m.type === 'job.progress'), false);
    assert.equal(h.sent.find(m => m.type === 'job.finished').payload.status, failure ? 'ERROR' : 'CANCELLED');
    assert.equal(h.sent.some(m => m.type === 'reading.save'), false); h.hide();
  }
});

test('TXT tablet layout places original left and translation right without affecting hidden-source or narrow layout', async () => {
  const css = await readFile(new URL('../web/reader/text.css', import.meta.url), 'utf8');
  assert.match(css, /@media\s*\(min-width:\s*768px\)/);
  assert.match(css, /\.textReader:not\(\.textHideSource\) \.textBlock\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/);
  assert.match(css, /\.textReader:not\(\.textHideSource\) \.bilingualSource\s*\{[^}]*grid-column:\s*1;[^}]*grid-row:\s*1;[^}]*margin-top:\s*0/);
  assert.match(css, /\.textReader:not\(\.textHideSource\) \.bilingualTarget\s*\{[^}]*grid-column:\s*2;[^}]*grid-row:\s*1/);
});

test('TXT entry has local CSP and shared error styles, without PDF or annotation assets', async () => {
  const html = await readFile(new URL('../web/reader/text.html', import.meta.url), 'utf8');
  for (const asset of ['panel.css', 'android.css', 'text.css', 'text.mjs']) assert.ok(html.includes(`./${asset}`));
  assert.match(html, /object-src 'none'; frame-src 'none'; base-uri 'none'/);
  assert.doesNotMatch(html, /pdfjs|viewer\.css|annotation|export|https?:\/\//i);
  const css = await readFile(new URL('../web/reader/text.css', import.meta.url), 'utf8');
  assert.match(css, /\.textContent \.bilingualSource\s*\{[^}]*display:\s*block/);
  assert.match(css, /\.textHideSource \.bilingualSource\s*\{[^}]*display:\s*none/);
  assert.doesNotMatch(css, /errorForeground|wavy/);
  const text = (await bundle).outputFiles[0].text;
  assert.doesNotMatch(text, /pdfjs\/build|pdfjs\/web|startBackgroundTranslation/);
});
