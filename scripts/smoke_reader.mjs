// HTTP smoke check for the original browser harness. No visual/WebView claim.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';

const root = fileURLToPath(new URL('../.references/pararead/', import.meta.url));
assert.ok(existsSync(`${root}dist/webview/main.mjs`), 'Run the upstream build first.');
const reservation = createServer();
reservation.listen(0, '127.0.0.1');
await once(reservation, 'listening');
const { port } = reservation.address();
await new Promise(resolve => reservation.close(resolve));

const child = spawn(process.execPath, ['tools/harness.mjs', String(port)], {
  cwd: root, stdio: ['ignore', 'pipe', 'pipe'],
});
try {
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Harness startup timed out')), 10000);
    const clear = () => clearTimeout(timer);
    child.once('error', error => { clear(); reject(error); });
    child.once('exit', code => { clear(); reject(new Error(`Harness exited: ${code}`)); });
    child.stdout.on('data', data => {
      if (data.toString().includes('harness on')) { clear(); resolve(); }
    });
  });
  const origin = `http://127.0.0.1:${port}`;
  const response = await fetch(`${origin}/`, { signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.ok(html.includes('pdf-view-config') && html.includes('window.__hostMessages'));
  assert.ok(html.includes('type: "translations"') && html.includes('dist/webview/main.mjs'));
  const resources = [
    ['dist/webview/main.mjs', 'text/javascript'],
    ['assets/pdf.js/build/pdf.mjs', 'text/javascript'],
    ['assets/pdf.js/build/pdf.worker.mjs', 'text/javascript'],
    ['assets/panel.css', 'text/css'],
    ['assets/pdf.js/web/compressed.tracemonkey-pldi-09.pdf', 'application/pdf'],
  ];
  const checked = await Promise.all(resources.map(async ([path, mime]) => {
    const resource = await fetch(`${origin}/${path}`, { signal: AbortSignal.timeout(5000) });
    try {
      assert.equal(resource.status, 200, path);
      assert.ok(resource.headers.get('content-type').startsWith(mime), path);
      assert.ok(Number(resource.headers.get('content-length')) > 0, path);
      return { path, status: resource.status, contentType: resource.headers.get('content-type') };
    } finally {
      await resource.body?.cancel();
    }
  }));
  process.stdout.write(`${JSON.stringify({ status: 'passed', scope: 'upstream harness HTML and resource HTTP responses only; no browser rendering', resources: checked }, null, 2)}\n`);
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    const exited = once(child, 'exit');
    child.kill('SIGTERM');
    await exited;
  }
}
