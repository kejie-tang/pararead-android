import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Native source contracts only; these do not execute Android or a WebView.
const kotlin = name => readFile(new URL(`../app/src/main/java/org/pararead/android/${name}.kt`, import.meta.url), 'utf8');
const section = (text, start, end) => {
  const begin = text.indexOf(start);
  const finish = text.indexOf(end, begin + start.length);
  assert.ok(begin >= 0 && finish > begin, `Missing source section: ${start}`);
  return text.slice(begin, finish);
};

test('LibraryScreen AppShell hides the wide rail for any selected reader, not only fullscreen (source contract)', async () => {
  const main = await kotlin('MainActivity');
  const shell = section(await kotlin('LibraryScreen'), '@Composable fun AppShell(', '@Composable private fun NavigationItem(');
  assert.match(main, /AppShell\(selected != null, modelState,/);
  assert.match(shell, /val wide = maxWidth >= 840\.dp/);
  assert.match(shell, /if \(wide && !reading && !fullscreen\) Surface\(/);
  for (const destination of ['library', 'models', 'settings']) {
    assert.match(shell, new RegExp(`NavigationItem\\(text\\.getString\\(R\\.string\\.${destination}\\)`));
  }
  // Keep the existing narrow-layout navigation and its safe Home callback.
  assert.match(shell, /if \(!wide && !fullscreen\) Row\(/);
  assert.match(main, /onHome = ::leaveReader/);
});

test('MainActivity has no fullscreen exit overlay and Back first leaves fullscreen then the reader (source contract)', async () => {
  const main = await kotlin('MainActivity');
  assert.doesNotMatch(main, /reader-exit-fullscreen|SmallFloatingActionButton|AppGlyph\.Close|reader_exit_fullscreen/);
  const back = section(main, 'val fullscreen = selected != null && readerFullscreen', 'DisposableEffect(fullscreen)');
  assert.match(back, /readerFullscreen = it; settings\.readerFullscreen = it/);
  assert.match(back, /BackHandler\(enabled = selected != null && !showSettings && !showModelLibrary && !showModelCatalog && !speedState\.visible\)/);
  assert.match(back, /if \(fullscreen\) setFullscreen\(false\) else leaveReader\(\)/);
  assert.doesNotMatch(back, /isText|destroy\(|host = null/);
});

test('MainActivity leaveReader awaits the reading/annotation ack and fences host AND document before detach (source contract)', async () => {
  const main = await kotlin('MainActivity');
  const leave = section(main, 'private fun leaveReader()', 'private fun switchReadingOnly(');
  const guard = leave.indexOf('if (modeSwitching) return');
  const capture = leave.indexOf('val document = selected ?: return');
  const busy = leave.indexOf('modeSwitching = true');
  const launch = leave.indexOf('lifecycleScope.launch');
  const flush = leave.indexOf('previous?.flushBeforeModeSwitch()');
  const fence = leave.indexOf('if (host !== previous || selected !== document) return@launch');
  const detach = leave.indexOf('reopen(); selected = null');
  assert.ok(guard >= 0 && capture > guard && busy > capture && launch > busy);
  assert.ok(flush > launch && fence > flush && detach > fence,
    'reading.saved and annotation save acknowledgement must finish before either host or selected is cleared');
  assert.match(leave, /val previous = host/);
  assert.match(leave, /finally \{ modeSwitching = false \}/);
  const toggle = section(main, 'private fun switchReadingOnly(', 'private fun reopen()');
  assert.match(toggle, /if \(modeSwitching \|\| enabled == readingOnly\) return/);
  assert.match(main, /readingOnly = readingOnly, modeSwitching = modeSwitching/);
});

test('MainActivity leaveReader failure stays attached, cancellation propagates, and retry is allowed (source contract)', async () => {
  const leave = section(await kotlin('MainActivity'), 'private fun leaveReader()', 'private fun switchReadingOnly(');
  const failure = leave.slice(leave.indexOf('catch (_: TimeoutCancellationException)'));
  assert.match(failure, /catch \(_: TimeoutCancellationException\)/);
  assert.match(failure, /catch \(e: CancellationException\) \{ throw e \}/);
  assert.match(failure, /catch \(_: Exception\)/);
  assert.equal((failure.match(/if \(host === previous && selected === document\)/g) ?? []).length, 2,
    'stale failures must not overwrite status for a replacement reader');
  assert.equal((failure.match(/R\.string\.reader_exit_save_failed/g) ?? []).length, 2);
  assert.match(failure, /finally \{ modeSwitching = false \}/);
  assert.doesNotMatch(failure, /reopen\(|selected\s*=(?!=)|host\s*=\s*null|prepareToStop|stopAndJoin|destroy\(|readingOnly\s*=(?!=)/);
  for (const locale of ['values', 'values-zh']) {
    const strings = await readFile(new URL(`../app/src/main/res/${locale}/strings.xml`, import.meta.url), 'utf8');
    assert.match(strings, /<string name="reader_exit_save_failed">[^<]+<\/string>/);
  }
});

test('TXT split immediateBack regression is covered through persisted state and library reopen (source contract)', async () => {
  const ui = await readFile(new URL('../app/src/androidTest/java/org/pararead/android/ReaderRecoveryUiTest.kt', import.meta.url), 'utf8');
  const regression = section(ui, '@Test fun txtSplitRatioSurvivesImmediateBack()', '@Test fun failedExitFlushKeepsReaderAttachedAndAllowsRetry()');
  assert.match(regression, /new KeyboardEvent\('keydown',\{key:'End',bubbles:true\}\)/);
  assert.match(regression, /delay === 250 \? 60000 : delay/,
    'hold the debounce so the test cannot accidentally pass via a delayed reading.save');
  assert.match(regression, /evaluateJavascript\([\s\S]*onBackPressedDispatcher\.onBackPressed\(\)/);
  assert.match(regression, /getJSONObject\("txt"\)\.getDouble\("columnRatio"\), 0\.001/);
  assert.match(regression, /"continue-reading"/);
  assert.match(regression, /aria-valuenow.*75/);
});

test('MainActivity reader exit retains prepare-before-detach and host drain barriers (source contract)', async () => {
  const main = await kotlin('MainActivity');
  const reopen = section(main, 'private fun reopen()', 'private fun runImport(');
  const prepare = reopen.indexOf('previous?.prepareToStop()');
  assert.ok(prepare >= 0 && prepare < reopen.indexOf('host = null; readerScope = null'));
  assert.match(reopen, /cleanupScope\.async\(start = CoroutineStart\.UNDISPATCHED\)/);
  assert.match(reopen, /try \{ pending\?\.await\(\) \}\s*finally/);
  assert.match(reopen, /previous\.stopAndJoin\(null\)/);
  const create = section(main, 'LaunchedEffect(document.id, readerVersion)', '}, update =');
  assert.ok(create.indexOf('readerRelease?.await()') >= 0);
  assert.ok(create.indexOf('readerRelease?.await()') < create.indexOf('AndroidView(factory'));
  assert.match(main, /readerRelease\?\.await\(\)\s*withContext\(NonCancellable\)/);
});

test('MainActivity keeps reader toolbar commands and PDF-only export policy (source contract)', async () => {
  const main = await kotlin('MainActivity');
  const toolbar = section(main, 'if (document != null && !fullscreen) ReaderToolbar', 'if (document != null && !fullscreen && !readingOnly');
  for (const command of ['translateCurrentPage', 'translateAll', 'cancelTranslateAll']) {
    assert.ok(toolbar.includes(`host?.command("${command}")`));
  }
  assert.match(toolbar, /canExport = !document\.isText/);
  assert.match(toolbar, /onReadingOnlyChange = ::switchReadingOnly/);
  assert.match(toolbar, /onFullscreen = \{ setFullscreen\(true\) \}/);
  assert.match(main, /update = \{ host\?\.setFullscreen\(fullscreen, showControlsLabel\) \}/);
});
