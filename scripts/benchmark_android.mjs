// Debug APK only. Synthetic fixtures stress byte access; results do not represent real-device performance.
import { execFileSync } from 'node:child_process';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const adb = process.env.ADB ?? `${process.env.ANDROID_HOME ?? `${process.env.HOME}/Library/Android/sdk`}/platform-tools/adb`;
const run = (...args) => execFileSync(adb, args, { encoding: 'utf8', timeout: 180000, maxBuffer: 4 * 1024 * 1024 });
const delay = ms => new Promise(r => setTimeout(r, ms));
const sizes = process.argv.slice(2).map(Number); if (!sizes.length) sizes.push(100, 300, 500);
const reports = [];
if (!run('shell', 'pm', 'path', 'org.pararead.android').includes('package:')) throw new Error('Install app/build/outputs/apk/debug/app-debug.apk before running the benchmark');
for (const size of sizes) {
  let socket;
  try {
  const name = `text-${size}MiB.pdf`; const path = resolve(root, '.cache/large-pdfs', name);
  console.log(`Preparing ${size} MiB`);
  run('push', path, `/data/local/tmp/${name}`);
  run('shell', 'run-as', 'org.pararead.android', 'mkdir', '-p', 'files/benchmarks');
  run('shell', 'run-as', 'org.pararead.android', 'cp', `/data/local/tmp/${name}`, `files/benchmarks/${name}`);
  run('shell', 'am', 'force-stop', 'org.pararead.android');
  const start = performance.now();
  run('shell', 'am', 'start', '-n', 'org.pararead.android/.MainActivity', '--es', 'benchmarkFile', name);
  let targets, pid;
  for (let i = 0; i < 120; i++) {
    try {
      pid = run('shell', 'pidof', 'org.pararead.android').trim();
      run('forward', 'tcp:9224', `localabstract:webview_devtools_remote_${pid}`);
      targets = await fetch('http://127.0.0.1:9224/json/list').then(r => r.json());
      if (targets.some(t => t.type === 'page')) break;
    } catch {}
    await delay(500);
  }
  if (!targets?.some(t => t.type === 'page')) throw new Error('WebView did not start');
  socket = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((r, reject) => { socket.addEventListener('open', r, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let id = 0; const pending = new Map();
  socket.addEventListener('message', event => { const response = JSON.parse(event.data); if (pending.has(response.id)) { pending.get(response.id)(response); pending.delete(response.id); } });
  socket.addEventListener('close', () => { for (const resolve of pending.values()) resolve({error:{message:'RENDERER_DISCONNECTED'}}); pending.clear(); });
  const call = (method, params, timeout = 15000) => new Promise((r, reject) => {
    const requestId = ++id;
    const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`CDP timeout ${method}`)); }, timeout);
    pending.set(requestId, value => { clearTimeout(timer); r(value); }); socket.send(JSON.stringify({ id: requestId, method, params }));
  });
  const evaluate = async (expression, timeout) => {
    const response = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, timeout);
    if (response.error) throw new Error(response.error.message);
    if (response.result?.exceptionDetails) throw new Error(JSON.stringify(response.result.exceptionDetails));
    return response.result?.result?.value;
  };
  let rendered = false;
  for (let i = 0; i < 180; i++) {
    rendered = await evaluate(`Boolean(window.PDFViewerApplication?.pdfDocument && PDFViewerApplication.pdfViewer.getPageView(0)?.renderingState===3 && document.querySelector('#viewerContainer')?.clientHeight>200 && document.querySelector('.page canvas')?.width>0)`);
    if (rendered) break; await delay(500);
  }
  const firstRenderMs = performance.now() - start;
  const pages = await evaluate('window.PDFViewerApplication?.pdfDocument?.numPages ?? 0');
  const jump = Math.min(pages, Math.max(2, Math.floor(pages * .75)));
  const jumpStart = performance.now();
  await evaluate(`window.__jumpRendered=false; PDFViewerApplication.eventBus.on('pagerendered',({pageNumber})=>{if(pageNumber===${jump})window.__jumpRendered=true}); PDFViewerApplication.pdfViewer.currentPageNumber=${jump}`);
  for (let i = 0; i < 120 && !await evaluate('window.__jumpRendered'); i++) await delay(100);
  const jumpRendered = await evaluate('window.__jumpRendered');
  const jumpMs = performance.now() - jumpStart;
  const heap = await call('Runtime.getHeapUsage', {});
  const processes = run('shell', 'ps', '-A', '-o', 'PID,NAME,ARGS').split('\n').filter(line => line.includes('org.pararead.android') || line.includes('sandboxed_process'));
  const memory = [];
  for (const line of processes) {
    const processId = line.trim().split(/\s+/)[0];
    try { const dump = run('shell', 'dumpsys', 'meminfo', processId); memory.push({ pid: processId, process: line.trim(), pssKiB: Number(dump.match(/TOTAL PSS:\s*(\d+)/)?.[1] ?? dump.match(/TOTAL\s+(\d+)/)?.[1] ?? 0) }); } catch {}
  }
  const incrementalSave = await evaluate(`(async()=>{
    const previous=ParaReadNative.onmessage;let confirmed=false,received=0;
    ParaReadNative.onmessage=e=>{const m=JSON.parse(e.data);if(m.type==='save.finished')confirmed=m.payload.draftPersisted===true;if(m.type==='save.progress')received=m.payload.received;previous(e)};
    try{PDFViewerApplication.pdfDocument.annotationStorage.setValue('pdfjs_internal_editor_777',{annotationType:3,pageIndex:${jump-1},rect:[1800,660,2060,690],rotation:0,color:[0,0,0],fontSize:12,value:'ParaRead large save ${size} MiB'});
      const started=performance.now();await paraRead.save();return {nativeDraftConfirmed:confirmed,suffixBytes:received,elapsedMs:performance.now()-started};
    }finally{ParaReadNative.onmessage=previous;}
  })()`, 180000);
  const report = { requestedMiB: size, environment: 'Android API 36 ARM64 emulator, Mock provider, synthetic text', rendered, pages,
    firstRenderMs, jumpPage: jump, jumpRendered, jumpMs, mainJsHeap: heap.result, processes: memory, aggregatePssKiB: memory.reduce((sum, m) => sum + m.pssKiB, 0) };
  report.incrementalSave = incrementalSave;
  reports.push(report); console.log(JSON.stringify(report, null, 2)); socket.close();
  await writeFile(resolve(root, 'docs/reports/android-large-pdf.json'), JSON.stringify(reports, null, 2));
  if (!rendered || !jumpRendered || !incrementalSave.nativeDraftConfirmed) process.exitCode = 1;
  } catch (error) {
    reports.push({requestedMiB:size,status:'failed',error:String(error),environment:'Android emulator, synthetic text'});
    await writeFile(resolve(root,'docs/reports/android-large-pdf.json'),JSON.stringify(reports,null,2));
    console.error(`Benchmark ${size} MiB failed: ${error}`); process.exitCode=1;
  } finally { socket?.close(); }
}
