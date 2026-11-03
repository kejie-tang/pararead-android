import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Source contracts only: these do not execute Android, a WebView, or JNI inference.
const source = path => readFile(new URL(`../${path}`, import.meta.url), 'utf8');
const kotlin = name => source(`app/src/main/java/org/pararead/android/${name}.kt`);
const section = (text, start, end) => {
  const begin = text.indexOf(start);
  const finish = text.indexOf(end, begin + start.length);
  assert.ok(begin >= 0 && finish > begin, `Missing source section: ${start}`);
  return text.slice(begin, finish);
};

test('Settings readingOnly defaults false independently of manual range and translation identity (source contract)', async () => {
  const settings = await kotlin('Settings');
  const option = section(settings, 'var readingOnly:', 'var translateRange:');
  assert.match(option, /getBoolean\("readingOnly", false\)/);
  assert.match(option, /putBoolean\("readingOnly", value\)\.apply\(\)/);
  assert.doesNotMatch(option, /translateRange/);
  assert.match(settings, /getString\("translateRange", "page"\)/);
  assert.doesNotMatch(settings.slice(settings.indexOf('fun config():')), /readingOnly/,
    'read-only preference must not affect runtime config, fingerprint or durable job identity');
});

test('ReaderHost freezes foreground preference and exports a boolean for both PDF and TXT (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  assert.match(host, /private val readingOnly = !backgroundTranslation && Settings\(context\)\.readingOnly/);
  const bootstrap = section(host, '"reader.ready" ->', '"session.opened" ->');
  assert.match(bootstrap, /\.put\("readingOnly", readingOnly\)/);
  assert.match(bootstrap, /\.put\("format", if \(source\.document\.isText\) "txt" else "pdf"\)/);
  assert.match(bootstrap, /if \(backgroundTranslation\) "manual" else Settings\(context\)\.translateRange/);
});

test('ReaderHost read-only bootstrap never matches, resumes, begins or rewrites a whole-job (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const opened = section(host, '"session.opened" ->', '"session.error" ->');
  assert.match(opened, /if \(!readingOnly\) jobMutex\.withLock \{/);
  const guard = opened.indexOf('if (!readingOnly) jobMutex.withLock');
  for (const call of ['wholeJob?.matches', 'beginWholeJob()', 'persistJob(wholeJob!!)', 'startWholeJob()']) {
    assert.ok(opened.indexOf(call) > guard, `${call} must be inside the guarded job bootstrap`);
  }
  assert.match(opened, /opened\.complete\(Unit\)/, 'read-only bootstrap must still acknowledge opening');
  const stop = section(host, 'suspend fun stopAndJoin(', 'suspend fun awaitProviderClosed()');
  assert.match(stop, /if \(!readingOnly && status != null\) wholeJob\?/,
    'even default cancellation of a read-only host must preserve its saved cursor');
});

test('ReaderHost rejects inference and translation restoration before payload parsing or IO (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  for (const method of ['restoreTranslations', 'translate']) {
    assert.match(host, new RegExp(`private suspend fun ${method}\\(m: Envelope\\) \\{\\s*require\\(!readingOnly\\) \\{ "READING_ONLY" \\}`));
  }
  assert.match(host, /"translation\.request" -> translate\(m\)/);
  assert.match(host, /"translation\.cacheGet" -> restoreTranslations\(m\)/);
  assert.match(host, /"job\.progress", "job\.finished" -> \{\s*require\(!readingOnly\) \{ "READING_ONLY" \}\s*jobMutex\.withLock/);
  for (const method of ['startWholeJob', 'beginWholeJob']) {
    assert.match(host, new RegExp(`private suspend fun ${method}\\(\\) \\{\\s*check\\(!readingOnly\\) \\{ "READING_ONLY" \\}`));
  }
  assert.match(host, /private suspend fun persistJob\(job: WholeDocumentJob\) = withContext\(NonCancellable\) \{\s*check\(!readingOnly\) \{ "READING_ONLY" \}/);
});

test('ReaderHost ignores translation and cancel commands with a localized disabled notice (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const command = section(host, 'fun command(type: String)', 'fun requestSave(');
  assert.match(command, /if \(readingOnly && type in setOf\("translateCurrentPage", "translateAll", "cancelTranslateAll"\)\) \{\s*report\(context\.withAppLanguage\(\)\.getString\(R\.string\.reader_reading_only_disabled\)\)\s*return@launch/);
  assert.ok(command.indexOf('return@launch') < command.indexOf('jobMutex.withLock'));
});

test('MainActivity persists reactive selection and drains the old provider before constructing a new host (source contract)', async () => {
  const main = await kotlin('MainActivity');
  assert.match(main, /private var readingOnly by mutableStateOf\(false\)/);
  assert.match(main, /readingOnly = settings\.readingOnly/);
  assert.match(main, /readingOnly = readingOnly, modeSwitching = modeSwitching,\s*onReadingOnlyChange = ::switchReadingOnly/);
  const toggle = section(main, 'private fun switchReadingOnly(', 'private fun reopen()');
  assert.match(toggle, /if \(modeSwitching \|\| enabled == readingOnly\) return/);
  assert.match(toggle, /catch \(_: TimeoutCancellationException\) \{\s*status = applicationContext\.withAppLanguage\(\)\.getString\(R\.string\.reader_mode_switch_failed\)/);
  assert.ok(toggle.indexOf('modeSwitching = true') < toggle.indexOf('lifecycleScope.launch'));
  const flush = toggle.indexOf('previous?.flushBeforeModeSwitch()');
  const current = toggle.indexOf('if (host !== previous) return@launch');
  const persist = toggle.indexOf('settings.readingOnly = enabled; readingOnly = enabled; reopen()');
  assert.ok(flush >= 0 && current > flush && persist > current, 'await saved state and verify current host before any preference/state/reopen change');
  const failure = toggle.slice(toggle.indexOf('catch (_: Exception)'));
  assert.match(failure, /reader_mode_switch_failed/);
  assert.match(failure, /finally \{ modeSwitching = false \}/);
  assert.doesNotMatch(failure, /settings\.|reopen\(|prepareToStop|stopAndJoin/);
  const reopen = section(main, 'private fun reopen()', 'private fun runImport(');
  assert.ok(reopen.indexOf('previous?.prepareToStop()') < reopen.indexOf('host = null'));
  assert.match(reopen, /previous\.stopAndJoin\(null\)/, 'mode switch preserves the recovery cursor');
  assert.match(reopen, /pending\?\.await\(\)/);
  const create = section(main, 'LaunchedEffect(document.id, readerVersion)', '}, update =');
  assert.ok(create.indexOf('readerRelease?.await()') < create.indexOf('AndroidView(factory'));
  assert.ok(create.indexOf('val config = runtime.config') < create.indexOf('if (readingOnly) MockTranslationProvider()'));
  assert.match(create, /if \(readingOnly\) MockTranslationProvider\(\) else when \(config\.provider\)/);
  assert.match(create, /ReaderHost\(this@MainActivity, file, db\.reader\(\), rs, config, provider/,
    'pass original translation identity even when the foreground provider is a mock');
  assert.doesNotMatch(reopen, /persistJob|dao\.|translateRange|readingOnly/);
});

test('ReaderToolbar selection disables only translation actions and retains PDF export and fullscreen (source contract)', async () => {
  const library = await kotlin('LibraryScreen');
  const toolbar = library.slice(library.indexOf('@Composable fun ReaderToolbar'));
  assert.match(toolbar, /FilterChip\(selected = readingOnly, onClick = \{ onReadingOnlyChange\(!readingOnly\) \}/);
  assert.match(toolbar, /testTag\("reader-reading-only"\)/);
  assert.match(toolbar, /FilterChip\(selected = readingOnly, onClick = \{ onReadingOnlyChange\(!readingOnly\) \}, enabled = !modeSwitching/);
  for (const [component, callback] of [['Button', 'onPage'], ['FilledTonalButton', 'onAll'], ['OutlinedButton', 'onCancel']]) {
    assert.match(toolbar, new RegExp(`\\b${component}\\(onClick = ${callback}, enabled = !readingOnly && !modeSwitching,`));
  }
  assert.match(toolbar, /if \(canExport\) OutlinedButton\(onClick = onExport, modifier = actionModifier/);
  assert.match(toolbar, /OutlinedButton\(onClick = onFullscreen, modifier = actionModifier/);
  assert.match(toolbar, /val actionShape = RoundedCornerShape\(12\.dp\)/);
  assert.match(toolbar, /val actionModifier = Modifier\.heightIn\(min = 48\.dp\)/);
  assert.match(toolbar, /leadingIcon = \{ AppIcon\(if \(readingOnly\) AppGlyph\.Check else AppGlyph\.Book/);
  assert.match(toolbar, /ButtonDefaults\.buttonColors\(containerColor = MaterialTheme\.colorScheme\.secondary, contentColor = MaterialTheme\.colorScheme\.onSecondary\)/);
  assert.match(toolbar, /Column\(Modifier\.fillMaxWidth\(\)[\s\S]*Text\(title, Modifier\.fillMaxWidth\(\)/);
  assert.match(toolbar, /FlowRow\(Modifier\.fillMaxWidth\(\), horizontalArrangement = Arrangement\.spacedBy\(8\.dp\), verticalArrangement = Arrangement\.spacedBy\(8\.dp\)/);
  for (const [locale, label] of [['values', 'Read only'], ['values-zh', '只阅读']]) {
    const strings = await source(`app/src/main/res/${locale}/strings.xml`);
    assert.ok(strings.includes(`<string name="reader_reading_only">${label}</string>`));
    assert.match(strings, /<string name="reader_reading_only_disabled">[^<]+<\/string>/);
    assert.match(strings, /<string name="reader_mode_switch_failed">[^<]+<\/string>/);
  }
});

test('ReaderHost awaits a bounded flush and resumes the same attached session on failure (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const flush = section(host, 'suspend fun flushBeforeModeSwitch()', '/** Stop bridge replies immediately');
  assert.match(flush, /if \(!ready \|\| pageCount == 0\) return@withContext/);
  assert.match(flush, /val flushId = UUID\.randomUUID\(\)\.toString\(\)/);
  assert.match(flush, /val pending = CompletableDeferred<Unit>\(\)/);
  assert.match(flush, /withTimeout\(90_000\) \{/);
  assert.match(flush, /put\("type", "flushBeforeModeSwitch"\)\.put\("flushId", flushId\)/);
  assert.ok(flush.indexOf('modeFlushId = flushId; modeFlush = pending') < flush.indexOf('send(base('));
  assert.ok(flush.indexOf('pending.await()') > flush.indexOf('send(base('));
  assert.match(flush, /autoSave\?\.cancel\(\); autoSave = null/);
  assert.ok(flush.indexOf('autoSave?.cancel()') < flush.indexOf('send(base('),
    'cancel the delayed native autosave before Web flush; already-sent saves are joined by the bridge');
  const failure = flush.slice(flush.indexOf('catch (e: Exception)'));
  assert.match(failure, /withContext\(NonCancellable\)/);
  assert.match(failure, /put\("type", "resumeAfterModeFlush"\)\.put\("flushId", flushId\)/);
  assert.match(failure, /throw e/);
  assert.match(failure, /finally[\s\S]*modeFlush = null; modeFlushId = null/);
  assert.doesNotMatch(flush, /prepareToStop\(|stopAndJoin\(|close\(|dao\.|persistJob\(|settings\./);
});

test('failed flush restores a previously active PDF autosave once, but not on navigation cancellation (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const flush = section(host, 'suspend fun flushBeforeModeSwitch()', '/** Stop bridge replies immediately');
  const capture = flush.indexOf('val hadPendingAutoSave = autoSave?.isActive == true');
  assert.ok(capture >= 0 && capture < flush.indexOf('autoSave?.cancel()'),
    'capture the old timer before cancellation, not the cancelled job state');
  const failure = section(flush, 'catch (e: Exception)', 'finally {');
  assert.match(failure, /hadPendingAutoSave && !source\.document\.isText && !closed && !stopping && !detached/);
  assert.match(failure, /\(e !is CancellationException \|\| e is TimeoutCancellationException\)/,
    'timeouts may restore autosave, but navigation/lifecycle cancellation must not');
  assert.match(failure, /autoSave\?\.isActive != true/,
    'keep any newly scheduled autosave instead of creating a second timer');
  assert.match(failure, /autoSave = scope\.launch \{ delay\(1200\); requestSave\(null\) \}/);
  assert.equal((failure.match(/requestSave\(null\)/g) ?? []).length, 1);
  assert.doesNotMatch(failure, /while\s*\(|retry\s*\(|flushBeforeModeSwitch\(/,
    'restore one old autosave, not an unbounded retry loop');
});

test('detach, close and stop cancel pending flush without waiting for its timeout (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  for (const [start, end] of [
    ['fun prepareToStop()', 'override fun close()'],
    ['override fun close()', 'private fun fatal('],
    ['suspend fun stopAndJoin(', 'suspend fun awaitProviderClosed()'],
  ]) {
    const lifecycle = section(host, start, end);
    assert.match(lifecycle, /modeFlush\?\.cancel\(CancellationException\("SESSION_STOPPING"\)\)/);
    assert.doesNotMatch(lifecycle, /pending\.await\(|modeFlush\?\.await\(/);
    if (start.includes('stopAndJoin')) {
      assert.ok(lifecycle.indexOf('modeFlush?.cancel(') < lifecycle.indexOf('jobMutex.withLock'),
        'navigation must release the toggle before waiting for durable job cleanup');
    }
  }
});

test('reader.flushed uses normal session fences and only acknowledges the pending ID (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  assert.match(host, /envelope\.sessionId == sessionId && envelope\.documentId == source\.document\.id && envelope\.documentRevision == source\.revision && envelope\.configRevision == configurationRevision/);
  const ack = section(host, '"reader.flushed" ->', '"session.error" ->');
  assert.match(ack, /pending != null && p\.optString\("flushId"\) == modeFlushId/);
  assert.match(ack, /if \(p\.getBoolean\("success"\)\) pending\.complete\(Unit\)/);
  assert.match(ack, /else pending\.completeExceptionally/);
  assert.doesNotMatch(ack, /dao\.|wholeJob|persistJob|readingOnly|prepareToStop|close\(/);
  const protocol = await source('core/src/main/kotlin/org/pararead/core/Protocol.kt');
  assert.match(protocol, /private val types = setOf\("reader.ready", "reader.flushed"/);
});

test('save staging is created before publishing any active transaction fields (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const start = section(host, '"document.save" ->', '"save.chunk" ->');
  const created = start.indexOf('val temp = File.createTempFile(');
  assert.ok(created >= 0);
  for (const assignment of ['expectedSaveLength = length', 'saveLength = 0', 'exportTemp = temp', 'saveId = id']) {
    assert.ok(start.indexOf(assignment) > created, `${assignment} must follow successful private staging creation`);
  }
  assert.match(start, /require\(saveId == null\) \{ "SAVE_BUSY" \}/);
});

test('matching save.chunk failures abort only their staging transaction, while wrong IDs leave it intact (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const chunk = section(host, '"save.chunk" ->', '"save.end" ->');
  assert.ok(chunk.indexOf('require(id == saveId)') < chunk.indexOf('try {'));
  for (const operation of ['p.getLong("offset")', 'Base64.decode(', 'require(bytes.size <= 24576', 'FileOutputStream(']) {
    assert.ok(chunk.indexOf(operation) > chunk.indexOf('try {') && chunk.indexOf(operation) < chunk.indexOf('catch (e: Exception)'),
      `${operation} must be covered by transaction cleanup`);
  }
  assert.match(chunk, /catch \(e: Exception\) \{ abortSave\(id\); throw e \}/);
  const abort = section(host, 'private fun abortSave(', 'private suspend fun finishSave(');
  assert.match(abort, /if \(saveId != id\) return/);
  assert.match(abort, /exportTemp = null; saveId = null; saveLength = 0; expectedSaveLength = 0; exportTarget = null/);
  assert.match(abort, /val temp = exportTemp[\s\S]*temp\?\.delete\(\)/);
  assert.doesNotMatch(abort, /dao\.|draft|cache|source\./, 'abort must not touch published drafts or translation caches');
});

test('matching invalid save.end clears staging in finally without aborting a different ID (source contract)', async () => {
  const host = await kotlin('ReaderHost');
  const end = section(host, 'private suspend fun finishSave(', 'private suspend fun metric(');
  assert.ok(end.indexOf('require(id == saveId)') < end.indexOf('try {'));
  for (const operation of ['m.payload.getLong("length")', 'val suffix = requireNotNull(exportTemp)']) {
    assert.ok(end.indexOf(operation) > end.indexOf('try {'));
  }
  assert.match(end, /finally \{ abortSave\(id\) \}/);
});

test('explicit library and background translation ignore the foreground-only preference (source contract)', async () => {
  const background = await kotlin('BackgroundTranslations');
  assert.match(background, /backgroundTranslation = true/);
  assert.doesNotMatch(background, /readingOnly/);
  const main = await kotlin('MainActivity');
  const enqueue = section(main, 'onTranslateAll = { doc ->', 'onCancelTranslation =');
  assert.match(enqueue, /BackgroundTranslations\.enqueue\(this@MainActivity, doc\.id\)/);
  assert.doesNotMatch(enqueue, /readingOnly/);
});
