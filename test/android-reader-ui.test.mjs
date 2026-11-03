import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const source=path=>readFile(new URL('../'+path,import.meta.url),'utf8');

test('reader controls use shared light/dark palette, rounded touch targets and explicit focus states',async()=>{
  const css=await source('web/reader/text.css');
  const android=await source('web/reader/android.css');
  assert.match(android,/--reader-accent:\s*#4668d7/);
  assert.match(android,/@media\s*\(prefers-color-scheme:\s*dark\)/);
  assert.match(css,/\.textToolbar button,[^{]+\{[^}]*min-height:\s*44px/);
  assert.match(css,/\.textToolbar button,[^{]+\{[^}]*border-radius:\s*12px/);
  assert.match(css,/\.textToolbar \.textTranslate\s*\{[^}]*background:\s*var\(--reader-accent\)/);
  assert.match(css,/:focus-visible[^}]*outline:\s*2px solid var\(--reader-accent\)/);
  assert.match(css,/:disabled\s*\{[^}]*cursor:\s*default/);
  assert.match(css,/@media\s*\(prefers-reduced-motion:\s*reduce\)/);
  assert.match(css,/@media\s*\(forced-colors:\s*active\)/);
  assert.match(css,/\.textSourceToggle\s*\{[^}]*min-height:\s*44px/);
});

test('TXT toolbar grouping preserves accessible native controls and existing mode guards',async()=>{
  const ts=await source('web/reader/src/text-reader.ts');
  assert.match(ts,/navigation\.className = "textNavigation"/);
  assert.match(ts,/preferences\.className = "textPreferences"/);
  assert.match(ts,/control\.setAttribute\("aria-label", text\)/);
  assert.match(ts,/sourceLabel\.hidden = readingOnly/);
  assert.match(ts,/translateButton\.hidden = readingOnly/);
  assert.match(ts,/this\.#toolbar\.append\(navigation, preferences\)/);
});
