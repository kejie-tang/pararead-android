import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {resolve, extname, sep} from 'node:path';
import {chromium} from '@playwright/test';
import {assertTranslationWarningStyle} from './translation-warning-assertions.mjs';

const reportDirectory = process.env.PARAREAD_REPORT_DIR ? resolve(process.env.PARAREAD_REPORT_DIR) : resolve(import.meta.dirname, '../docs/reports');

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
  const context = await browser.newContext({viewport: {width: 700, height: 650}, hasTouch:true});
  await context.addInitScript(({fixture}) => {
    const background = new URL(location.href).searchParams.has('background');
    const params = new URL(location.href).searchParams;
    const readingOnly = params.has('readingOnly');
    const translationFault = params.get('translationFault');
    const bookChapters = params.has('noChapters') ? [] : params.has('manyChapters')
      ? Array.from({length:205},(_,i) => ({title:`测试章节 ${i+1}`,page:Math.min(3,Math.floor(i/70)+1)}))
      : [{title:'第一章 开始',page:1},{title:'第二章 继续',page:2},{title:'Chapter 3 Ending',page:3}];
    const prefix = translationFault ? 'txt-reader-proof:fault:'+translationFault+':' : 'txt-reader-proof:';
    const read = (key, fallback) => JSON.parse(localStorage.getItem(prefix + key) ?? JSON.stringify(fallback));
    const save = (key, value) => localStorage.setItem(prefix + key, JSON.stringify(value));
    const session = {version:1, sessionId:'txt-browser', documentId:'txt-fixture', documentRevision:'txt-v1', configRevision:1, viewportEpoch:0};
    const state = window.__txtProof = {messages:[], pages:[], savedPages:[], translations:[], checkpoints:[], readingWrites:0, finished:null, flushes:[], chapterBatches:[]};
    const chunks = new Map();
    const send = (message, type, payload) => setTimeout(() => window.ParaReadNative.onmessage({data:JSON.stringify({...message, ...session, type, payload})}), 0);
    const command = (type, payload = {}) => send({requestId:'command-' + Math.random()}, 'reader.command', {type, ...payload});
    state.command = command;
    window.ParaReadNative = {postMessage(raw) {
      const m = JSON.parse(raw); state.messages.push(m.type);
      if (m.type === 'reader.ready') send(m, 'session.open', {token:'txt-test', length:1, format:'txt',
        text:{pages:fixture.length, chapters:bookChapters.slice(0,100), chapterCount:bookChapters.length, encoding:'UTF-8', indexVersion:1},
        reading:read('reading', {}), settings:{uiLanguage:'zh-CN',translateRange:'manual',readingOnly,backgroundTranslation:background}});
      else if (m.type === 'session.opened' && background) command('translateAll', {jobId:11,startPage:1});
      else if (m.type === 'text.chapters') {
        state.chapterBatches.push(m.payload.offset);
        send(m,'text.chapters',{chapters:bookChapters.slice(m.payload.offset,m.payload.offset+m.payload.count),total:bookChapters.length});
      }
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
        const failed = translationFault && m.payload.sentences.find(s => s.text.includes('remembers'));
        const successful = m.payload.sentences.filter(s => s !== failed);
        const items = successful.map(s => ({id:s.id,translation:'【模拟译文】' + s.text + (s.text.includes('The reader remembers') ? '（http_500）' : '')}));
        successful.forEach((s,i) => { cache[s.text] = items[i].translation; }); save('cache',cache);
        if (failed) send(m,'translation.delta',{sequence:0,items:[{id:failed.id,translation:'保留的不完美译文'}]});
        send(m,'translation.committed',{sequence:failed?1:0,items});
        send(m,'translation.finished',{failures:failed?[{id:failed.id,message:translationFault}]:[]});
      } else if (m.type === 'reading.save') {
        state.readingWrites++; save('reading',{...read('reading',{}),...m.payload});
        if (state.holdReadingAck) (state.readingAcks ??= []).push(() => send(m,'reading.saved',{}));
        else send(m,'reading.saved',{});
      }
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
  await assertTranslationWarningStyle(page);
  await page.getByRole('button', {name:'章节',exact:true}).click();
  await page.locator('.textNavigationPanelChapter').nth(2).click();
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
  await assertTranslationWarningStyle(page);
  report.translationWarnings = {plainText:true,normalStyleInLightAndDark:true,noUnderline:true,cacheRestoreWithoutInference:true};
  const paragraphRects = () => page.locator('.textBlock').evaluateAll(blocks => blocks.filter(b => b.querySelector('.bilingualSource')).map(b => ({
    source:b.querySelector('.bilingualSource').getBoundingClientRect().toJSON(),
    target:b.querySelector('.bilingualTarget').getBoundingClientRect().toJSON(),
  })));
  for (const viewport of [{width:640,height:360},{width:768,height:1024},{width:800,height:1280},{width:1280,height:800}]) {
    await page.setViewportSize(viewport);
    const pairs = await paragraphRects(); assert.ok(pairs.length > 1);
    for (const {source,target} of pairs) {
      assert.ok(source.right <= target.left, 'tablet original must be left of translation');
      assert.ok(Math.abs(source.top - target.top) < 1, 'paired paragraphs must start at the same height');
      assert.ok(source.width > 200 && target.width > 200, 'each tablet column must remain readable');
    }
    assert.ok(await page.locator('.textContent').evaluate(e => e.scrollWidth <= e.clientWidth), 'tablet content must not overflow');
  }
  const split = page.getByRole('separator', {name:'原文栏宽度',exact:true});
  await split.focus(); await page.keyboard.press('ArrowRight');
  assert.equal(await split.getAttribute('aria-valuenow'), '55');
  const handle = await split.boundingBox();
  await page.mouse.move(handle.x + handle.width/2,handle.y+handle.height/2);
  await page.mouse.down(); await page.mouse.move(handle.x+handle.width/2+100,handle.y+handle.height/2,{steps:5}); await page.mouse.up();
  const ratio = await page.evaluate(() => Number(document.querySelector('.textReader').style.getPropertyValue('--text-source-share')));
  assert.ok(ratio > .6 && ratio <= .75, 'drag widens the original column');
  assert.ok((await paragraphRects())[0].source.width > (await paragraphRects())[0].target.width);
  await page.waitForFunction(ratio => JSON.parse(localStorage.getItem('txt-reader-proof:reading')).txt.columnRatio === ratio, ratio);
  await page.reload();
  await page.waitForFunction(() => document.querySelector('.bilingualSentence') && !document.querySelector('[aria-busy="true"]'));
  assert.equal(await page.evaluate(() => Number(document.querySelector('.textReader').style.getPropertyValue('--text-source-share'))),ratio,'reopening retains split preference');
  const pairedWidth = (await paragraphRects())[0].target.width;
  const showSource = page.getByRole('checkbox', {name:'显示原文',exact:true});
  await showSource.uncheck();
  assert.equal(await split.isVisible(), false, 'single-column mode has no separator');
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
  await page.evaluate(() => window.__txtProof.command('readerFullscreen',{enabled:true,showControlsLabel:'显示阅读工具栏'}));
  await page.getByRole('button',{name:'显示阅读工具栏',exact:true}).click();
  await page.getByRole('button',{name:'收起阅读工具栏',exact:true}).click();
  assert.equal(await page.locator('.textToolbar').isVisible(),false);
  await page.getByRole('button',{name:'显示阅读工具栏',exact:true}).click();
  await page.evaluate(() => window.__txtProof.command('readerFullscreen',{enabled:false}));
  await pageInput.fill('1'); await page.getByRole('button',{name:'跳转',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  const cdp = await context.newCDPSession(page);
  const swipe = async (dx,dy=0,edge=false) => {
    const rect=await page.locator('.textContent').boundingBox();
    const x=edge?10:rect.x+rect.width/2,y=rect.y+Math.min(80,rect.height/2);
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});
    for(let step=1;step<=5;step++) await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*step/5,y:y+dy*step/5}]});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  };
  await swipe(-100,3);
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '2' && !document.querySelector('[aria-busy="true"]'));
  await swipe(100,3);
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  await swipe(100,0,true);
  assert.equal(await pageInput.inputValue(),'1','edge swipe belongs to Android Back');
  await swipe(3,-70);
  assert.equal(await pageInput.inputValue(),'1','vertical reading does not turn pages');
  await cdp.detach();
  await page.getByRole('button',{name:'章节',exact:true}).click();
  const dialog=page.getByRole('dialog');
  await dialog.locator('.textNavigationPanelNumber').fill('50');
  assert.equal(await pageInput.inputValue(),'1','percentage adjustment only previews');
  await dialog.getByRole('button',{name:'跳转',exact:true}).click();
  await page.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '2' && !document.querySelector('[aria-busy="true"]'));
  await page.getByRole('button',{name:'章节',exact:true}).click();
  await page.evaluate(() => { window.__txtProof.holdReadingAck=true; window.__txtProof.command('flushBeforeModeSwitch',{flushId:'txt-navigation-freeze'}); });
  await page.waitForFunction(() => document.body.inert && !document.querySelector('dialog').open);
  const frozenRequests=await page.evaluate(()=>window.__txtProof.pages.length);
  await page.keyboard.press('ArrowRight');
  await page.evaluate(()=>document.querySelector('.textNext').click());
  assert.equal(await page.evaluate(()=>window.__txtProof.pages.length),frozenRequests,'save freeze fences keyboard and synthetic direct actions');
  assert.equal(await pageInput.inputValue(),'2');
  await page.evaluate(()=>{window.__txtProof.holdReadingAck=false;for(const ack of window.__txtProof.readingAcks ?? []) ack();});
  await page.waitForFunction(()=>window.__txtProof.flushes.some(f=>f.flushId==='txt-navigation-freeze'&&f.success));
  await page.evaluate(()=>window.__txtProof.command('resumeAfterModeFlush',{flushId:'txt-navigation-freeze'}));
  await page.waitForFunction(()=>!document.body.inert);
  await page.getByRole('button',{name:'下一页',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('input[inputmode="numeric"]').value==='3'&&!document.querySelector('[aria-busy="true"]'));
  report.foreground = {pagination:true,chapterJump:true,invalidPageRejected:true,fontSize:true,anchorPreserved:true,reopen:true,cacheOnlyRestore:true,phoneLayout:true,darkMode:true,tabletSideBySide:true,paragraphAlignment:true,sourceHiddenSingleColumn:true,swipeNavigation:true,edgeAndVerticalGestureExclusions:true,splitDragAndKeyboard:true,splitReopen:true,phoneLandscapeSideBySide:true,fullscreenShowAndHide:true,percentagePreviewAndJump:true,durableFreezeNavigationGuard:true};
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
  await ro.getByRole('button',{name:'章节',exact:true}).click();
  await ro.locator('.textNavigationPanelChapter').nth(0).click();
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
  await ro.getByRole('button',{name:'章节',exact:true}).click();
  await ro.locator('.textNavigationPanelChapter').nth(0).click();
  await ro.waitForFunction(() => document.querySelector('input[inputmode="numeric"]').value === '1' && !document.querySelector('[aria-busy="true"]'));
  await ro.evaluate(() => {
    const font = document.querySelector('.textFontControl input'); font.value = '29'; font.dispatchEvent(new Event('input'));
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
  const chapters = await context.newPage(); chapters.on('pageerror',e=>errors.push(String(e)));
  await chapters.goto(origin+'/?manyChapters=1');
  await chapters.getByRole('button',{name:'章节',exact:true}).click();
  const list=chapters.locator('.textNavigationPanelList');
  assert.equal(await list.locator('li').count(),100);
  assert.ok(await list.evaluate(e=>e.scrollHeight>e.clientHeight),'chapters are vertically scrollable');
  for(const count of [200,205]) {
    await list.evaluate(e=>{e.scrollTop=e.scrollHeight;e.dispatchEvent(new Event('scroll'));});
    await chapters.waitForFunction(count=>document.querySelectorAll('.textNavigationPanelList li').length===count,count);
  }
  assert.deepEqual(await chapters.evaluate(()=>window.__txtProof.chapterBatches),[100,200]);
  await chapters.locator('.textNavigationPanelChapter').last().click();
  await chapters.waitForFunction(()=>document.querySelector('input[inputmode="numeric"]').value==='3');
  const empty=await context.newPage(); empty.on('pageerror',e=>errors.push(String(e)));
  await empty.goto(origin+'/?noChapters=1');
  await empty.getByRole('button',{name:'章节',exact:true}).click();
  assert.ok(await empty.getByText('暂无章节，仍可通过下方百分比跳转阅读位置。',{exact:true}).isVisible());
  await empty.locator('.textNavigationPanelNumber').fill('100');
  await empty.getByRole('dialog').getByRole('button',{name:'跳转',exact:true}).click();
  await empty.waitForFunction(()=>document.querySelector('input[inputmode="numeric"]').value==='3');
  const shots=process.env.PARAREAD_REPORT_DIR ? reportDirectory : resolve(import.meta.dirname,'../output/reader-interactions'); await mkdir(shots,{recursive:true});
  await page.setViewportSize({width:1280,height:800});
  await page.screenshot({path:resolve(shots,'txt-landscape-resizable.png')});
  await chapters.setViewportSize({width:360,height:800});
  await chapters.getByRole('button',{name:'章节',exact:true}).click();
  await chapters.screenshot({path:resolve(shots,'txt-chapters-progress.png')});
  report.navigationPanel={scrollableChapters:true,lazyBatches:[100,200],noChaptersPercentJump:true};
  for (const fault of ['TRUNCATED_STREAM_length','HTTP_500','HOST_TIMEOUT']) {
    await page.evaluate(() => { for (const key of Object.keys(localStorage)) if (key.startsWith('txt-reader-proof:')) localStorage.removeItem(key); });
    await page.goto(origin+'/?translationFault='+fault);
    await page.waitForFunction(() => document.querySelector('.bilingualSentence') && !document.querySelector('[aria-busy="true"]'));
    await page.getByRole('button',{name:'翻译当前页',exact:true}).click();
    await page.waitForFunction(code => [...document.querySelectorAll('.bilingualSentence')].some(el =>
      el.textContent.includes(`保留的不完美译文（${code}）`)),fault.toLowerCase()).catch(async error => {
        console.error('TXT fault diagnostic',await page.evaluate(()=>({proof:window.__txtProof,text:document.body.innerText.slice(-1500)})));
        throw error;
      });
    await assertTranslationWarningStyle(page,fault.toLowerCase());
    assert.equal(await page.evaluate(()=>window.__txtProof.translations.length),1,'ordinary failures never automatically request another translation');
  }
  report.generalErrorRetainsTextInLightAndDark=['truncated_stream_length','http_500','host_timeout'];
  assert.ok(!resources.some(p => p.includes('pdfjs')),'TXT must not load PDF.js');
  assert.deepEqual(errors,[]);
  report.status = 'passed'; report.timestamp = new Date().toISOString(); report.browserVersion = browser.version();
  await mkdir(reportDirectory,{recursive:true});
  await writeFile(resolve(reportDirectory,'txt-reader-browser.json'),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report,null,2));
} finally { await browser?.close(); await new Promise(done => server.close(done)); }
