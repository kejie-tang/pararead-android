import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const tick=()=>new Promise(r=>setImmediate(r));
const bundle=build({entryPoints:[fileURLToPath(new URL('../web/reader/src/bridge.ts',import.meta.url))],bundle:true,format:'iife',write:false});
async function harness(){
  const sent=[],body={style:{},inert:false};let resets=0;
  const pdf={pdfViewer:{_layerProperties:{annotationEditorUIManager:{endCurrentEditing(){}}}},
    pdfDocument:{annotationStorage:{size:1,serializable:{hash:'current'},resetModified(){resets++;}},saveDocument:async()=>new Uint8Array([1,2,3])}};
  const window={innerHeight:800,addEventListener(){},postMessage(){},PDFViewerApplication:pdf,ParaReadNative:{postMessage:raw=>sent.push(JSON.parse(raw))}};
  vm.runInNewContext((await bundle).outputFiles[0].text,{window,document:{body,documentElement:{style:{}}},location:{origin:'https://reader.test'},
    TextEncoder,Uint8Array,Map,Set,AbortController,console,btoa:globalThis.btoa,setTimeout:()=>1,clearTimeout(){}});
  const session={version:1,sessionId:'s',documentId:'d',documentRevision:'r',configRevision:1,viewportEpoch:0};
  const deliver=(type,payload,requestId='open')=>window.ParaReadNative.onmessage({data:JSON.stringify({...session,type,payload,requestId})});
  deliver('session.open',{format:'pdf',settings:{readingOnly:true},reading:{},length:100,token:'t'});
  window.paraRead.setReadingStateFlusher(()=>({page:2,zoom:'1.25',rotation:90,location:'page=2&zoom=125'}));
  const command=(type,flushId)=>deliver('reader.command',{type,flushId});
  const last=type=>sent.filter(m=>m.type===type).at(-1);
  const reply=(m,type,payload={})=>deliver(type,payload,m.requestId);
  async function finishSave(start=last('document.save')){
    reply(start,'save.started',{saveId:'save'});await tick();
    reply(last('save.chunk'),'save.progress');await tick();
    assert.equal(last('save.end').payload.length,3);
    reply(last('save.end'),'save.finished');await tick();
  }
  return{window,body,pdf,sent,command,last,reply,finishSave,resets:()=>resets};
}

test('mode flush requires reading.saved and complete annotation save.finished before success',async()=>{
  const h=await harness();h.command('flushBeforeModeSwitch','one');await tick();
  assert.equal(h.body.inert,true);assert.equal(h.last('document.save'),undefined);
  const reading=h.last('reading.save');assert.equal(reading.payload.page,2);assert.equal(reading.payload.rotation,90);
  assert.equal(h.last('reader.flushed'),undefined);
  h.reply(reading,'reading.saved');await tick();
  const start=h.last('document.save');assert.equal(start.payload.incrementalLength,3);
  assert.equal(h.last('reader.flushed'),undefined);
  await h.finishSave(start);
  assert.equal(h.last('reader.flushed').payload.success,true);assert.equal(h.resets(),1);
  assert.equal(h.body.inert,true,'remain frozen until host replaces or explicitly resumes view');
  h.command('resumeAfterModeFlush','one');assert.equal(h.body.inert,false);
});

test('mode flush failure leaves the view editable and never reports success',async()=>{
  const h=await harness();h.command('flushBeforeModeSwitch','read-fail');await tick();
  h.reply(h.last('reading.save'),'protocol.error',{code:'READING_DISK_FULL'});await tick();
  assert.equal(h.last('reader.flushed').payload.success,false);assert.equal(h.body.inert,false);
  assert.equal(h.last('document.save'),undefined);assert.equal(h.resets(),0);
  h.command('flushBeforeModeSwitch','save-fail');await tick();
  h.reply(h.last('reading.save'),'reading.saved');await tick();
  h.reply(h.last('document.save'),'protocol.error',{code:'SAVE_DISK_FULL'});await tick();
  assert.equal(h.last('reader.flushed').payload.success,false);assert.match(h.last('reader.flushed').payload.message,/SAVE_DISK_FULL/);
  assert.equal(h.body.inert,false);assert.equal(h.resets(),0,'failed writes must not clear modified annotations');
  assert.equal(h.pdf.pdfDocument.annotationStorage.size,1);
});

test('mode flush cancellation ignores late acknowledgement and preserves earlier inert state',async()=>{
  const h=await harness();h.body.inert=true;
  h.command('flushBeforeModeSwitch','late');await tick();const reading=h.last('reading.save');
  h.command('resumeAfterModeFlush','late');assert.equal(h.body.inert,true);
  h.reply(reading,'reading.saved');await tick();
  assert.equal(h.last('reader.flushed'),undefined);assert.equal(h.last('document.save'),undefined);
});

test('mode flush joins an already active autosave and waits for its queued final save',async()=>{
  const h=await harness();const saving=h.window.paraRead.save();await tick();const first=h.last('document.save');
  h.pdf.pdfDocument.annotationStorage.serializable.hash = 'newer-edit';
  h.command('flushBeforeModeSwitch','concurrent');await tick();
  h.reply(h.last('reading.save'),'reading.saved');await tick();
  await h.finishSave(first);
  assert.equal(h.sent.filter(m=>m.type==='document.save').length,2,'flush must join and drain the queued latest save');
  assert.equal(h.last('reader.flushed'),undefined,'first autosave completion is not the flush barrier');
  await h.finishSave();await saving;
  assert.equal(h.last('reader.flushed').payload.success,true);assert.equal(h.resets(),1, 'only the final up-to-date save may clear modified state');
});

test('mode flush does not rewrite an already acknowledged clean PDF draft or its revision',async()=>{
  const h=await harness();const saving=h.window.paraRead.save();await tick();
  await h.finishSave();await saving;
  h.command('flushBeforeModeSwitch','clean');await tick();
  h.reply(h.last('reading.save'),'reading.saved');await tick();
  assert.equal(h.sent.filter(m=>m.type==='document.save').length,1,'no new draft/revision for unchanged annotations');
  assert.equal(h.last('reader.flushed').payload.success,true);
});
