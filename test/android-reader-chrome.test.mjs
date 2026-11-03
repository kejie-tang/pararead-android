import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const sourcePath = new URL('../web/reader/src/reader-chrome.ts', import.meta.url);
const built = await build({ entryPoints: [fileURLToPath(sourcePath)], bundle: true, format: 'cjs', write: false });
function harness({ panel = true } = {}) {
  const target = () => {
    const listeners = new Map();
    return { addEventListener(type, listener) {
      const list = listeners.get(type) ?? []; list.push(listener); listeners.set(type, list);
    }, emit(type, event = {}) { for (const listener of listeners.get(type) ?? []) listener(event); } };
  };
  const classes = new Set();
  const classList = { contains: name => classes.has(name), toggle(name, enabled) {
    if (enabled) classes.add(name); else classes.delete(name);
  } };
  const bars = [{ inert: false }, { inert: false }];
  const viewer = Object.assign(target(), { scrollTop: 100, scrollHeight: 2000, clientHeight: 800 });
  const body = Object.assign(target(), { scrollTop: 100, scrollHeight: 2000, clientHeight: 800 });
  const selection = { current: null, onChange(listener) { this.changed = listener; } };
  const nativeSelection = { isCollapsed: true };
  const document = Object.assign(target(), {
    body: { classList }, activeElement: null, blocker: null,
    querySelectorAll: () => bars,
    querySelector() { return this.blocker; },
    createElement() { throw new Error('ReaderChrome must not create an overlay'); },
  });
  const window = Object.assign(target(), { getSelection: () => nativeSelection });
  const handlers = new Map();
  const app = { pdfViewer: { container: viewer, annotationEditorMode: 0 },
    eventBus: { on(type, listener) { handlers.set(type, listener); } } };
  let now = 1000;
  const context = { module: { exports: {} }, document, window, performance: { now: () => now } };
  vm.runInNewContext(built.outputFiles[0].text, context);
  const chrome = new context.module.exports.ReaderChrome(app, panel ? { body } : null, selection);
  const hidden = () => classes.has('readerChromeHidden');
  const wheel = (deltaY, container = viewer, extra = {}) => container.emit('wheel', { deltaY, deltaX: 0, deltaMode: 0, ...extra });
  const key = key => { viewer.emit('keydown', { key }); document.emit('keydown', { key }); };
  return { chrome, hidden, wheel, key, viewer, body, bars, document, window, classes,
    selection, nativeSelection, app, handlers, advance: ms => { now += ms; } };
}

test('reader-chrome hides only after deliberate fullscreen input, not synchronized scroll', () => {
  const h = harness();
  h.wheel(100); assert.equal(h.hidden(), false);
  h.chrome.setFullscreen(true);
  h.viewer.scrollTop = 300; h.viewer.emit('scroll'); h.body.emit('scroll');
  assert.equal(h.hidden(), false);
  h.wheel(20); assert.equal(h.hidden(), false);
  h.advance(301); h.wheel(20); assert.equal(h.hidden(), false, 'stale gestures do not accumulate');
  h.wheel(20); assert.equal(h.hidden(), true);
  assert.ok(h.bars.every(bar => bar.inert));
  h.chrome.setFullscreen(false);
  assert.equal(h.hidden(), false); assert.ok(h.bars.every(bar => !bar.inert));
});

for (const panel of [false, true]) test(`reader-chrome restores with upward input and Tab/Escape without an overlay (panel: ${panel})`, () => {
  const h = harness({ panel }); h.chrome.setFullscreen(true);
  for (const recover of [() => h.wheel(-16), () => h.key('ArrowUp'), () => h.key('Tab'), () => h.key('Escape')]) {
    h.wheel(80); assert.equal(h.hidden(), true);
    recover(); assert.equal(h.hidden(), false);
    assert.ok(h.bars.every(bar => !bar.inert), 'restored controls return to keyboard navigation');
  }
  if (panel) { h.wheel(80, h.body); assert.equal(h.hidden(), true); h.wheel(-20, h.body); assert.equal(h.hidden(), false); }
});

test('reader-chrome touch gestures collapse and restore; horizontal and multi-touch input do not hide', () => {
  const h = harness(); h.chrome.setFullscreen(true);
  const touch = (type, x, y, count = 1) => h.viewer.emit(type, { touches: Array.from({ length: count }, () => ({ clientX: x, clientY: y })) });
  touch('touchstart', 100, 300); touch('touchmove', 200, 295); assert.equal(h.hidden(), false);
  touch('touchstart', 100, 300, 2); touch('touchmove', 100, 200, 2); assert.equal(h.hidden(), false);
  touch('touchstart', 100, 300); touch('touchmove', 100, 240); assert.equal(h.hidden(), true);
  touch('touchmove', 100, 270); assert.equal(h.hidden(), false);
  touch('touchend', 100, 270, 0); touch('touchmove', 100, 100); assert.equal(h.hidden(), false);
});

test('reader-chrome ignores horizontal, zoom, short-page and bottom-boundary hide attempts', () => {
  const h = harness(); h.chrome.setFullscreen(true);
  h.wheel(100, h.viewer, { deltaX: 200 }); assert.equal(h.hidden(), false);
  h.wheel(100, h.viewer, { ctrlKey: true }); assert.equal(h.hidden(), false);
  h.wheel(100, h.viewer, { metaKey: true }); assert.equal(h.hidden(), false);
  h.viewer.scrollTop = 1200; h.wheel(100); assert.equal(h.hidden(), false);
  h.viewer.scrollHeight = 800; h.viewer.scrollTop = 0; h.wheel(100); assert.equal(h.hidden(), false);
});

const guards = {
  annotation: h => { h.app.pdfViewer.annotationEditorMode = 3; },
  pinnedSelection: h => { h.selection.current = { pinned: true }; },
  nativeSelection: h => { h.nativeSelection.isCollapsed = false; },
  resize: h => { h.classes.add('bilingualResizing'); },
  input: h => { h.document.activeElement = { matches: () => true }; },
  dialogOrSidebarOrFindbarOrSecondaryToolbar: h => { h.document.blocker = {}; },
};
for (const [name, block] of Object.entries(guards)) test(`reader-chrome never hides while blocked by ${name}`, () => {
  const h = harness(); h.chrome.setFullscreen(true); block(h);
  h.wheel(100); h.key('PageDown'); assert.equal(h.hidden(), false);
});

test('reader-chrome safety events and viewport resize immediately restore inert controls', () => {
  for (const restore of [
    h => h.selection.changed({ pinned: true }),
    h => h.handlers.get('annotationeditormodechanged')({ mode: 3 }),
    h => { h.nativeSelection.isCollapsed = false; h.document.emit('selectionchange'); },
    h => { guards.input(h); h.document.emit('focusin'); },
    h => h.window.emit('resize'),
  ]) {
    const h = harness(); h.chrome.setFullscreen(true); h.wheel(80); assert.equal(h.hidden(), true);
    restore(h); assert.equal(h.hidden(), false); assert.ok(h.bars.every(bar => !bar.inert));
  }
});

test('reader-chrome source contract removes the reveal overlay without changing PDF.js sizing or unobtrusive errors', async () => {
  const source = await readFile(sourcePath, 'utf8');
  const css = await readFile(new URL('../web/reader/android.css', import.meta.url), 'utf8');
  assert.doesNotMatch(source + css, /readerShowControls|#reveal|setLabel/);
  assert.doesNotMatch(source, /\.style\.|setProperty|currentScale|pagesRotation/);
  assert.match(source, /active\?\.matches\("input, textarea, select, \[contenteditable=true\]"\)/);
  assert.match(source, /dialog\[open\], #outerContainer\.sidebarOpen, #findbar:not\(\.hidden\), #secondaryToolbar:not\(\.hidden\)/);
  assert.doesNotMatch(css, /--toolbar-height\s*:/);
  assert.match(css, /body\.readerChromeHidden #toolbarContainer \{ visibility: hidden; pointer-events: none; \}/);
  assert.match(css, /body\.readerChromeHidden #viewerContainer \{ inset-block-start: 0; \}/);
  assert.match(css, /body\.readerChromeHidden \.bilingualHeader \{ display: none; \}/);
  assert.match(css, /\.bilingualSentence\[data-status="error"\]\s*\{\s*color: var\(--vscode-descriptionForeground, GrayText\);\s*text-decoration: none;/);
});
