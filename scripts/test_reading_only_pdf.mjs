// Real PDF.js/browser proof. Reuses generated PDF.js assets, but bundles current web sources in memory.
// --generated tests the packaged main.mjs instead; run build:reader first. Does not overwrite historical reports.
import { chromium } from '@playwright/test';
import { build } from 'esbuild';
import { createServer } from 'node:http';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, extname, sep } from 'node:path';
import assert from 'node:assert/strict';

const project = resolve(import.meta.dirname, '..');
const root = resolve(project, 'app/build/generated/readerAssets/reader');
const reportDirectory = process.env.PARAREAD_REPORT_DIR ? resolve(process.env.PARAREAD_REPORT_DIR) : resolve(project, 'docs/reports');
const reportPath = resolve(reportDirectory, 'reading-only-pdf-browser.json');
const channel = process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined);
const useGenerated = process.argv.includes('--generated');
const sourceSentences = ['Original first page sentence.', 'Original second page sentence.'];
// Original ASCII fixture, generated in memory: no third-party PDF or download.
function smallPdf() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>'];
  for (const [index, text] of sourceSentences.entries()) {
    const stream = `BT /F1 12 Tf 70 700 Td (${text}) Tj ET`;
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents ${4 + index * 2} 0 R >>`,
      `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  }
  objects.push('<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>');
  let pdf = '%PDF-1.4\n'; const offsets = [0];
  objects.forEach((object, index) => { offsets.push(pdf.length); pdf += `${index + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = pdf.length;
  pdf += `xref\n0 ${offsets.length}\n0000000000 65535 f \n`;
  pdf += offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
  return Buffer.from(pdf + `trailer\n<< /Root 1 0 R /Size ${offsets.length} >>\nstartxref\n${xref}\n%%EOF\n`, 'ascii');
}
const sample = smallPdf();
const report = { status: 'running', timestamp: new Date().toISOString(), channel,
  scope: 'Actual desktop Edge/Chromium and PDF.js, current source bundle, simulated Native bridge/save/storage. NOT Android/device or model inference.',
  fixture: { provenance: 'Original two-page ASCII PDF generated in memory', pages: 2, bytes: sample.length },
  generatedAssets: { reusedWithoutRebuild: true, sourceBundledInMemory: !useGenerated, generatedMainUsed: useGenerated } };
const source = useGenerated ? { outputFiles: [{ text: await readFile(`${root}/main.mjs`, 'utf8') }] } : await build({ entryPoints: [`${project}/web/reader/src/entry.ts`], bundle: true, write: false,
  platform: 'browser', format: 'esm', target: 'es2022', plugins: [{ name: 'pdfjs-external', setup(b) {
    b.onResolve({ filter: /\/viewer\.mjs$/ }, () => ({ path: './pdfjs/web/viewer.mjs', external: true }));
  } }],
});
report.sourceBundleSha256 = createHash('sha256').update(source.outputFiles[0].text).digest('hex');
let rangeRequests = 0;
const errors = [];
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/favicon.ico') { res.writeHead(204).end(); return; }
    if (url.pathname === '/fixture.pdf') { res.setHeader('Content-Type', 'application/pdf'); res.end(sample); return; }
    if (url.pathname.startsWith('/documents/')) {
      const begin = Number(url.searchParams.get('begin')), end = Number(url.searchParams.get('end'));
      assert.ok(begin >= 0 && end <= sample.length && end - begin <= 262144);
      rangeRequests++;
      res.setHeader('Content-Type', 'application/octet-stream'); res.end(sample.subarray(begin, end)); return;
    }
    const file = resolve(root, `.${url.pathname === '/' ? '/index.html' : url.pathname}`);
    if (!file.startsWith(root + sep)) { res.writeHead(403).end(); return; }
    const type = { '.mjs': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.json': 'application/json', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.ftl': 'text/plain' }[extname(file)];
    if (type) res.setHeader('Content-Type', type);
    res.end(url.pathname === '/main.mjs' ? source.outputFiles[0].text :
      !useGenerated && url.pathname === '/android.css' ? await readFile(`${project}/web/reader/android.css`) : await readFile(file));
  } catch (error) { res.writeHead(404).end(String(error)); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser;
try {
  assert.ok(sample.length < 1500);
  browser = await chromium.launch({ headless: true, channel });
  report.browserVersion = browser.version();
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, hasTouch: true });
  page.setDefaultTimeout(15000);
  page.on('pageerror', error => errors.push(String(error)));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.addInitScript(({ length }) => {
    const readingOnly = new URL(location.href).searchParams.get('mode') !== 'normal';
    window.__nativeMessages = []; window.__saveChunks = []; window.__flushEvents = []; window.__failSave = false;
    const session = { version: 1, sessionId: 'pdf-reading-test', documentId: 'guide', documentRevision: 'test', configRevision: 1, viewportEpoch: 0 };
    const send = (m, type, payload) => setTimeout(() => {
      window.__flushEvents.push({ direction: 'host', type, requestId: m.requestId, payload });
      window.ParaReadNative.onmessage({ data: JSON.stringify({ ...session, ...m, type, payload }) });
    }, 0);
    window.ParaReadNative = { postMessage(raw) {
      const m = JSON.parse(raw); window.__nativeMessages.push(m);
      window.__flushEvents.push({ direction: 'web', type: m.type, requestId: m.requestId, payload: m.payload });
      if (m.type === 'reader.ready') send(m, 'session.open', { length, token: 'test', debug: true,
        reading: readingOnly ? { page: 2, zoom: '1.25', rotation: 90, location: 'page=2&zoom=125,0,0', selectedId: 'p2-b0-s0', selectedSource: 'panel', panelState: { open: true } } : {},
        settings: { readingOnly, targetLanguage: 'zh-CN', translateRange: 'page', selectOnHover: true, readerFullscreen: true } });
      if (m.type === 'page.cacheGet') send(m, 'page.cached', { page: null });
      if (m.type === 'page.textChunk' && m.payload.chunkIndex === m.payload.chunkCount - 1) send(m, 'page.saved', { page: m.payload.page });
      if (m.type === 'translation.cacheGet') send(m, 'translation.finished', { failures: [], cancelled: false });
      if (m.type === 'translation.request') {
        send(m, 'translation.committed', { sequence: 0, items: m.payload.sentences.map(s => ({ id: s.id, translation: '【模拟译文】' + s.text })) });
        send(m, 'translation.finished', { failures: [], cancelled: false });
      }
      if (m.type === 'reading.save') send(m, 'reading.saved', {});
      if (m.type === 'document.save' && 'incrementalLength' in m.payload) send(m, 'save.started', { saveId: 'pdf-save' });
      if (m.type === 'save.chunk') { window.__saveChunks.push(m.payload.data); send(m, 'save.accepted', {}); }
      if (m.type === 'save.end') send(m, window.__failSave ? 'protocol.error' : 'save.finished', window.__failSave ? { code: 'TEST_SAVE_FAILED' } : {});
    } };
  }, { length: sample.length });
  const base = `http://127.0.0.1:${server.address().port}`;
  const command = async payload => page.evaluate(payload => {
    const m = window.__nativeMessages.find(m => m.type === 'session.opened');
    window.ParaReadNative.onmessage({ data: JSON.stringify({ ...m, type: 'reader.command', payload }) });
  }, payload);
  await page.goto(base);
  await page.waitForFunction(() => window.paraRead?.opened, undefined, { timeout: 45000 });
  await page.waitForFunction(() => document.querySelector('.page[data-page-number="2"] canvas'));
  await page.waitForFunction(expected => document.querySelector('.page[data-page-number="2"] .textLayer')?.textContent.includes(expected), sourceSentences[1]);
  assert.deepEqual(await page.evaluate(() => ({ page: PDFViewerApplication.pdfViewer.currentPageNumber,
    zoom: PDFViewerApplication.pdfViewer.currentScaleValue, rotation: PDFViewerApplication.pdfViewer.pagesRotation })), { page: 2, zoom: '1.25', rotation: 90 });
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer._location?.pdfOpenParams.includes('page=2&zoom=125'));
  assert.equal(await page.locator('.bilingualPanel, .bilingualToggle, .bilingualHighlightLayer, .bilingualConnector').count(), 0);
  assert.equal(await page.evaluate(() => window.__bilingual === undefined), true);
  assert.ok(await page.evaluate(() => document.querySelector('#outerContainer').getBoundingClientRect().width >= innerWidth - 1));
  const text = page.locator('.page[data-page-number="2"] .textLayer span').first();
  await text.hover(); await page.waitForTimeout(400); await text.click();
  for (const type of ['translateCurrentPage', 'translateAll', 'retryFailed']) await command({ type, jobId: 7 });
  await command({ type: 'settings', selectOnHover: true, translateRange: 'nearby', targetLanguage: 'de' });
  await page.locator('#pageNumber').fill('1'); await page.locator('#pageNumber').press('Enter');
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer.currentPageNumber === 1);
  // updateviewarea/bookmark follows navigation asynchronously, not the input event itself.
  await page.waitForFunction(() => document.querySelector('#viewBookmark').getAttribute('href')?.includes('page=1'));
  await page.locator('#scaleSelect').selectOption('1');
  await page.locator('#secondaryToolbarToggleButton').click();
  await page.locator('#pageRotateCw').click();
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer.pagesRotation === 180);
  await page.locator('#secondaryToolbarToggleButton').click();
  await page.evaluate(() => { document.activeElement?.blur(); window.getSelection()?.removeAllRanges(); });
  await page.locator('#viewerContainer').hover(); await page.mouse.wheel(0, 100);
  await page.waitForFunction(() => document.body.classList.contains('readerChromeHidden'));
  assert.equal(await page.locator('#toolbarContainer').isVisible(), false);
  assert.equal(await page.locator('#readerShowControls').count(), 0, 'No downward-arrow overlay after collapse');
  await page.mouse.wheel(0, -100);
  await page.waitForFunction(() => !document.body.classList.contains('readerChromeHidden'));
  assert.equal(await page.locator('#toolbarContainer').isVisible(), true);
  for (const key of ['Tab', 'Escape']) {
    await page.evaluate(() => {
      document.activeElement?.blur();
      document.querySelector('#viewerContainer').scrollTop = 100;
    });
    await page.locator('#viewerContainer').hover(); await page.mouse.wheel(0, 100);
    await page.waitForFunction(() => document.body.classList.contains('readerChromeHidden'));
    assert.equal(await page.locator('#toolbarContainer').evaluate(el => el.inert), true);
    await page.keyboard.press(key);
    assert.equal(await page.locator('#toolbarContainer').isVisible(), true, `${key} restores PDF controls`);
    assert.equal(await page.locator('#toolbarContainer').evaluate(el => el.inert), false);
    if (key === 'Tab') {
      for (let i = 0; i < 80 && !await page.locator('#toolbarContainer').evaluate(el => el.contains(document.activeElement)); i++) {
        await page.keyboard.press('Shift+Tab');
      }
      assert.equal(await page.locator('#toolbarContainer').evaluate(el => el.contains(document.activeElement)), true,
        'Restored toolbar remains reachable by keyboard');
    }
  }
  await page.locator('#editorFreeTextButton').click();
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer.annotationEditorMode > 0);
  await page.locator('#viewerContainer').hover(); await page.mouse.wheel(0, 100);
  assert.equal(await page.evaluate(() => document.body.classList.contains('readerChromeHidden')), false);
  await page.locator('#editorFreeTextButton').click();
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer.annotationEditorMode === 0);
  const saved = await page.evaluate(async () => {
    const app = PDFViewerApplication;
    app.pdfDocument.annotationStorage.setValue('pdfjs_internal_editor_900', { annotationType: 3, pageIndex: 0, rect: [70, 660, 230, 690], rotation: 0, color: [0, 0, 0], fontSize: 12, value: 'Read-only view annotation' });
    app.downloadManager.download(); // SaveBridge must still forward native toolbar/export saves.
    await window.paraRead.save();
    const original = new Uint8Array(await fetch('/fixture.pdf').then(r => r.arrayBuffer()));
    const suffix = Uint8Array.from(window.__saveChunks.map(s => atob(s)).join(''), c => c.charCodeAt(0));
    const combined = new Uint8Array(original.length + suffix.length); combined.set(original); combined.set(suffix, original.length);
    const reopened = await globalThis.pdfjsLib.getDocument({ data: combined }).promise;
    return { suffixLength: suffix.length, values: JSON.stringify(await (await reopened.getPage(1)).getAnnotations()) };
  });
  assert.ok(saved.suffixLength > 0 && saved.suffixLength < 65536, 'bounded incremental suffix; a tiny original can be smaller than its annotations');
  assert.ok(saved.values.includes('Read-only view annotation'));
  await page.evaluate(() => { PDFViewerApplication.pdfViewer.currentPageNumber = 2; });
  await page.waitForFunction(() => PDFViewerApplication.pdfViewer.currentPageNumber === 2 && PDFViewerApplication.pdfViewer._location?.pageNumber === 2);
  const beforeReload = await page.evaluate(() => ({ page: PDFViewerApplication.pdfViewer.currentPageNumber,
    zoom: PDFViewerApplication.pdfViewer.currentScaleValue, rotation: PDFViewerApplication.pdfViewer.pagesRotation }));
  await command({ type: 'reload' });
  await page.waitForTimeout(500);
  assert.deepEqual(await page.evaluate(() => ({ page: PDFViewerApplication.pdfViewer.currentPageNumber,
    zoom: PDFViewerApplication.pdfViewer.currentScaleValue, rotation: PDFViewerApplication.pdfViewer.pagesRotation })), beforeReload, 'save/reload preserves PDF view');
  await command({ type: 'readerFullscreen', enabled: false, showControlsLabel: 'Show original PDF controls' });
  await page.waitForFunction(() => !document.body.classList.contains('readerFullscreen'));
  await page.waitForTimeout(350);
  const messages = await page.evaluate(() => window.__nativeMessages);
  const forbiddenMessages = messages.filter(m => /^(translation\.|page\.cacheGet|page\.textChunk|job\.)/.test(m.type));
  assert.deepEqual(forbiddenMessages, [], 'no inference, translation caches, page extraction, segmentation or job requests');
  assert.equal(await page.locator('.bilingualPanel, .bilingualToggle, .bilingualHighlightLayer, .bilingualConnector').count(), 0, 'click/hover/settings must not mount translation UI');
  const history = messages.filter(m => m.type === 'reading.save');
  assert.ok(history.some(m => m.payload.page === 1));
  assert.ok(history.every(m => !('selectedId' in m.payload) && !('selectedSource' in m.payload) && !('panelState' in m.payload)));
  assert.ok(messages.some(m => m.type === 'annotation.changed'));
  assert.ok(messages.some(m => m.type === 'document.save' && !('incrementalLength' in m.payload)));
  assert.equal(messages.some(m => m.type === 'session.error'), false, JSON.stringify(messages.filter(m => m.type === 'session.error')));
  // Flush in the same JS turn as navigation/zoom and a pending annotation, before either debounce.
  const beginModeFlush = async (flushId, failSave) => page.evaluate(({ flushId, failSave }) => {
    const app = PDFViewerApplication;
    window.__flushEvents = []; window.__saveChunks = []; window.__failSave = failSave; window.__flushDocument = app.pdfDocument;
    app.pdfDocument.annotationStorage.setValue('pdfjs_internal_editor_901', { annotationType: 3, pageIndex: 0,
      rect: [70, 620, 250, 650], rotation: 0, color: [0, 0, 0], fontSize: 12, value: 'Pending mode switch annotation ' + flushId });
    app.pdfViewer.currentScaleValue = failSave ? '0.75' : '1.5';
    app.pdfViewer.currentPageNumber = failSave ? 2 : 1;
    const expected = { page: app.pdfViewer.currentPageNumber, zoom: String(app.pdfViewer.currentScaleValue), rotation: app.pdfViewer.pagesRotation };
    const m = window.__nativeMessages.find(m => m.type === 'session.opened');
    window.ParaReadNative.onmessage({ data: JSON.stringify({ ...m, type: 'reader.command', payload: { type: 'flushBeforeModeSwitch', flushId } }) });
    return expected;
  }, { flushId, failSave });
  const finishModeFlush = async flushId => {
    await page.waitForFunction(id => window.__nativeMessages.some(m => m.type === 'reader.flushed' && m.payload.flushId === id), flushId);
    return page.evaluate(flushId => ({
      result: window.__nativeMessages.find(m => m.type === 'reader.flushed' && m.payload.flushId === flushId).payload,
      events: window.__flushEvents, inert: document.body.inert,
      sameDocument: PDFViewerApplication.pdfDocument === window.__flushDocument,
    }), flushId);
  };
  const assertLiveSnapshot = (snapshot, expected) => {
    for (const [key, value] of Object.entries(expected)) assert.equal(snapshot[key], value, `live ${key}`);
    assert.equal(typeof snapshot.location, 'string', 'always replace the old Room location, even when clearing it');
    if (snapshot.location) {
      const params = new URLSearchParams(snapshot.location);
      assert.equal(Number(params.get('page')), snapshot.page, 'location must not restore the previous page');
      const zoom = Number(snapshot.zoom);
      const expectedZoom = Number.isFinite(zoom) && zoom > 0 ? String(Math.round(zoom * 10000) / 100) : snapshot.zoom;
      assert.equal(params.get('zoom')?.split(',')[0], expectedZoom, 'location must not restore the previous zoom');
    }
    assert.equal('selectedId' in snapshot, false);
  };
  const expectedFlush = await beginModeFlush('pdf-flush-success', false);
  const flushed = await finishModeFlush('pdf-flush-success');
  assert.equal(flushed.result.success, true);
  assert.equal(flushed.inert, true, 'successful drain freezes input until native reopens/resumes');
  assert.equal(flushed.sameDocument, true);
  const snapshotEvent = flushed.events.find(e => e.direction === 'web' && e.type === 'reading.save' && 'zoom' in e.payload);
  assert.ok(snapshotEvent, 'flush must request a durable reading acknowledgement');
  assertLiveSnapshot(snapshotEvent.payload, expectedFlush);
  const readingAck = flushed.events.findIndex(e => e.direction === 'host' && e.type === 'reading.saved' && e.requestId === snapshotEvent.requestId);
  const saveRequest = flushed.events.findIndex(e => e.direction === 'web' && e.type === 'document.save' && 'incrementalLength' in e.payload);
  const saveAck = flushed.events.findIndex(e => e.direction === 'host' && e.type === 'save.finished');
  const successAck = flushed.events.findIndex(e => e.direction === 'web' && e.type === 'reader.flushed');
  assert.ok(readingAck >= 0 && readingAck < saveRequest && saveRequest < saveAck && saveAck < successAck,
    'reading.saved -> annotation save -> save.finished -> reader.flushed success');
  const flushedAnnotations = await page.evaluate(async () => {
    const original = new Uint8Array(await fetch('/fixture.pdf').then(r => r.arrayBuffer()));
    const suffix = Uint8Array.from(window.__saveChunks.map(s => atob(s)).join(''), c => c.charCodeAt(0));
    const combined = new Uint8Array(original.length + suffix.length); combined.set(original); combined.set(suffix, original.length);
    const reopened = await globalThis.pdfjsLib.getDocument({ data: combined }).promise;
    return JSON.stringify(await (await reopened.getPage(1)).getAnnotations());
  });
  assert.ok(flushedAnnotations.includes('Pending mode switch annotation pdf-flush-success'), 'flush commits the pending annotation bytes');
  await command({ type: 'resumeAfterModeFlush', flushId: 'pdf-flush-success' });
  await page.waitForFunction(() => !document.body.inert);
  const expectedFailedFlush = await beginModeFlush('pdf-flush-failure', true);
  const failedFlush = await finishModeFlush('pdf-flush-failure');
  assertLiveSnapshot(failedFlush.events.find(e => e.direction === 'web' && e.type === 'reading.save' && 'zoom' in e.payload).payload, expectedFailedFlush);
  assert.equal(failedFlush.result.success, false);
  assert.match(failedFlush.result.message, /TEST_SAVE_FAILED/, 'failure acknowledgement must come from the injected save failure');
  assert.equal(failedFlush.sameDocument, true, 'save failure must retain the old PDF/session');
  assert.equal(failedFlush.inert, false, 'save failure restores input');
  assert.equal(failedFlush.events.some(e => e.type === 'reader.flushed' && e.payload.success === true), false);
  assert.equal(failedFlush.events.some(e => e.direction === 'host' && e.type === 'save.finished'), false);
  await command({ type: 'resumeAfterModeFlush', flushId: 'pdf-flush-failure' });
  await page.evaluate(async () => { window.__failSave = false; await window.paraRead.save(); });
  assert.equal(await page.evaluate(() => document.body.inert || PDFViewerApplication.pdfDocument !== window.__flushDocument), false,
    'the retained document can save again after resume');
  report.modeSwitchFlush = { immediateLiveSnapshot: true, locationMatchesLivePageAndZoomOrExplicitlyClears: true,
    snapshot: snapshotEvent.payload, readingAcknowledgedBeforeSave: true,
    annotationSaveAcknowledgedBeforeSuccess: true, freezesOnlyUntilReopenOrResume: true,
    saveFailureRetainsDocumentAndInput: true, resumeCanSaveAgain: true,
    successEvents: flushed.events.map(e => `${e.direction}:${e.type}`), failureEvents: failedFlush.events.map(e => `${e.direction}:${e.type}`) };
  // The very same PDF.js/assets + current bundle must still mount and translate in normal mode.
  await page.goto(`${base}/?mode=normal`);
  await page.waitForFunction(() => window.paraRead?.opened && window.__bilingual?.store.readyPages().length > 0);
  await page.waitForFunction(() => document.querySelector('.bilingualSentence')?.textContent.includes('模拟译文'));
  assert.equal(await page.locator('.bilingualPanel').count(), 1);
  assert.equal(await page.locator('.bilingualToggle').count(), 1);
  assert.ok(await page.evaluate(() => window.__nativeMessages.some(m => m.type === 'translation.request')));
  assert.deepEqual(errors.filter(e => !e.includes('404')), []);
  Object.assign(report, { status: 'passed', originalOnly: true, noPanelOrToggle: true,
    noInferenceCacheSegmentationJobMessages: true, forbiddenMessages, preservedSelectionHistory: true,
    restoredPageZoomRotationLocation: true, nativeNavigationRotationBookmark: true, fullscreenGestureAndKeyboardRecoveryWithoutOverlay: true, annotationProtection: true,
    saveReloadPreservesView: true, incrementalAnnotationSaveBytes: saved.suffixLength, normalPdfStillTranslates: true,
    readOnlyMessageCounts: Object.fromEntries([...new Set(messages.map(m => m.type))].map(type => [type, messages.filter(m => m.type === type).length])), rangeRequests });
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  Object.assign(report, { status: 'failed', error: String(error) });
  throw error;
} finally {
  report.errors = errors;
  await mkdir(reportDirectory, { recursive: true });
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}
