import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { orderColumns } from '../web/reader/src/reading-order.ts';
import { TranslationClient } from '../web/reader/src/upstream/translation-client.ts';

test('tapping a pending sentence upgrades priority without stale cancellation clearing its new request', async () => {
  const s = {id:'p1-b0-s0',page:1,blockId:'p1-b0',text:'Select me.',rects:[]};
  const page = {page:1,blocks:[{id:'p1-b0',kind:'text',sentences:[s]}]};
  const sent=[];
  const client=new TranslationClient({app:{pdfViewer:{currentPageNumber:1,pagesCount:1},eventBus:{on(){}}},
    store:{onPageReady(){},ensure:async()=>page},post:m=>sent.push(m),range:'page'});
  client.refresh(); await Promise.resolve(); await Promise.resolve();
  const background=sent[0]; assert.equal(background.priority,1);
  const selected=client.translateBlockOf(s); await Promise.resolve(); await Promise.resolve();
  const urgent=sent[1]; assert.equal(urgent.priority,0);
  client.handle({type:'translationDone',requestId:background.requestId,failures:[],cancelled:true});
  assert.equal(client.state(s.id).status,'pending');
  client.handle({type:'translations',requestId:urgent.requestId,items:[{id:s.id,translation:'选句'}]});
  client.handle({type:'translationDone',requestId:urgent.requestId,failures:[]});
  await selected; assert.deepEqual(client.state(s.id),{status:'done',translation:'选句'});
});

test('two-column block ordering keeps heading bands, IDs and coordinates', () => {
  const block = (id, x, y, w = 200) => ({ id, page: 1, kind: 'text', role: 'paragraph', text: id, sentences: [], lines: [{x, y, w, h: 10}] });
  const heading = block('heading', 40, 750, 520);
  const left1 = block('left1', 40, 700), right1 = block('right1', 340, 700);
  const left2 = block('left2', 40, 600), right2 = block('right2', 340, 600);
  const ordered = orderColumns([heading, right1, left1, right2, left2], [0,0,612,792]);
  assert.deepEqual(ordered.map(b => b.id), ['heading','left1','left2','right1','right2']);
  assert.equal(ordered[1], left1); assert.equal(ordered[1].lines[0].x, 40);
  const ambiguous = [block('a',40,700,300), block('b',300,700,250)];
  assert.equal(orderColumns(ambiguous,[0,0,612,792]), ambiguous);
});

test('bridge rejects stale sessions and duplicate/out-of-order translation messages', async () => {
  const built = await build({entryPoints:[fileURLToPath(new URL('../web/reader/src/bridge.ts',import.meta.url))], bundle:true, format:'iife', write:false});
  const sent = [], legacy = [];
  const timers = new Map(); let next = 0;
  const window = { innerHeight: 800, addEventListener(){}, postMessage(m){legacy.push(m);}, ParaReadNative:{postMessage(s){sent.push(JSON.parse(s));}} };
  const context = vm.createContext({window, document:{documentElement:{style:{}},body:{style:{}}}, location:{origin:'https://appassets.androidplatform.net'},
    TextEncoder, Uint8Array, Map, Set, AbortController, console, setTimeout(fn){timers.set(++next,fn);return next;},clearTimeout(id){timers.delete(id);}});
  vm.runInContext(built.outputFiles[0].text, context);
  const session = {version:1,sessionId:'session',documentId:'document',documentRevision:'revision',configRevision:1,viewportEpoch:0,requestId:'open'};
  const deliver = (type,payload,extra={}) => window.ParaReadNative.onmessage({data:JSON.stringify({...session,type,payload,...extra})});
  deliver('session.open',{length:1000,token:'token',reading:{},settings:{}});
  window.acquireVsCodeApi().postMessage({type:'translate',requestId:1,sentences:[{id:'p1-b0-s0',group:'p1-b0',text:'Test.'}]});
  const request = sent.find(m=>m.type==='translation.request'); assert.ok(request);
  const payload = {sequence:1,items:[{id:'p1-b0-s0',translation:'译文'}]};
  deliver('translation.committed',payload,{requestId:request.requestId,documentRevision:'stale'});
  deliver('translation.committed',payload,{requestId:request.requestId,version:2});
  assert.equal(legacy.length,0);
  deliver('translation.committed',payload,{requestId:request.requestId});
  await Promise.resolve(); await Promise.resolve();
  assert.equal(legacy.filter(m=>m.type==='translationDone').length,0, 'A partial commit must not terminate the translation stream');
  deliver('translation.committed',payload,{requestId:request.requestId});
  deliver('translation.delta',{...payload,sequence:0},{requestId:request.requestId});
  assert.equal(legacy.filter(m=>m.type==='translations').length,1);
  deliver('translation.finished',{failures:[]},{requestId:request.requestId});
  deliver('translation.finished',{failures:[]},{requestId:request.requestId});
  await Promise.resolve(); await Promise.resolve();
  assert.equal(legacy.filter(m=>m.type==='translationDone').length,1);
  assert.equal(timers.size,0);
  assert.throws(()=>window.paraRead.post('reading.save',{text:'中'.repeat(22000)}),/MESSAGE_TOO_LARGE/);
});
