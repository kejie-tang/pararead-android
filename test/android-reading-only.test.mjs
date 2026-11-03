import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
import {TranslationClient} from '../web/reader/src/upstream/translation-client.ts';

const tick=()=>new Promise(resolve=>setImmediate(resolve));
const sentence={id:'p1-b0-s0',page:1,blockId:'p1-b0',text:'An original sentence.',rects:[]};
const page={page:1,blocks:[{id:'p1-b0',kind:'text',sentences:[sentence]}]};
for(const range of ['page','nearby','manual']) test(`read-only client blocks automatic, explicit, retry and whole-job translation (${range})`,async()=>{
  const sent=[],ensured=[],restored=[]; let onReady,onTurn;
  const client=new TranslationClient({
    app:{pageCount:()=>2,currentPage:()=>1,onPageChanging:listener=>{onTurn=listener;}},
    store:{onPageReady:listener=>{onReady=listener;},ensure:async n=>{ensured.push(n);return page;}},
    post:m=>sent.push(m),range,readingOnly:true,
    restoreCached:async sentences=>{restored.push(sentences);return[{id:sentence.id,translation:'A cached translation.'}];},
  });
  client.refresh();onTurn(2);onReady(page);
  await client.translatePage(1);await client.translateBlockOf(sentence);
  for(const message of [
    {type:'translateCurrentPage'}, {type:'translateAll',jobId:42,startPage:1}, {type:'retryFailed'},
    {type:'settings',translateRange:'nearby',readingOnly:false},
    {type:'translations',requestId:1,items:[{id:sentence.id,translation:'Late translation'}]},
    {type:'translationDraft',requestId:1,items:[{id:sentence.id,translation:'Draft'}]},
  ]) assert.equal(client.handle(message),true);
  await tick();
  assert.deepEqual(ensured,[],'read-only PDF needs no segmentation for translation');
  assert.deepEqual(restored,[],'read-only does not restore a hidden translated result');
  assert.deepEqual(sent,[],'no inference or durable job mutations');
  assert.equal(client.state(sentence.id).status,'none');
  client.reset();
});

test('manual translation mode remains distinct from read-only: cached translations still restore',async()=>{
  const restored=[],sent=[];
  const client=new TranslationClient({app:{pageCount:()=>1,currentPage:()=>1},
    store:{onPageReady(){},ensure:async()=>page},range:'manual',readingOnly:false,post:m=>sent.push(m),
    restoreCached:async()=>{restored.push(1);return[{id:sentence.id,translation:'缓存译文'}];}});
  client.refresh();await tick();
  assert.equal(restored.length,1);assert.equal(client.state(sentence.id).translation,'缓存译文');
  assert.deepEqual(sent,[]);client.reset();
});

const bridgeBundle=build({entryPoints:[fileURLToPath(new URL('../web/reader/src/bridge.ts',import.meta.url))],bundle:true,format:'iife',write:false});
async function bridgeHarness(backgroundTranslation=false){
  const sent=[],legacy=[];
  const window={innerHeight:800,addEventListener(){},postMessage:m=>legacy.push(m),ParaReadNative:{postMessage:s=>sent.push(JSON.parse(s))}};
  vm.runInNewContext((await bridgeBundle).outputFiles[0].text,{window,document:{documentElement:{style:{}},body:{style:{}}},
    location:{origin:'https://appassets.androidplatform.net'},TextEncoder,Uint8Array,Map,Set,AbortController,console,setTimeout:()=>1,clearTimeout(){}});
  const session={version:1,sessionId:'s',documentId:'d',documentRevision:'r',configRevision:1,viewportEpoch:0};
  const deliver=(type,payload,requestId='open')=>window.ParaReadNative.onmessage({data:JSON.stringify({...session,type,payload,requestId})});
  deliver('session.open',{token:'t',length:1000,reading:{},settings:{readingOnly:true,backgroundTranslation}});
  return{sent,legacy,window,deliver,post:window.acquireVsCodeApi().postMessage};
}

test('bridge read-only policy blocks inference, cache lookup and job writes, including attempted live bypass',async()=>{
  const h=await bridgeHarness();
  const request={type:'translate',requestId:1,priority:0,sentences:[{id:sentence.id,text:sentence.text,group:sentence.blockId}]};
  h.post(request);assert.deepEqual(Array.from(await h.window.paraRead.cachedTranslations([sentence])),[]);
  h.deliver('reader.command',{type:'settings',readingOnly:false,translateRange:'nearby'});
  h.post({...request,requestId:2});
  h.deliver('reader.command',{type:'translateAll',jobId:55});
  await assert.rejects(h.post({type:'translateAllProgress',jobId:55,done:1,total:2,failed:false}),/READING_ONLY/);
  await assert.rejects(h.post({type:'translateAllDone',jobId:55,total:2,status:'COMPLETED'}),/READING_ONLY/);
  assert.equal(h.sent.some(m=>['translation.request','translation.cacheGet','job.progress','job.finished'].includes(m.type)),false);
  assert.equal(h.legacy.filter(m=>m.type==='translationDone'&&m.cancelled).length,2);
  assert.equal(h.legacy.some(m=>m.type==='translateAll'),false);
  assert.equal((await h.window.paraRead.ready).settings.readingOnly,true);
});

test('explicit background translation is independent of the foreground read-only preference',async()=>{
  const h=await bridgeHarness(true);
  h.post({type:'translate',requestId:1,priority:3,jobId:8,sentences:[{id:sentence.id,text:sentence.text,group:sentence.blockId}]});
  const request=h.sent.find(m=>m.type==='translation.request');assert.ok(request);
  assert.equal((await h.window.paraRead.ready).settings.readingOnly,false);
  h.deliver('translation.finished',{failures:[]},request.requestId);await tick();
});
