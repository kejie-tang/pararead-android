import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, writeFile} from 'node:fs/promises';
import {resolve, extname, sep} from 'node:path';
import {chromium} from '@playwright/test';

const assets = resolve(import.meta.dirname, '../app/build/generated/readerAssets/reader');
const errors = [], resources = [];
const fixture = [
  '第一章 开始\n' + Array.from({length: 35}, (_, i) => `Paragraph ${i + 1} remembers the last reading position.\n`).join(''),
  '第二章 继续\nThe reader remembers the last page.\nA saved sentence stays available.\n',
  'Chapter 3 Ending\nThe last chapter remains available offline.\n',
];
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  resources.push(url.pathname);
  if (url.pathname === '/favicon.ico') { res.writeHead(204).end(); return; }
  const file = resolve(assets, '.' + (url.pathname === '/' ? '/text.html' : decodeURIComponent(url.pathname)));
  if (!file.startsWith(assets + sep)) { res.writeHead(403).end(); return; }
  try {
    res.setHeader('Content-Type', {'.html':'text/html', '.mjs':'text/javascript', '.css':'text/css'}[extname(file)] ?? 'application/octet-stream');
    res.end(await readFile(file));
  } catch { res.writeHead(404).end(); }
});
let browser;
const report = {scope: 'Actual desktop Edge/Chromium, generated TXT assets and real bridge/client; simulated Android host/storage and translations. No Android device, PDF or model execution.', status: 'running'};
try {
  await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({channel: process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined), headless: true});
  const context = await browser.newContext({viewport: {width: 700, height: 650}});
  await context.addInitScript(({fixture}) => {
    const background = new URL(location.href).searchParams.has('background');
    const readingOnly = new URL(location.href).searchParams.has('readingOnly');
    const prefix = 'txt-reader-proof:';
    const read = (key, fallback) => JSON.parse(localStorage.getItem(prefix + key) ?? JSON.stringify(fallback));
    const save = (key, value) => localStorage.setItem(prefix + key, JSON.stringify(value));
    const session = {version:1, sessionId:'txt-browser', documentId:'txt-fixture', documentRevision:'txt-v1', configRevision:1, viewportEpoch:0};
    const state = window.__txtProof = {messages:[], pages:[], savedPages:[], translations:[], checkpoints:[], readingWrites:0, finished:null, flushes:[]};
    const chunks = new Map();
    const send = (message, type, payload) => setTimeout(() => window.ParaReadNative.onmessage({data:JSON.stringify({...message, ...session, type, payload})}), 0);
    const command = (type, payload = {}) => send({requestId:'command-' + Math.random()}, 'reader.command', {type, ...payload});
    state.command = command;
    window.ParaReadNative = {postMessage(raw) {
      const m = JSON.parse(raw); state.messages.push(m.type);
      if (m.type === 'reader.ready') send(m, 'session.open', {token:'txt-test', length:1, format:'txt',
        text:{pages:fixture.length, chapters:[{title:'第一章 开始',page:1},{title:'第二章 继续',page:2},{title:'Chapter 3 Ending',page:3}], chapterCount:3, encoding:'UTF-8', indexVersion:1},
        reading:read('reading', {}), settings:{uiLanguage:'zh-CN',translateRange:'manual',readingOnly,backgroundTranslation:background}});
      else if (m.type === 'session.opened' && background) command('translateAll', {jobId:11,startPage:1});
      else if (m.type === 'text.page') {
        state.pages.push(m.payload.page);
        send(m, 'text.page', {page:m.payload.page,text:fixture[m.payload.page-1]});
      } else if (m.type === 'page.textChunk') {
        const p = m.payload, data = chunks.get(p.page) ?? [];
        data[p.chunkIndex] = p.data; chunks.set(p.page,data);
        if (data.filter(v => v !== undefined).length === p.chunkCount) {
          JSON.parse(data.join('')); state.savedPages.push(p.page); chunks.delete(p.page);
          send(m,'page.saved',{page:p.page});
        }
      } else if (m.type === 'translation.cacheGet') {
        const cache = read('cache', {}), items = m.payload.sentences.filter(s => cache[s.text]).map(s => ({id:s.id,translation:cache[s.text]}));
        if (items.length) send(m,'translation.committed',{sequence:0,items});
        send(m,'translation.finished',{failures:[]});
      } else if (m.type === 'translation.request') {
        const cache = read('cache', {});
        state.translations.push(m.payload.sentences.map(s => s.text));
        const items = m.payload.sentences.map(s => ({id:s.id,translation:'【模拟译文】' + s.text}));
        m.payload.sentences.forEach((s,i) => { cache[s.text] = items[i].translation; }); save('cache',cache);
        send(m,'translation.committed',{sequence:0,items}); send(m,'translation.finished',{failures:[]});
      } else if (m.type === 'reading.save') { state.readingWrites++; save('reading',{...read('reading',{}),...m.payload}); send(m,'reading.saved',{}); }
      else if (m.type === 'reader.flushed') state.flushes.push(m.payload);
      else if (m.type === 'job.progress' || m.type === 'job.finished') {
        state.checkpoints.push(m.payload); if (m.type === 'job.finished') state.finished = m.payload;
        send(m,'job.saved',{status:m.payload.status ?? 'RUNNING'});
      }
    }};
  }, {fixture});
  const page = await context.newPage(); page.setDefaultTimeout(15000);
  page.on('pageerror', e => errors.push(String(e)));
  await page.goto(origin);
  await page.waitForFunction(() => document.querySelectorAll('.bilingualSentence').length > 0 && !document.querySelector('[aria-busy="true"]'));
  assert.equal(await page.evaluate(() => window.__txtProof.translations.length), 0, 'manual open must not infer');
  assert.equal(await page.locator('button', {hasText:'上一页'}).isDisabled(), true);
  await page.getByRole('button', {name:'下一页',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '2' && !document.querySelector('[aria-busy="true"]'));
  await page.getByRole('button', {name:'翻译当前页',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('.bilingualSentence')?.textContent.includes('【模拟译文】'));
  await page.getByLabel('章节', {exact:true}).selectOption('3');
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '3' && !document.querySelector('[aria-busy="true"]'));
  assert.equal(await page.getByRole('button', {name:'下一页',exact:true}).isDisabled(), true);
  const pageInput = page.getByRole('textbox', {name:'页码',exact:true});
  await pageInput.fill('999'); await page.getByRole('button', {name:'跳转',exact:true}).click();
  assert.equal(await pageInput.getAttribute('aria-invalid'), 'true');
  await pageInput.fill('1'); await page.getByRole('button', {name:'跳转',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  await page.locator('.textContent').evaluate(e => { e.scrollTop = 300; e.dispatchEvent(new Event('scroll')); });
  const font = page.getByRole('slider', {name:'字号',exact:true});
  await font.fill('26'); await font.dispatchEvent('input');
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.textContent')).fontSize === '26px');
  assert.ok(await page.locator('.textContent').evaluate(e => e.scrollTop > 0), 'font changes preserve the reading anchor');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('txt-reader-proof:reading'))?.txt?.fontSize === 26);
  await page.reload();
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.textContent')).fontSize === '26px' && !document.querySelector('[aria-busy="true"]'));
  assert.ok(await page.locator('.textContent').evaluate(e => e.scrollTop > 0), 'reopening restores position');
  assert.equal(await page.evaluate(() => window.__txtProof.translations.length),0);
  await pageInput.fill('2'); await page.getByRole('button', {name:'跳转',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '2' && document.querySelector('.bilingualSentence')?.textContent.includes('【模拟译文】'));
  assert.equal(await page.evaluate(() => window.__txtProof.translations.length),0,'cached translations restore without inference');
  const paragraphRects = () => page.locator('.textBlock').evaluateAll(blocks => blocks.filter(b => b.querySelector('.bilingualSource')).map(b => ({
    source:b.querySelector('.bilingualSource').getBoundingClientRect().toJSON(),
    target:b.querySelector('.bilingualTarget').getBoundingClientRect().toJSON(),
  })));
  for (const viewport of [{width:768,height:1024},{width:800,height:1280},{width:1280,height:800}]) {
    await page.setViewportSize(viewport);
    const pairs = await paragraphRects(); assert.ok(pairs.length > 1);
    for (const {source,target} of pairs) {
      assert.ok(source.right <= target.left, 'tablet original must be left of translation');
      assert.ok(Math.abs(source.top - target.top) < 1, 'paired paragraphs must start at the same height');
      assert.ok(source.width > 200 && target.width > 200, 'each tablet column must remain readable');
    }
    assert.ok(await page.locator('.textContent').evaluate(e => e.scrollWidth <= e.clientWidth), 'tablet content must not overflow');
  }
  const pairedWidth = (await paragraphRects())[0].target.width;
  const showSource = page.getByRole('checkbox', {name:'显示原文',exact:true});
  await showSource.uncheck();
  assert.equal(await page.locator('.bilingualSource').first().isVisible(), false);
  assert.ok((await page.locator('.bilingualTarget').first().boundingBox()).width > pairedWidth, 'hiding original restores single column');
  await showSource.check();
  await font.fill('32'); await font.dispatchEvent('input');
  assert.ok(await page.locator('.textContent').evaluate(e => e.scrollWidth <= e.clientWidth), 'large tablet font must wrap');
  await font.fill('26'); await font.dispatchEvent('input');
  await page.setViewportSize({width:360,height:720});
  for (const {source,target} of await paragraphRects()) assert.ok(source.top >= target.bottom, 'phone keeps stacked paragraphs');
  await page.emulateMedia({colorScheme:'dark'});
  assert.ok(await page.locator('.textToolbar').evaluate(e => e.scrollWidth <= e.clientWidth), 'phone controls must not overflow horizontally');
  assert.ok(await page.locator('.textContent').evaluate(e => e.clientHeight > 100), 'phone controls must leave readable content space');
  for (const scheme of ['light','dark']) {
    await page.emulateMedia({colorScheme:scheme});
    for (const width of [320,360,800,1280]) {
      await page.setViewportSize({width,height:800});
      assert.ok(await page.locator('.textToolbar').evaluate(e => e.scrollWidth <= e.clientWidth), `controls fit ${width}px ${scheme}`);
      for (const button of await page.locator('.textToolbar button:visible').all()) {
        assert.ok((await button.boundingBox()).height >= 44, 'buttons retain touch targets');
      }
    }
    const contrasts = await page.evaluate(() => {
      const rgb = value => value.match(/[0-9.]+/g).slice(0,3).map(Number).map(v => { const s=v/255; return s<=.04045?s/12.92:((s+.055)/1.055)**2.4; });
      const lum = color => { const c=rgb(color); return .2126*c[0]+.7152*c[1]+.0722*c[2]; };
      const contrast = (a,b) => { const x=lum(a),y=lum(b); return (Math.max(x,y)+.05)/(Math.min(x,y)+.05); };
      const body=getComputedStyle(document.body), primary=getComputedStyle(document.querySelector('.textTranslate'));
      const source=getComputedStyle(document.querySelector('.bilingualSource'));
      const controls = [...document.querySelectorAll('.textToolbar button:not(:disabled)')].map(e => { const style=getComputedStyle(e); return contrast(style.color,style.backgroundColor); });
      return {primary:contrast(primary.color,primary.backgroundColor),text:contrast(body.color,body.backgroundColor),source:contrast(source.color,body.backgroundColor),controls:Math.min(...controls)};
    });
    assert.ok(Object.values(contrasts).every(value => value >= 4.5), `readable ${scheme} contrast: ${JSON.stringify(contrasts)}`);
  }
  await page.setViewportSize({width:360,height:800});
  await page.evaluate(() => document.documentElement.style.fontSize='32px');
  assert.ok(await page.locator('.textToolbar').evaluate(e => e.scrollWidth <= e.clientWidth), '200% UI text remains in bounds');
  assert.ok(await page.locator('.textContent').evaluate(e => e.clientHeight > 100), 'large controls leave content space');
  await page.evaluate(() => document.documentElement.style.removeProperty('font-size'));
  await page.keyboard.press('Tab');
  await page.getByRole('button',{name:'跳转',exact:true}).focus();
  assert.equal(await page.getByRole('button',{name:'跳转',exact:true}).evaluate(e => getComputedStyle(e).outlineStyle), 'solid', 'keyboard focus remains visible');
  report.controls = {lightAndDark:true,widths:[320,360,800,1280],touchTargets44px:true,contrastAtLeast4_5:true,keyboardFocus:true,largeUiText:true};
  report.foreground = {pagination:true,chapterJump:true,invalidPageRejected:true,fontSize:true,anchorPreserved:true,reopen:true,cacheOnlyRestore:true,phoneLayout:true,darkMode:true,tabletSideBySide:true,paragraphAlignment:true,sourceHiddenSingleColumn:true};
  const bg = await context.newPage(); bg.setDefaultTimeout(15000); bg.on('pageerror', e => errors.push(String(e)));
  await bg.goto(origin + '/?background=1');
  await bg.waitForFunction(() => window.__txtProof.finished?.status === 'COMPLETED' && window.paraRead.opened === false);
  const proof = await bg.evaluate(() => ({readingWrites:window.__txtProof.readingWrites, checkpoints:window.__txtProof.checkpoints,savedPages:window.__txtProof.savedPages, nodes:document.querySelectorAll('.textReader').length,pdf:!!window.PDFViewerApplication}));
  assert.equal(proof.readingWrites,0); assert.equal(proof.nodes,0); assert.equal(proof.pdf,false);
  assert.deepEqual(proof.savedPages,[1,2,3]);
  assert.deepEqual(proof.checkpoints.filter(p => p.done !== undefined && p.status === undefined).map(p => p.done),[1,2,3]);
  report.background = {noReadingWrites:true,noViewer:true,allPagesSaved:true,allCheckpointsAcknowledged:true};
  const ro = await context.newPage(); ro.setDefaultTimeout(15000); ro.on('pageerror', e => errors.push(String(e)));
  await ro.setViewportSize({width:800,height:1280});
  await ro.goto(origin + '/?readingOnly=1');
  await ro.waitForFunction(() => document.querySelectorAll('.bilingualTarget').length > 0 && !document.querySelector('[aria-busy="true"]'));
  await ro.getByLabel('章节', {exact:true}).selectOption('1');
  await ro.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  assert.equal(await ro.locator('.bilingualTarget').allTextContents().then(parts => parts.join('')), fixture[0], 'read-only preserves exact source text');
  assert.equal(await ro.locator('.bilingualSource, .bilingualSentence').count(), 0, 'read-only must not duplicate original or add translation click targets');
  assert.equal(await ro.getByRole('button', {name:'翻译当前页',exact:true}).count(), 0);
  assert.equal(await ro.getByRole('checkbox', {name:'显示原文',exact:true}).count(), 0);
  await ro.evaluate(() => { window.__txtProof.command('translateAll', {jobId:99}); window.__txtProof.command('translateCurrentPage'); window.__txtProof.command('retryFailed'); });
  const roFont = ro.getByRole('slider', {name:'字号',exact:true}); await roFont.fill('28'); await roFont.dispatchEvent('input');
  await ro.waitForFunction(() => getComputedStyle(document.querySelector('.textContent')).fontSize === '28px');
  await ro.getByRole('button', {name:'下一页',exact:true}).click();
  await ro.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '2' && !document.querySelector('[aria-busy="true"]'));
  assert.equal(await ro.locator('.bilingualTarget').allTextContents().then(parts => parts.join('')), fixture[1]);
  const roProof = await ro.evaluate(() => window.__txtProof.messages);
  assert.ok(!roProof.some(type => ['translation.request','translation.cacheGet','job.progress','job.finished'].includes(type)), 'read-only has zero translation/cache/job messages');
  await ro.getByLabel('章节', {exact:true}).selectOption('1');
  await ro.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  await ro.evaluate(() => {
    const font = document.querySelector('input[type="range"]'); font.value = '29'; font.dispatchEvent(new Event('input'));
    const content = document.querySelector('.textContent'); content.scrollTop = 240; content.dispatchEvent(new Event('scroll'));
    window.__txtProof.command('flushBeforeModeSwitch', {flushId:'txt-browser-flush'});
  });
  await ro.waitForFunction(() => window.__txtProof.flushes.some(f => f.flushId === 'txt-browser-flush' && f.success));
  assert.equal(await ro.evaluate(() => document.body.inert), true);
  const flushedReading = await ro.evaluate(() => JSON.parse(localStorage.getItem('txt-reader-proof:reading')));
  assert.equal(flushedReading.txt.fontSize, 29); assert.ok(flushedReading.txt.scrollTop > 0);
  await ro.goto(origin);
  await ro.waitForFunction(() => document.querySelector('.bilingualSentence')?.textContent.includes('【模拟译文】'));
  assert.equal(await ro.evaluate(() => window.__txtProof.translations.length), 0, 'leaving read-only reuses intact cached translations');
  report.readingOnly = {exactSource:true,noTranslationOrCacheRequests:true,noJobWrites:true,navigationAndFont:true,hiddenTranslationControls:true,cacheIntactOnExit:true,durableModeFlush:true};
  assert.ok(!resources.some(p => p.includes('pdfjs')),'TXT must not load PDF.js');
  assert.deepEqual(errors,[]);
  report.status = 'passed'; report.timestamp = new Date().toISOString(); report.browserVersion = browser.version();
  await writeFile(resolve(import.meta.dirname,'../docs/reports/txt-reader-browser.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
} finally { await browser?.close(); await new Promise(done => server.close(done)); }
