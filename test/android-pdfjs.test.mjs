import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';
import { patchWorker, sparseStore } from '../scripts/patch_pdfjs.mjs';

test('sparse store allocates by fetched chunks, supports boundaries and eviction', () => {
  class MissingDataException extends Error {}
  const Store = vm.runInNewContext(`${sparseStore}; AndroidSparseBytes`, { MissingDataException, Uint8Array, Map, Math, Error });
  const owner = { _loadedChunks: new Set(), _lastSuccessfulEnsureByteChunk: 1 };
  const store = new Store(500 * 1024 * 1024, 262144, owner);
  assert.equal(store.chunks.size, 0);
  assert.throws(() => store.buffer);
  store.set(new Uint8Array(262144).fill(7), 0); owner._loadedChunks.add(0);
  store.set(new Uint8Array(262144).fill(9), 262144); owner._loadedChunks.add(1);
  assert.deepEqual([...store.subarray(262142, 262146)], [7, 7, 9, 9]);
  store.limit = 2; store.set(new Uint8Array(262144), 524288);
  assert.equal(store.chunks.size, 2); assert.equal(owner._loadedChunks.has(0), false);
  assert.throws(() => store.byte(0), MissingDataException);
  assert.throws(() => store.subarray(0, 17000000), /BUDGET/);
});
test('patch is pinned and eliminates whole source copies in reader/save', async () => {
  const original = await readFile(new URL('../.references/pararead/assets/pdf.js/build/pdf.worker.mjs', import.meta.url), 'utf8');
  const patched = patchWorker(original);
  assert.ok(!patched.includes('super(new Uint8Array(length), 0, length, null);'));
  assert.ok(patched.includes('suffixOnly: true'));
  assert.ok(patched.includes('FULL_DOCUMENT_READ_DISABLED'));
  assert.throws(() => patchWorker(patched), /drift/);
});

test('saving one editor annotation in a 16320-page book visits only its edited page', async () => {
  const original = await readFile(new URL('../.references/pararead/assets/pdf.js/build/pdf.worker.mjs', import.meta.url), 'utf8');
  const source = patchWorker(original);
  const start = source.indexOf('handler.on("SaveDocument"');
  const end = source.indexOf('handler.on("GetOperatorList"',start);
  let save, visits=0, suffixOnly=false;
  const xref={trailer:{getRaw(){return null;},get(){return null;}},getNewTemporaryRef(){return {};},resetNewTemporaryRef(){}};
  const context = {
    handler:{on(name,fn){save=fn;}},
    pdfManager:{pdfDocument:{stream:{end:100,bytes:{length:100}}},evaluatorOptions:{isOffscreenCanvasSupported:false},ensure:async()=>null,ensureCatalog:async()=>null,
      ensureDoc:async name=>name==='xref'?xref:0,getPage:async()=>{visits++;return {saveNewAnnotations:async(a,b,c,d,changes)=>{changes.size++;},save:async(a,b,c,changes)=>{changes.size++;}};}},
    RefSetCache:class {size=0;values(){return [];}},
    getNewAnnotationsMap(storage){return [...storage.keys()].some(k=>k.startsWith('pdfjs_internal_editor_'))?new Map([[100,[]]]):null;},
    AnnotationEditorPrefix:'pdfjs_internal_editor_',StructTreeRoot:{canCreateStructureTree:async()=>false},
    AnnotationFactory:{generateImages(){}},WorkerTask:class{},startWorkerTask(){},finishWorkerTask(){},
    isDict:()=>false,Dict:class{},incrementalUpdate:options=>{suffixOnly=options.suffixOnly;return Promise.resolve(new Uint8Array([1]));},
  };
  vm.runInNewContext(source.slice(start,end),context);
  await save({isPureXfa:false,numPages:16320,annotationStorage:new Map([['pdfjs_internal_editor_1',{pageIndex:100}]])});
  assert.equal(visits,1); assert.equal(suffixOnly,true);
  visits=0;
  await save({isPureXfa:false,numPages:4,annotationStorage:new Map([['widget-id',{value:'form'}]])});
  assert.equal(visits,4,'small form documents keep the upstream widget save path');
  visits=0;
  await assert.rejects(save({isPureXfa:false,numPages:513,annotationStorage:new Map([['widget-id',{value:'form'}]])}),/LARGE_FORM_SAVE_NOT_SUPPORTED/);
  assert.equal(visits,0,'oversize widget save fails before unbounded page traversal');
});
