import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
import { chromium } from '@playwright/test';

const assets = resolve(import.meta.dirname, '../app/build/generated/readerAssets/reader');
const reportPath = resolve(import.meta.dirname, '../docs/reports/alpha16-background-browser.json');
const channel = process.env.PLAYWRIGHT_CHANNEL || 'msedge';

// Original, in-memory ASCII fixture: two pages, one sentence each, no downloads.
function smallPdf() {
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>'];
  for (const [index, text] of ['First page sentence.', 'Second page sentence.'].entries()) {
    const stream = `BT /F1 12 Tf 20 100 Td (${text}) Tj ET`;
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 7 0 R >> >> /Contents ${4 + index * 2} 0 R >>`,
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

const pdf = smallPdf(), ranges = [], requests = [], workerUrls = [], errors = [], consoleWarnings = [];
const report = {
  status: 'running', timestamp: new Date().toISOString(),
  scope: 'Real desktop Edge/Chromium and actual PDF.js; simulated Native bridge, durable storage and translations. NOT an Android Worker or device test; no model inference.',
  fixture: { provenance: 'Original two-page PDF generated in memory by this script', pages: 2, bytes: pdf.length },
  generatedReaderAssets: { path: assets, reusedReadOnly: true, rebuilt: false },
  channel, node: process.version,
};
let browser, page;
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost'); requests.push(url.pathname);
  try {
    if (url.pathname === '/favicon.ico') { res.writeHead(204).end(); return; }
    if (url.pathname === '/documents/background-test/ranges') {
      const begin = Number(url.searchParams.get('begin')), end = Number(url.searchParams.get('end'));
      assert.ok(Number.isInteger(begin) && Number.isInteger(end) && begin >= 0 && end > begin && end <= pdf.length && end - begin <= 262144);
      ranges.push([begin, end]); res.setHeader('Content-Type', 'application/octet-stream');
      res.end(pdf.subarray(begin, end)); return;
    }
    const file = resolve(assets, `.${url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)}`);
    if (!file.startsWith(assets + sep)) { res.writeHead(403).end(); return; }
    const type = { '.html': 'text/html', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
      '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.ftl': 'text/plain' }[extname(file)];
    if (type) res.setHeader('Content-Type', type);
    res.end(await readFile(file));
  } catch (error) { errors.push(`HTTP ${url.pathname}: ${String(error)}`); res.writeHead(404).end(String(error)); }
});

try {
  assert.ok(pdf.length < 1500, 'fixture must remain approximately 1KB');
  report.generatedReaderAssets.mainSha256 = createHash('sha256').update(await readFile(resolve(assets, 'main.mjs'))).digest('hex');
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ headless: true, channel });
  report.browserVersion = browser.version();
  page = await browser.newPage({ viewport: { width: 800, height: 600 } });
  page.setDefaultTimeout(15000);
  page.on('pageerror', error => errors.push(String(error)));
  page.on('requestfailed', request => errors.push(`${request.url()}: ${request.failure()?.errorText}`));
  page.on('console', message => {
    if (message.type() === 'error') errors.push(message.text());
    if (message.type() === 'warning') consoleWarnings.push(message.text());
  });
  page.on('worker', worker => workerUrls.push(worker.url()));
  await page.addInitScript(({ length }) => {
    const prefix = 'alpha16-background:';
    const read = (key, fallback) => JSON.parse(localStorage.getItem(prefix + key) ?? JSON.stringify(fallback));
    const write = (key, value) => localStorage.setItem(prefix + key, JSON.stringify(value));
    const run = read('run', 0) + 1; write('run', run);
    const state = window.__backgroundProof = { run, cursorAtOpen: read('cursor', 0), messages: [], replies: [],
      extractedPages: [], documentLoads: [], canvasCreated: 0, destroyCalls: 0, cacheHits: [], heldPage: null, heldJob: null, finished: null };
    const session = { version: 1, sessionId: `browser-${run}`, documentId: 'original-two-page-test',
      documentRevision: 'original-two-page-v1', configRevision: 1, viewportEpoch: 0 };
    const send = (message, type, payload) => {
      state.replies.push({ type, requestId: message.requestId, payload });
      setTimeout(() => window.ParaReadNative.onmessage({ data: JSON.stringify({ ...message, ...session, type, payload }) }), 0);
    };
    state.releasePageSaved = () => {
      const message = state.heldPage; if (!message) throw new Error('NO_HELD_PAGE'); state.heldPage = null;
      send(message, 'page.saved', { page: message.payload.page });
    };
    state.releaseJobSaved = () => {
      const message = state.heldJob; if (!message) throw new Error('NO_HELD_JOB'); state.heldJob = null;
      send(message, 'job.saved', { status: 'RUNNING' });
    };
    // Observe the real library assignment. All parsing and text extraction still use actual PDF.js.
    let library;
    const observedPages = new WeakSet();
    Object.defineProperty(window, 'pdfjsLib', { configurable: true, get: () => library, set(value) {
      library = value;
      const getDocument = value.getDocument.bind(value);
      value.getDocument = options => {
        state.documentLoads.push({ range: options.range instanceof value.PDFDataRangeTransport,
          hasData: options.data !== undefined, disableAutoFetch: options.disableAutoFetch, disableStream: options.disableStream });
        const task = getDocument(options), destroy = task.destroy.bind(task);
        task.destroy = (...args) => { state.destroyCalls++; return destroy(...args); };
        task.promise.then(doc => {
          const getPage = doc.getPage.bind(doc);
          doc.getPage = async number => {
            const page = await getPage(number);
            if (!observedPages.has(page)) {
              observedPages.add(page); const getTextContent = page.getTextContent.bind(page);
              page.getTextContent = (...args) => { state.extractedPages.push(number); return getTextContent(...args); };
            }
            return page;
          };
        }, error => { state.loadError = String(error); });
        return task;
      };
    } });
    const createElement = document.createElement.bind(document);
    document.createElement = (name, ...args) => {
      if (String(name).toLowerCase() === 'canvas') state.canvasCreated++;
      return createElement(name, ...args);
    };
    const chunks = new Map();
    window.ParaReadNative = { postMessage(raw) {
      const message = JSON.parse(raw), p = message.payload; state.messages.push(message);
      if (message.type === 'reader.ready') send(message, 'session.open', { length, token: 'background-test', reading: {},
        settings: { backgroundTranslation: true, translateRange: 'manual', targetLanguage: 'zh-CN' }, debug: false });
      if (message.type === 'session.opened') send(message, 'reader.command', { type: 'translateAll', jobId: 1601, startPage: state.cursorAtOpen + 1 });
      if (message.type === 'page.cacheGet') send(message, 'page.cached', { page: read(`page:${p.page}`, null) });
      if (message.type === 'page.textChunk') {
        if (!chunks.has(p.page)) chunks.set(p.page, []);
        const parts = chunks.get(p.page);
        if (p.chunkIndex !== parts.length || p.chunkCount > 64) throw new Error('INVALID_PAGE_CHUNKS');
        parts.push(p.data);
        if (parts.length === p.chunkCount) {
          const segmentation = JSON.parse(parts.join(''));
          if (segmentation.page !== p.page) throw new Error('WRONG_PAGE');
          write(`page:${p.page}`, segmentation); chunks.delete(p.page);
          state.heldPage = message; // Durable text exists, but extraction must await page.saved.
        }
      }
      if (message.type === 'translation.cacheGet') {
        const items = p.sentences.flatMap(sentence => {
          const translation = read(`translation:${sentence.text}`, null);
          if (!translation) return [];
          state.cacheHits.push(sentence.id); return [{ id: sentence.id, translation }];
        });
        if (items.length) send(message, 'translation.committed', { sequence: 0, items });
        send(message, 'translation.finished', { failures: [], cancelled: false });
      }
      if (message.type === 'translation.request') {
        const items = p.sentences.map(sentence => {
          const translation = `[SIMULATED TRANSLATION] ${sentence.text}`;
          write(`translation:${sentence.text}`, translation); return { id: sentence.id, translation };
        });
        send(message, 'translation.committed', { sequence: 0, items });
        send(message, 'translation.finished', { failures: [], cancelled: false });
      }
      if (message.type === 'job.progress') {
        if (p.done !== read('cursor', 0) + 1) throw new Error('NON_SEQUENTIAL_DURABLE_CURSOR');
        write('cursor', p.done);
        write('checkpoints', [...read('checkpoints', []), { page: p.done, failed: p.failed }]);
        state.heldJob = message; // Persist before ACK; reload must resume from this cursor.
      }
      if (message.type === 'job.finished') {
        const checkpoints = read('checkpoints', []);
        const finished = { ...p, durableCursor: read('cursor', 0), failedPages: checkpoints.filter(item => item.failed).map(item => item.page) };
        write('finished', finished); state.finished = finished;
        send(message, 'job.saved', { status: p.status });
      }
    } };
  }, { length: pdf.length });

  const snapshot = () => page.evaluate(() => {
    const state = window.__backgroundProof;
    return { run: state.run, cursorAtOpen: state.cursorAtOpen, extractedPages: state.extractedPages,
      documentLoads: state.documentLoads, hasViewer: typeof window.PDFViewerApplication !== 'undefined',
      canvasCount: document.querySelectorAll('canvas').length, canvasCreated: state.canvasCreated,
      readingSaves: state.messages.filter(m => m.type === 'reading.save').length,
      pageTextPages: state.messages.filter(m => m.type === 'page.textChunk').map(m => m.payload.page),
      translationPages: state.messages.filter(m => m.type === 'translation.request').map(m => m.payload.page),
      progress: state.messages.filter(m => m.type === 'job.progress').map(m => m.payload),
      replies: state.replies, cacheHits: state.cacheHits, finished: state.finished,
      cursor: JSON.parse(localStorage.getItem('alpha16-background:cursor') ?? '0'), destroyCalls: state.destroyCalls,
      fatal: state.messages.filter(m => m.type === 'session.error').map(m => m.payload),
      pdfjsVersion: window.pdfjsLib?.version, userAgent: navigator.userAgent };
  });
  const assertHeadless = state => {
    assert.equal(state.hasViewer, false); assert.equal(state.canvasCount, 0); assert.equal(state.canvasCreated, 0);
    assert.equal(state.readingSaves, 0); assert.deepEqual(state.fatal, []);
    assert.deepEqual(state.documentLoads, [{ range: true, hasData: false, disableAutoFetch: true, disableStream: true }]);
  };

  await page.goto(origin, { waitUntil: 'load', timeout: 30000 });
  await page.waitForFunction(() => window.__backgroundProof.heldPage?.payload.page === 1);
  const firstPageBarrier = await snapshot(); assertHeadless(firstPageBarrier);
  assert.deepEqual(firstPageBarrier.extractedPages, [1]); assert.deepEqual(firstPageBarrier.translationPages, []);
  assert.deepEqual(firstPageBarrier.progress, []);
  await page.evaluate(() => window.__backgroundProof.releasePageSaved());
  await page.waitForFunction(() => window.__backgroundProof.heldJob?.payload.done === 1);
  await page.waitForTimeout(250); // A bounded opportunity for an incorrectly unblocked next page to run.
  const heldCheckpoint = await snapshot(); assertHeadless(heldCheckpoint);
  assert.deepEqual(heldCheckpoint.extractedPages, [1]); assert.deepEqual(heldCheckpoint.translationPages, [1]);
  assert.deepEqual(heldCheckpoint.pageTextPages, [1]); assert.equal(heldCheckpoint.cursor, 1);
  assert.equal(heldCheckpoint.progress[0].failed, false);
  assert.equal(heldCheckpoint.replies.some(reply => reply.type === 'job.saved'), false);
  const cachedSource = await page.evaluate(() => {
    const savedPage = JSON.parse(localStorage.getItem('alpha16-background:page:1'));
    const sentence = savedPage.blocks.filter(block => block.kind === 'text').flatMap(block => block.sentences)[0];
    return { ...sentence, expected: JSON.parse(localStorage.getItem(`alpha16-background:translation:${sentence.text}`)) };
  });
  assert.match(cachedSource.expected, /^\[SIMULATED TRANSLATION\]/);

  // Do NOT release page 1 job.saved. The simulated durable cursor is already committed.
  await page.reload({ waitUntil: 'load', timeout: 30000 });
  await page.waitForFunction(() => window.__backgroundProof.heldPage?.payload.page === 2);
  const resumedPageBarrier = await snapshot(); assertHeadless(resumedPageBarrier);
  assert.equal(resumedPageBarrier.run, 2); assert.equal(resumedPageBarrier.cursorAtOpen, 1);
  assert.deepEqual(resumedPageBarrier.extractedPages, [2]); assert.deepEqual(resumedPageBarrier.translationPages, []);
  const restored = await page.evaluate(sentence => window.paraRead.cachedTranslations([sentence]), cachedSource);
  assert.deepEqual(restored, [{ id: cachedSource.id, translation: cachedSource.expected }]);
  assert.deepEqual((await snapshot()).translationPages, [], 'cross-reload cache reads must not invoke simulated inference');
  await page.evaluate(() => window.__backgroundProof.releasePageSaved());
  await page.waitForFunction(() => window.__backgroundProof.heldJob?.payload.done === 2);
  const secondCheckpoint = await snapshot(); assertHeadless(secondCheckpoint);
  assert.equal(secondCheckpoint.finished, null); assert.equal(secondCheckpoint.cursor, 2);
  assert.deepEqual(secondCheckpoint.translationPages, [2]); assert.equal(secondCheckpoint.progress[0].failed, false);
  await page.evaluate(() => window.__backgroundProof.releaseJobSaved());
  await page.waitForFunction(() => window.__backgroundProof.finished && window.paraRead.opened === false && window.__backgroundProof.destroyCalls === 1);
  const completed = await snapshot(); assertHeadless(completed);
  assert.deepEqual(completed.extractedPages, [2]); assert.equal(completed.finished.status, 'COMPLETED');
  assert.equal(completed.finished.durableCursor, 2); assert.deepEqual(completed.finished.failedPages, []);
  assert.ok(completed.replies.some(reply => reply.type === 'job.saved' && reply.payload.status === 'COMPLETED'));
  assert.ok(workerUrls.some(url => url.endsWith('/pdfjs/build/pdf.worker.mjs')));
  assert.equal(requests.some(path => path.endsWith('/viewer.mjs')), false);
  assert.ok(ranges.length > 0); assert.deepEqual(errors, []);
  Object.assign(report, { status: 'passed', assertions: { noViewerOrCanvasOrReadingSave: true,
    pageSavedBlocksTranslation: true, jobSavedBlocksNextPageExtraction: true,
    durableCursorPersistedBeforeHeldAck: true, reloadExtractsOnlyPage2: true,
    translationCacheSurvivesReloadWithoutInference: true, bothAckBarriersRequired: true,
    completedWithoutFailedPages: true, loadingTaskDestroyed: true, viewerModuleNeverRequested: true },
    firstPageBarrier, heldCheckpoint, resumedPageBarrier, secondCheckpoint, completed,
    cacheRestoration: { sentenceId: cachedSource.id, source: cachedSource.text, translation: restored[0].translation, simulated: true },
    workerUrls, rangeRequests: ranges, consoleWarnings, errors });
} catch (error) {
  Object.assign(report, { status: 'failed', error: String(error), stack: error.stack, errors, consoleWarnings, rangeRequests: ranges });
  if (page && !page.isClosed()) {
    try { report.diagnostics = await page.evaluate(() => ({ state: window.__backgroundProof, content: document.body?.innerText.slice(0, 300) })); }
    catch (diagnosticError) { report.diagnosticError = String(diagnosticError); }
  }
  process.exitCode = 1;
} finally {
  const cleanupErrors = [];
  try { await browser?.close(); } catch (error) { cleanupErrors.push(`browser: ${String(error)}`); }
  try {
    if (server.listening) { server.closeAllConnections(); await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())); }
  } catch (error) { cleanupErrors.push(`server: ${String(error)}`); }
  report.cleanup = { browserClosed: !browser?.isConnected(), serverClosed: !server.listening, errors: cleanupErrors };
  if (cleanupErrors.length) { report.status = 'failed'; process.exitCode = 1; }
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, report: reportPath, fixtureBytes: pdf.length,
    browserVersion: report.browserVersion, assertions: report.assertions, error: report.error, cleanup: report.cleanup }, null, 2));
}
