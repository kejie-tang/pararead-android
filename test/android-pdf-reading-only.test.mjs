import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const counters = `
const count = name => globalThis.calls.push(name);
export class SentenceStore {
  constructor() { count('SentenceStore'); }
  async ensure(page) { count('ensure:' + page); }
  get(id) { return { id, page: 3 }; }
  trim() {} reset() {} readyPages() { return []; }
}
export class ColumnOrderSegmenter { constructor() { count('ColumnOrderSegmenter'); } }
export class HighlightOverlay { constructor() { count('HighlightOverlay'); } show() {} clear() {} }
export function scrollToSentence() {}
export class TranslationClient {
  constructor(options) { count('TranslationClient'); globalThis.clientOptions = options; }
  onChange() {} trim() {} reset() {} refresh() { count('refresh'); } handle() { return false; }
}
export class SelectionController {
  constructor() { count('SelectionController'); this.listeners = []; }
  onChange(listener) { this.listeners.push(listener); }
  pin(sentence, source) { count('pin:' + sentence.id); this.listeners.forEach(fn => fn({ sentence, source })); }
  clear() { this.listeners.forEach(fn => fn(null)); }
}
export class TranslationPanel {
  constructor() { count('TranslationPanel'); }
  trim() {} reset() {} setTargetLanguage() {}
}
export class ScrollSync { constructor() { count('ScrollSync'); } schedule() {} }
export class Connector { constructor() { count('Connector'); } schedule() {} }
export function installPointerSelect() { count('installPointerSelect'); }
export function setStrings() {}
export class ReaderChrome {
  constructor(app, panel, selection) { count(panel === null && selection === null ? 'originalChrome' : 'bilingualChrome'); }
  setFullscreen(enabled) { count('fullscreen:' + enabled); }
}
export class SaveBridge {
  constructor() { count('SaveBridge'); }
  async handle(message) { count('saveHandle:' + message.type); return message.type === 'getData'; }
}
export async function startBackgroundTranslation() { count('background'); }
`;
const entry = fileURLToPath(new URL('../web/reader/src/upstream/main.ts', import.meta.url));
const built = await build({ entryPoints: [entry], bundle: true, format: 'iife', write: false,
  plugins: [{ name: 'pdf-ui-test-doubles', setup(b) {
    b.onResolve({ filter: /\/(connector|highlight-overlay|i18n|panel|pointer-select|save-bridge|scroll-sync|reading-order|selection|sentence-store|translation-client|reader-chrome|background-translation)$/ }, () => ({ path: 'doubles', namespace: 'test' }));
    b.onLoad({ filter: /.*/, namespace: 'test' }, () => ({ contents: counters, loader: 'js' }));
    b.onResolve({ filter: /\/viewer\.mjs$/ }, () => ({ path: 'viewer', namespace: 'viewer-test' }));
    b.onLoad({ filter: /.*/, namespace: 'viewer-test' }, () => ({ contents: '', loader: 'js' }));
  } }],
});

async function harness(settings) {
  const calls = [], posted = [], events = new Map(), messages = [], timers = [];
  let selectedReads = 0;
  const reading = { page: 3, zoom: '1.25', rotation: 90, location: 'page=3&zoom=125,10,20',
    get selectedId() { selectedReads++; return 'p3-b0-s0'; }, selectedSource: 'panel' };
  const session = { settings, reading, length: 1000 };
  const viewer = { pagesPromise: Promise.resolve(), pagesCount: 6, currentPageNumber: 1, currentScaleValue: 'auto', pagesRotation: 0 };
  const app = { initializedPromise: Promise.resolve(), pdfViewer: viewer, isInitialViewSet: true,
    eventBus: { on(name, fn) { events.set(name, [...(events.get(name) ?? []), fn]); } },
    pdfLinkService: { setHash(hash) { calls.push('hash:' + hash); } },
    async open() { calls.push('open'); },
  };
  const window = { origin: 'https://reader.test', PDFViewerApplication: app, PDFViewerApplicationOptions: { set() {} },
    addEventListener(name, fn) { if (name === 'message') messages.push(fn); },
    paraRead: { ready: Promise.resolve(session), post(type, payload) { posted.push({ type, payload }); },
      setReadingStateFlusher(flusher) { calls.push('registerReadingFlusher'); this.flushReadingState = flusher; },
      viewport(page) { calls.push('viewport:' + page); }, makeRangeTransport() { return {}; } },
  };
  const context = vm.createContext({ window, calls, pdfjsLib: { GlobalWorkerOptions: {} },
    document: { querySelector: () => ({ dataset: { config: JSON.stringify({ url: '' }) } }), addEventListener() {} },
    location: { href: 'https://reader.test/' }, acquireVsCodeApi: () => ({ postMessage(message) { posted.push(message); } }),
    URL, console, performance, setTimeout(fn) { timers.push(fn); return timers.length; },
    clearTimeout(id) { if (id !== undefined) timers[id - 1] = () => {}; }, requestAnimationFrame(fn) { fn(); },
  });
  vm.runInContext(built.outputFiles[0].text, context);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(posted.some(m => m.type === 'session.error'), false, JSON.stringify(posted));
  const deliver = async data => { for (const fn of messages) await fn({ origin: window.origin, data }); };
  return { calls, posted, viewer, app, context, events, window, timers, deliver, selectedReads: () => selectedReads };
}

const machinery = ['SentenceStore', 'ColumnOrderSegmenter', 'HighlightOverlay', 'TranslationClient', 'SelectionController', 'TranslationPanel', 'ScrollSync', 'Connector', 'installPointerSelect'];
test('main.ts original-only entry never constructs translation, selection or segmentation machinery', async () => {
  const h = await harness({ readingOnly: true, selectOnHover: true, translateRange: 'nearby', readerFullscreen: true });
  for (const name of machinery) assert.equal(h.calls.includes(name), false, name);
  assert.ok(h.calls.includes('SaveBridge'));
  assert.ok(h.calls.includes('originalChrome'));
  assert.ok(h.calls.includes('fullscreen:true'));
  assert.equal(h.selectedReads(), 0, 'do not even consult saved bilingual selection');
  assert.equal(h.events.has('textlayerrendered'), false, 'PDF text layers must not start segmentation');
  assert.equal(h.window.__bilingual, undefined);
  assert.equal(h.viewer.currentPageNumber, 3);
  assert.equal(h.viewer.currentScaleValue, '1.25');
  assert.equal(h.viewer.pagesRotation, 90);
  assert.equal(h.app.initialBookmark, 'page=3&zoom=125,10,20');
  assert.equal(h.app.initialRotation, 90);
  assert.equal(h.window.paraRead.opened, true);
  for (const type of ['translateCurrentPage', 'translateAll', 'retryFailed', 'settings']) await h.deliver({ type, targetLanguage: 'de', selectOnHover: true });
  assert.equal(h.calls.includes('TranslationClient'), false);
  await h.deliver({ type: 'getData', requestId: 7 });
  assert.ok(h.calls.includes('saveHandle:getData'));
  await h.deliver({ type: 'readerFullscreen', enabled: false, showControlsLabel: 'Show controls' });
  assert.ok(h.calls.includes('fullscreen:false'));
  // Real PDF.js rotation/scale setters update visible-page ownership during layout.
  // Restoring navigation before these setters loses the non-default page on reload.
  for (const key of ['currentScaleValue', 'pagesRotation']) {
    let value = h.viewer[key];
    Object.defineProperty(h.viewer, key, { get() { return value; }, set(next) { value = next; this.currentPageNumber = 1; } });
  }
  await h.deliver({ type: 'reload' });
  assert.equal(h.calls.filter(c => c === 'open').length, 2);
  assert.equal(h.viewer.currentPageNumber, 3);
  assert.equal(h.viewer.currentScaleValue, '1.25');
  assert.equal(h.viewer.pagesRotation, 90);
  for (const fn of h.events.get('pagechanging')) fn({ pageNumber: 4 });
  for (const fn of h.events.get('updateviewarea')) fn({ location: { pageNumber: 4, rotation: 90, pdfOpenParams: '#page=4&zoom=125' } });
  h.timers.forEach(fn => fn());
  const saves = h.posted.filter(m => m.type === 'reading.save');
  assert.ok(saves.some(m => m.payload.page === 4 && m.payload.location === 'page=4&zoom=125'));
  assert.ok(saves.every(m => !('selectedId' in m.payload) && !('selectedSource' in m.payload)), 'preserve saved bilingual selection');
});

for (const readingOnly of [undefined, false]) test(`main.ts normal PDF pipeline and selection restore are retained (readingOnly=${readingOnly})`, async () => {
  const h = await harness({ readingOnly, translateRange: 'manual' });
  for (const name of machinery) assert.ok(h.calls.includes(name), name);
  assert.ok(h.calls.includes('bilingualChrome'));
  assert.ok(h.calls.includes('pin:p3-b0-s0'));
  assert.ok(h.calls.includes('refresh'));
  assert.equal(h.context.clientOptions.readingOnly, false);
  assert.equal(h.context.clientOptions.range, 'manual');
  assert.equal(typeof h.context.clientOptions.restoreCached, 'function');
  assert.equal(h.events.has('textlayerrendered'), true);
});

for (const readingOnly of [true, false]) test(`PDF reading flusher snapshots live state without clearing bilingual history (readingOnly=${readingOnly})`, async () => {
  const h = await harness({ readingOnly });
  const getter = h.window.paraRead.flushReadingState;
  assert.equal(typeof getter, 'function');
  const savedBefore = h.posted.filter(m => m.type === 'reading.save').length;
  for (const fn of h.events.get('updateviewarea')) fn({ location: { pageNumber: 2, rotation: 0, pdfOpenParams: '#page=2&zoom=100' } });
  h.viewer.currentPageNumber = 5;
  h.viewer.currentScaleValue = 1.75;
  h.viewer.pagesRotation = 270;
  const validLocation = { pageNumber: 5, scale: 175, rotation: 270, pdfOpenParams: '#page=5&zoom=175,10,20' };
  h.viewer._location = { ...validLocation, pageNumber: 2 };
  let settled = false;
  h.context.requestAnimationFrame = fn => { settled = true; h.viewer._location = validLocation; fn(); };
  assert.deepEqual(JSON.parse(JSON.stringify(await getter())), { page: 5, zoom: '1.75', rotation: 270, location: 'page=5&zoom=175,10,20' });
  assert.equal(settled, true, 'wait for the PDF.js frame before capturing location');
  h.context.requestAnimationFrame = fn => fn();
  h.timers.forEach(fn => fn());
  assert.equal(h.posted.filter(m => m.type === 'reading.save').length, savedBefore, 'flush cancels stale debounced writes');
  for (const location of [undefined, {}, { ...validLocation, pdfOpenParams: '' },
    { ...validLocation, pageNumber: 2 }, { ...validLocation, rotation: 90 },
    { ...validLocation, scale: 125 }, { ...validLocation, scale: 'page-fit' }]) {
    h.viewer._location = location;
    const snapshot = await getter();
    assert.equal(snapshot.location, '', 'unavailable or stale location must explicitly clear the old Room bookmark');
    assert.equal('selectedId' in snapshot, false);
    assert.equal('selectedSource' in snapshot, false);
  }
  h.viewer.currentScaleValue = 'page-width';
  h.viewer._location = { ...validLocation, scale: 'page-width', pdfOpenParams: '#page=5&zoom=page-width,10,20' };
  assert.equal((await getter()).location, 'page=5&zoom=page-width,10,20', 'named zooms match without percentage conversion');
});

test('background PDF startup remains ahead of foreground read-only branching', async () => {
  const h = await harness({ backgroundTranslation: true, readingOnly: true });
  assert.deepEqual(h.calls, ['background']);
  assert.equal(h.window.paraRead.opened, undefined);
});
