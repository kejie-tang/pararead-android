import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Source contracts complement JVM/desktop tests; they do not execute Android framework code.
test('native background persistence keeps cancellation, ownership and cross-connection safeguards', async () => {
  const root = new URL('../app/src/main/java/org/pararead/android/', import.meta.url);
  const host = await readFile(new URL('ReaderHost.kt', root), 'utf8');
  const database = await readFile(new URL('Database.kt', root), 'utf8');
  const worker = await readFile(new URL('BackgroundTranslations.kt', root), 'utf8');
  assert.match(host, /private suspend fun persistJob\(job: WholeDocumentJob\) = withContext\(NonCancellable\)/);
  assert.match(host, /dao\.saveJobIfCurrent/);
  assert.match(host, /send\(m\.reply\("page\.saved"/);
  const cancel = host.match(/"request\.cancel"\s*->[^\n]*/)?.[0];
  assert.ok(cancel); assert.match(cancel, /translationQueue\.cancel/);
  assert.doesNotMatch(cancel, /pageChunks\.clear/);
  assert.match(database, /enableMultiInstanceInvalidation\(\)/);
  assert.match(database, /json = :expected AND EXISTS/);
  assert.match(host, /checkpointCachePolicy\(job\.nextPage, persistedJobJson\)/);
  assert.match(host, /previous\?\.takeIf \{ hasCurrentCachePolicy\(\) \}/);
  assert.match(worker, /settings\.runtimeSnapshot\(\)/);
  const provider = worker.slice(worker.indexOf('private fun createProvider'), worker.indexOf('private fun data'));
  assert.match(provider, /runtime\.modelPath/);
  assert.doesNotMatch(provider, /settings\.(modelPath|baseUrl|cpuThreads|inferenceBackend|apiKey)/);
});
