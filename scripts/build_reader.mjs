import { build } from 'esbuild';
import { readFile, writeFile, mkdir, cp, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { patchWorker } from './patch_pdfjs.mjs';
const root = resolve(import.meta.dirname, '..');
const upstream = resolve(root, '.references/pararead');
const out = resolve(root, 'app/build/generated/readerAssets/reader');
await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
// Upstream sample PDFs have separate publication rights; do not ship them.
await cp(`${upstream}/assets/pdf.js`, `${out}/pdfjs`, { recursive: true, filter: source => !source.toLowerCase().endsWith('.pdf') });
await cp(`${root}/third_party`, `${out}/licenses`, { recursive: true });
await mkdir(`${out}/licenses/project`, { recursive: true });
for (const file of ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md']) await cp(`${root}/${file}`, `${out}/licenses/project/${file}`);
for (const file of ['main.css', 'panel.css']) await cp(`${upstream}/assets/${file}`, `${out}/${file}`);
await cp(`${root}/web/reader/android.css`, `${out}/android.css`);
await build({ entryPoints: [`${root}/web/reader/src/entry.ts`], outfile: `${out}/main.mjs`, bundle: true,
  platform: 'browser', format: 'esm', target: 'es2022', sourcemap: true,
  banner: { js: '/*! ParaRead Android: modified ParaRead 0.6.1, Apache-2.0. Attribution and changes: licenses/project/NOTICE and licenses/pararead/NOTICE. */' },
  plugins: [{ name: 'pdfjs-external', setup(b) { b.onResolve({ filter: /\/viewer\.mjs$/ }, () => ({ path: './pdfjs/web/viewer.mjs', external: true })); } }] });
const config = { url: '', resourceRoot: 'https://appassets.androidplatform.net/', debug: true, targetLanguage: 'zh-CN', translateRange: 'page', selectOnHover: false,
  ...Object.fromEntries(Object.entries({ sandboxBundleSrc: 'build/pdf.sandbox.mjs', cMapUrl: 'web/cmaps/', iccUrl: 'web/iccs/',
    standardFontDataUrl: 'web/standard_fonts/', wasmUrl: 'web/wasm/', imageResourcesPath: 'web/images/' }).map(([k, v]) => [k, `./pdfjs/${v}`])) };
const attribute = JSON.stringify(config).replaceAll('"', '&quot;');
let html = await readFile(`${upstream}/assets/pdf.js/web/viewer.html`, 'utf8');
html = html.replace('<link rel="resource" type="application/l10n" href="locale/locale.json" />', '')
  .replace('<script src="../build/pdf.mjs" type="module"></script>', '').replace('<script src="viewer.mjs" type="module"></script>', '')
  .replace('<link rel="stylesheet" href="viewer.css" />', '')
  .replace('<title>PDF.js viewer</title>', `<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; object-src 'none'; frame-src 'none'; base-uri 'none'">
<meta id="pdf-view-config" data-config="${attribute}"><title>ParaRead</title>
<link rel="stylesheet" href="./pdfjs/web/viewer.css"><link rel="stylesheet" href="./main.css"><link rel="stylesheet" href="./panel.css"><link rel="stylesheet" href="./android.css">
<link rel="resource" type="application/l10n" href="./pdfjs/web/locale/locale.json">
<script src="./pdfjs/build/pdf.mjs" type="module"></script><script src="./main.mjs" type="module"></script>
<!-- Modified for ParaRead Android (2026): native bootstrap, local assets, CSP and tablet layout. Original PDF.js/ParaRead notices are preserved in licenses/. -->`);
await writeFile(`${out}/index.html`, html);
let viewer = await readFile(`${out}/pdfjs/web/viewer.mjs`, 'utf8');
viewer = viewer.replace('const DEFAULT_CACHE_SIZE = 10;', 'const DEFAULT_CACHE_SIZE = 4; // ParaRead Android: canvas budget');
await writeFile(`${out}/pdfjs/web/viewer.mjs`, '/* Modified for ParaRead Android (2026): bounded viewer page buffer. See licenses/project/NOTICE. */\n' + viewer);
await writeFile(`${out}/pdfjs/build/pdf.worker.mjs`, '/* Modified for ParaRead Android (2026): sparse range cache and bounded incremental saves. See licenses/project/NOTICE. */\n' + patchWorker(await readFile(`${upstream}/assets/pdf.js/build/pdf.worker.mjs`, 'utf8')));
await cp(`${root}/samples/pararead-reading-guide.pdf`, `${out}/sample.pdf`);
await mkdir(`${out}/licenses/sample`, { recursive: true });
await cp(`${root}/samples/NOTICE`, `${out}/licenses/sample/NOTICE`);
console.log(`Reader assets: ${out}`);
