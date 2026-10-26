import test from 'node:test';
import assert from 'node:assert/strict';
import { TranslationClient } from '../web/reader/src/upstream/translation-client.ts';
import vm from 'node:vm';
import { build } from 'esbuild';

const tick = () => new Promise(resolve => setImmediate(resolve));
function harness({range='manual', ensure, post} = {}) {
  const sent=[], extracted=[];
  const page = n => ({page:n,blocks:[{id:`p${n}-b0`,kind:'text',sentences:[{id:`p${n}-b0-s0`,page:n,blockId:`p${n}-b0`,text:`Page ${n}.`,rects:[]}]}]});
  globalThis.window = {paraRead:{evict(){}}};
  const client=new TranslationClient({app:{pdfViewer:{currentPageNumber:1,pagesCount:3},eventBus:{on(){}}},
    store:{onPageReady(){},ensure:async n=>{extracted.push(n);return ensure ? ensure(n,page(n)) : page(n);}},
    post:m=>{sent.push(m);return post?.(m);},range});
  const settle = (m, failed=false) => {
    if (!failed) client.handle({type:'translations',requestId:m.requestId,items:m.sentences.map(s=>({id:s.id,translation:'译文'}))});
    client.handle({type:'translationDone',requestId:m.requestId,failures:failed?m.sentences.map(s=>({id:s.id,message:'failure'})):[]});
  };
  return {client,sent,extracted,settle};
}

test('resume skips durable pages and waits for native checkpoint acknowledgement', async () => {
  let acknowledge;
  const h=harness({post:m=>{if(m.type==='translateAllProgress'&&m.done===2)return new Promise(r=>{acknowledge=r;});}});
  h.client.handle({type:'translateAll',jobId:10,startPage:2}); await tick();
  assert.deepEqual(h.extracted,[2]);
  h.settle(h.sent.find(m=>m.type==='translate')); await tick();
  assert.deepEqual(h.extracted,[2], 'Page 3 must wait for the page 2 Room commit');
  acknowledge(); await tick();
  assert.deepEqual(h.extracted,[2,3]);
  h.settle(h.sent.filter(m=>m.type==='translate')[1],true); await tick();
  assert.deepEqual(h.sent.filter(m=>m.type==='translateAllProgress').map(m=>[m.done,m.failed]),[[2,false],[3,true]]);
  assert.equal(h.sent.at(-1).status,'COMPLETED');
});

test('cancel during segmentation never submits or checkpoints the partial page', async () => {
  let finish;
  const h=harness({ensure:(_n,page)=>new Promise(r=>{finish=()=>r(page);})});
  h.client.handle({type:'translateAll',jobId:11}); await tick();
  h.client.handle({type:'cancelTranslateAll',jobId:11}); finish(); await tick();
  assert.equal(h.sent.filter(m=>m.type==='translate').length,0);
  assert.equal(h.sent.filter(m=>m.type==='translateAllProgress').length,0);
  assert.equal(h.sent.at(-1).status,'CANCELLED');
});

test('cancel during inference keeps the page cursor and stops subsequent pages', async () => {
  const h=harness(); h.client.handle({type:'translateAll',jobId:12}); await tick();
  const request=h.sent.find(m=>m.type==='translate');
  h.client.trim(100,0);
  assert.equal(h.client.state('p1-b0-s0').status,'pending');
  h.client.handle({type:'cancelTranslateAll',jobId:12});
  h.client.handle({type:'translationDone',requestId:request.requestId,failures:[],cancelled:true}); await tick();
  assert.deepEqual(h.extracted,[1]);
  assert.equal(h.sent.filter(m=>m.type==='translateAllProgress').length,0);
  assert.equal(h.sent.at(-1).status,'CANCELLED');
});

test('whole-document work waits for visible-page work before counting a page', async () => {
  const h=harness({range:'page'}); h.client.refresh(); await tick();
  const visible=h.sent[0]; h.client.handle({type:'translateAll',jobId:13}); await tick();
  assert.equal(h.sent.length,1);
  h.settle(visible); await tick();
  const progress=h.sent.find(m=>m.type==='translateAllProgress');
  assert.equal(progress.done,1); assert.equal(progress.failed,false);
  assert.equal(h.sent.filter(m=>m.type==='translate').length,2);
  h.client.handle({type:'cancelTranslateAll',jobId:13});
  h.settle(h.sent.filter(m=>m.type==='translate')[1]); await tick();
});

test('checkpoint or extraction errors stop the job at the last durable page', async () => {
  const h=harness({post:m=>{if(m.type==='translateAllProgress')return Promise.reject(new Error('DB unavailable'));}});
  h.client.handle({type:'translateAll',jobId:14}); await tick();
  h.settle(h.sent[0]); await tick();
  assert.deepEqual(h.extracted,[1]); assert.equal(h.sent.at(-1).status,'ERROR');
  const broken=harness({ensure:()=>Promise.reject(new Error('PDF page damaged'))});
  broken.client.handle({type:'translateAll',jobId:15,startPage:2}); await tick();
  assert.equal(broken.sent.at(-1).status,'ERROR');
  assert.equal(broken.sent.filter(m=>m.type==='translateAllProgress').length,0);
});

async function bridgeHarness() {
  const built=await build({entryPoints:[new URL('../web/reader/src/bridge.ts',import.meta.url).pathname],bundle:true,format:'iife',write:false});
  const sent=[],legacy=[];
  const window={innerHeight:800,addEventListener(){},postMessage(m){legacy.push(m);},ParaReadNative:{postMessage(s){sent.push(JSON.parse(s));}}};
  const context=vm.createContext({window,document:{documentElement:{style:{}},body:{style:{}}},location:{origin:'https://appassets.androidplatform.net'},
    TextEncoder,Uint8Array,Map,Set,AbortController,console,setTimeout(){return 1;},clearTimeout(){}});
  vm.runInContext(built.outputFiles[0].text,context);
  const session={version:1,sessionId:'session',documentId:'doc',documentRevision:'revision',configRevision:1,viewportEpoch:0};
  const deliver=(type,payload,requestId='command')=>window.ParaReadNative.onmessage({data:JSON.stringify({...session,type,payload,requestId})});
  deliver('session.open',{length:1000,token:'test',reading:{},settings:{}});
  return {sent,legacy,deliver,post:window.acquireVsCodeApi().postMessage};
}

test('bridge cancellation stops remaining chunks of a large job page', async () => {
  const h=await bridgeHarness();
  h.deliver('reader.command',{type:'translateAll',jobId:77});
  h.post({type:'translate',requestId:1,jobId:77,sentences:Array.from({length:81},(_,i)=>({id:`p1-b0-s${i}`,group:'p1-b0',text:`Sentence ${i}.`}))});
  const first=h.sent.find(m=>m.type==='translation.request');
  h.deliver('translation.finished',{failures:[],cancelled:false},first.requestId);
  h.deliver('reader.command',{type:'cancelTranslateAll'});
  await tick();
  assert.equal(h.sent.filter(m=>m.type==='translation.request').length,1);
  assert.equal(h.legacy.find(m=>m.type==='translationDone').cancelled,true);
});

test('bridge checkpoint promises require a successful durable native acknowledgement', async () => {
  const h=await bridgeHarness(); let saved=false;
  const pending=h.post({type:'translateAllProgress',jobId:78,done:1,total:3,failed:false}).then(()=>{saved=true;});
  await tick(); assert.equal(saved,false);
  h.deliver('job.saved',{status:'RUNNING'},h.sent.at(-1).requestId); await pending;
  assert.equal(saved,true);
  const stopped=h.post({type:'translateAllProgress',jobId:78,done:2,total:3,failed:false});
  h.deliver('job.saved',{status:'CANCELLED'},h.sent.at(-1).requestId);
  await assert.rejects(stopped,/JOB_STOPPED/);
  const failed=h.post({type:'translateAllProgress',jobId:78,done:2,total:3,failed:false});
  h.deliver('protocol.error',{code:'DATABASE_ERROR'},h.sent.at(-1).requestId);
  await assert.rejects(failed,/DATABASE_ERROR/);
});
