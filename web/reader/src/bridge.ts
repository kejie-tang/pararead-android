/* Android host adapter. ParaRead UI modules remain in ./upstream (Apache-2.0). */
export {};
type Session = { sessionId: string; documentId: string; documentRevision: string; length: number; token: string;
  viewportEpoch: number; configRevision: number; reading: any; settings: any; debug: boolean };
type Envelope = { version: number; type: string; sessionId: string; documentId: string; documentRevision: string;
  requestId: string; viewportEpoch: number; configRevision: number; payload: any };
declare global { interface Window { ParaReadNative: { postMessage(s: string): void; onmessage: (e: {data: string}) => void }; paraRead: any } }
const origin = location.origin;
const utf8 = new TextEncoder();
const fitViewport = () => { const height = `${window.innerHeight}px`; document.documentElement.style.height = height; if (document.body) document.body.style.height = height; };
window.addEventListener('resize', fitViewport);
window.visualViewport?.addEventListener('resize', fitViewport);
fitViewport();
let session: Session;
let sequence = 0;
let panelState: any = {};
let resolveReady: (s: Session) => void;
const ready = new Promise<Session>(r => { resolveReady = r; });
const waiters = new Map<string, { resolve: (m: Envelope) => void; timer: ReturnType<typeof setTimeout> }>();
const streams = new Map<string, number>();
const terminal = new Set<string>();
const active = new Map<string, { legacyId: number; priority: number }>();
let runningJob: number | undefined;
const cancelledJobs = new Set<number>();
function post(type: string, payload: any = {}, requestId = `web-${++sequence}`) {
  const envelope = { version: 1, type, sessionId: session?.sessionId ?? 'bootstrap', documentId: session?.documentId ?? 'bootstrap',
    documentRevision: session?.documentRevision ?? 'bootstrap', requestId, viewportEpoch: session?.viewportEpoch ?? 0,
    configRevision: session?.configRevision ?? 0, payload };
  const text = JSON.stringify(envelope);
  if (utf8.encode(text).length > 65536) throw new Error('MESSAGE_TOO_LARGE');
  window.ParaReadNative.postMessage(text);
  return requestId;
}
function request(type: string, payload: any, timeout = 15000): Promise<Envelope> {
  const id = `web-${++sequence}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { waiters.delete(id); post('request.cancel', { requestId: id }); reject(new Error('HOST_TIMEOUT')); }, timeout);
    waiters.set(id, { resolve, timer });
    try { post(type, payload, id); } catch (e) { clearTimeout(timer); waiters.delete(id); reject(e); }
  });
}
function legacy(message: any) { window.postMessage(message, origin); }
window.ParaReadNative.onmessage = ({ data }) => {
  try {
    if (utf8.encode(data).length > 65536) return;
    const m: Envelope = JSON.parse(data);
    if (m.version !== 1 || typeof m.type !== 'string' || typeof m.requestId !== 'string' || !m.payload) return;
    if (m.type === 'session.open') {
      if (session) return;
      session = { ...m.payload, sessionId: m.sessionId, documentId: m.documentId, documentRevision: m.documentRevision,
        viewportEpoch: m.viewportEpoch, configRevision: m.configRevision };
      panelState = session.reading?.panelState ?? {};
      resolveReady(session); return;
    }
    if (!session || m.sessionId !== session.sessionId || m.documentId !== session.documentId || m.documentRevision !== session.documentRevision || m.configRevision !== session.configRevision) return;
    if (m.type === 'translation.delta' || m.type === 'translation.committed') {
      if (terminal.has(m.requestId) || !active.has(m.requestId)) return;
      if (!Number.isInteger(m.payload.sequence) || m.payload.sequence <= (streams.get(m.requestId) ?? -1)) return;
      streams.set(m.requestId, m.payload.sequence);
      if (m.type === 'translation.committed') legacy({ type: 'translations', requestId: active.get(m.requestId)!.legacyId, items: m.payload.items });
      else legacy({ type: 'translationDraft', requestId: active.get(m.requestId)!.legacyId, items: m.payload.items });
    }
    if (m.type === 'translation.finished' || m.type === 'protocol.error') {
      if (terminal.has(m.requestId)) return;
      terminal.add(m.requestId);
      if (terminal.size > 256) terminal.delete(terminal.values().next().value!);
    }
    const waiter = waiters.get(m.requestId);
    if (waiter && m.type !== 'translation.delta' && m.type !== 'translation.committed') {
      clearTimeout(waiter.timer); waiters.delete(m.requestId); waiter.resolve(m);
    }
    if (m.type === 'reader.command') {
      if (m.payload.type === 'settings') session.settings = { ...session.settings, ...m.payload };
      if (m.payload.type === 'translateAll') { runningJob = m.payload.jobId; cancelledJobs.delete(m.payload.jobId); }
      if (m.payload.type === 'cancelTranslateAll' && runningJob !== undefined) cancelledJobs.add(runningJob);
      legacy(m.payload);
    }
    if (m.type === 'document.save') void save();
  } catch (e) { console.error('Invalid host message', String(e)); }
};
async function translate(message: any) {
  const failures: any[] = [];
  // Requests are bounded and sequential even when a PDF page contains thousands of sentences.
  let batch: any[] = []; let bytes = 0;
  const batches: any[][] = [];
  for (const sentence of message.sentences) {
    const size = utf8.encode(sentence.text).length;
    if (size > 16384) { failures.push({ id: sentence.id, message: '句子超过 16KiB，需要拆分后重试' }); continue; }
    if (batch.length && (batch.length >= 40 || bytes + size > 32768)) { batches.push(batch); batch = []; bytes = 0; }
    batch.push(sentence); bytes += size;
  }
  if (batch.length) batches.push(batch);
  const epoch = session.viewportEpoch;
  for (const sentences of batches) {
    if (message.jobId != null && cancelledJobs.has(message.jobId)) {
      legacy({ type: 'translationDone', requestId: message.requestId, failures: [], cancelled: true }); return;
    }
    const priority = message.jobId != null ? 3 : (message.priority ?? 1);
    if (priority !== 0 && priority !== 3 && epoch !== session.viewportEpoch) break;
    const id = `translate-${++sequence}`;
    active.set(id, { legacyId: message.requestId, priority });
    try {
      const response = await new Promise<Envelope>((resolve, reject) => {
        const timer = setTimeout(() => { waiters.delete(id); post('request.cancel', { requestId: id }); reject(new Error('TRANSLATION_TIMEOUT')); }, 480000);
        waiters.set(id, { resolve, timer });
        post('translation.request', { sentences, priority, jobId: message.jobId, page: Number(sentences[0].id.match(/^p(\d+)/)?.[1] ?? 1) }, id);
      });
      failures.push(...(response.payload.failures ?? []));
      if (response.type === 'protocol.error') failures.push(...sentences.map(s => ({ id: s.id, message: response.payload.code })));
      if (response.payload.cancelled) { legacy({ type: 'translationDone', requestId: message.requestId, failures: [], cancelled: true }); return; }
    } catch (e) { failures.push(...sentences.map(s => ({ id: s.id, message: String(e) }))); }
    finally { active.delete(id); streams.delete(id); }
  }
  legacy({ type: 'translationDone', requestId: message.requestId, failures });
}
window.acquireVsCodeApi = () => ({
  postMessage(m: any) {
    if (m.type === 'translate') void translate(m);
    else if (m.type === 'translateAllProgress' || m.type === 'translateAllDone') {
      return request(m.type === 'translateAllProgress' ? 'job.progress' : 'job.finished', m).then(response => {
        if (response.type === 'protocol.error') throw new Error(response.payload.code);
        if (m.type === 'translateAllProgress' && response.payload.status !== 'RUNNING') throw new Error('JOB_STOPPED');
        if (m.type === 'translateAllDone') { cancelledJobs.delete(m.jobId); if (runningJob === m.jobId) runningJob = undefined; }
      });
    }
    else if (m.type === 'chooseTargetLanguage') post('settings.chooseLanguage');
    else if (m.type === 'dirty') post('annotation.changed');
    else if (m.type === 'requestSave') post('document.save');
    else if (m.open) post('external.open', { url: m.open });
  },
  getState: () => panelState,
  setState(value: any) { panelState = value; if (session) post('reading.save', { panelState }); },
});
let inflight = 0;
const rangeWaiters: (() => void)[] = [];
async function rangeSlot() { if (inflight >= 4) await new Promise<void>(r => rangeWaiters.push(r)); else inflight++; }
function rangeRelease() { const next = rangeWaiters.shift(); if (next) next(); else inflight--; }
function makeRangeTransport(pdfjs: any, s: Session) {
  const abort = new AbortController();
  const transport = new pdfjs.PDFDataRangeTransport(s.length, new Uint8Array(0), true);
  transport.requestDataRange = (begin: number, end: number) => {
    void (async () => {
      if (end - begin > 16 * 1024 * 1024) throw new Error('RANGE_EXCEEDS_16MIB_BUDGET');
      const merged = new Uint8Array(end - begin);
      for (let offset = begin; offset < end; offset += 262144) {
        await rangeSlot();
        try {
          const limit = Math.min(end, offset + 262144);
          const response = await fetch(`/documents/${s.token}/ranges?begin=${offset}&end=${limit}`, { signal: abort.signal });
          if (!response.ok) throw new Error(`RANGE_HTTP_${response.status}`);
          const chunk = new Uint8Array(await response.arrayBuffer());
          if (chunk.length !== limit - offset) throw new Error('TRUNCATED_RANGE');
          merged.set(chunk, offset - begin);
        } finally { rangeRelease(); }
      }
      transport.onDataRange(begin, merged);
    })().catch(e => { abort.abort(); (window.PDFViewerApplication as any)?.close?.(); post('session.error', { message: String(e) }); });
  };
  transport.abort = () => abort.abort();
  return transport;
}
function viewport(page: number) {
  session.viewportEpoch++;
  for (const [id, request] of active) if (request.priority === 1 || request.priority === 2) post('request.cancel', { requestId: id });
  post('page.visible', { page });
}
async function pageCached(page: number) {
  const response = await request('page.cacheGet', { page }, 3000);
  return response.payload.page ?? null;
}
function pageText(page: any) {
  const data = JSON.stringify(page); const size = 5000; const count = Math.ceil(data.length / size);
  if (count > 64) { post('telemetry.event', { name: 'PAGE_TEXT_TOO_LARGE', value: data.length }); return; }
  for (let i = 0; i < count; i++) post('page.textChunk', { page: page.page, chunkIndex: i, chunkCount: count, data: data.slice(i * size, (i + 1) * size) });
}
let saveActive = false;
let saveQueued = false;
async function save() {
  if (saveActive) { saveQueued = true; return; }
  saveActive = true;
  try {
    const app: any = window.PDFViewerApplication;
    app.pdfViewer._layerProperties?.annotationEditorUIManager?.endCurrentEditing();
    const annotationHash = app.pdfDocument.annotationStorage.serializable.hash;
    // Patched PDF.js returns ONLY the incremental suffix. Original bytes never cross the bridge.
    const data: Uint8Array = app.pdfDocument.annotationStorage.size ? await app.pdfDocument.saveDocument() : new Uint8Array();
    const started = await request('document.save', { incrementalLength: data.length });
    if (started.type === 'protocol.error') throw new Error(started.payload.code);
    const saveId = started.payload.saveId;
    for (let offset = 0; offset < data.length; offset += 24576) {
      const chunk = data.subarray(offset, offset + 24576);
      let binary = ''; for (const byte of chunk) binary += String.fromCharCode(byte);
      const response = await request('save.chunk', { saveId, offset, data: btoa(binary) });
      if (response.type === 'protocol.error') throw new Error(response.payload.code);
    }
    const result = await request('save.end', { saveId, length: data.length });
    if (result.type !== 'save.finished') throw new Error(result.payload.code ?? 'SAVE_FAILED');
    if (annotationHash === app.pdfDocument.annotationStorage.serializable.hash) app.pdfDocument.annotationStorage.resetModified();
    else post('annotation.changed');
  } catch (e) { post('session.error', { message: `保存失败: ${String(e)}` }); }
  finally { saveActive = false; if (saveQueued) { saveQueued = false; void save(); } }
}
Object.assign(window, { paraRead: { ready, post, request, viewport, pageCached, pageText, makeRangeTransport, save } });
post('reader.ready', { versions: [1], reader: '0.6.1-android', pdfjs: '6.2.108' });
