/* SPDX-License-Identifier: Apache-2.0 */
// Standalone panel checks; does not build generated assets or import text-reader.ts.
// Run: node --test scripts/test_text_navigation.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
import {chromium} from '@playwright/test';

const source = new URL('../web/reader/src/text-navigation.ts', import.meta.url);
const esm = await build({entryPoints:[fileURLToPath(source)], bundle:true, write:false, format:'esm', target:'es2022'});
const {pageToPercent, percentToPage} = await import(`data:text/javascript;base64,${Buffer.from(esm.outputFiles[0].text).toString('base64')}`);

test('text-navigation: percent endpoints, nearest pages, clamping and one-page safety', () => {
  assert.equal(pageToPercent(1, 101), 0);
  assert.equal(pageToPercent(101, 101), 100);
  assert.equal(pageToPercent(51, 101), 50);
  assert.equal(percentToPage(0, 101), 1);
  assert.equal(percentToPage(100, 101), 101);
  assert.equal(percentToPage(50, 101), 51);
  assert.equal(percentToPage(50, 4), 3);
  assert.equal(percentToPage(-1, 101), 1);
  assert.equal(percentToPage(101, 101), 101);
  assert.equal(pageToPercent(999, 101), 100);
  assert.equal(pageToPercent(-1, 101), 0);
  for (const value of [0, 1, 100, NaN, Infinity, -Infinity]) {
    assert.equal(pageToPercent(value, 1), 0);
    assert.equal(percentToPage(value, 1), 1);
    assert.equal(percentToPage(value, 0), 1);
  }
  assert.equal(percentToPage(NaN, 101), 1);
  assert.equal(pageToPercent(NaN, 101), 0);
  for (const pages of [2, 3, 101, 10001]) {
    for (const page of [1, 2, Math.ceil(pages / 2), pages]) {
      assert.equal(percentToPage(pageToPercent(page, pages), pages), page);
    }
  }
});

test('text-navigation: native modal, explicit commits, batch/retry, disposal and responsive controls', {timeout:60000}, async () => {
  const bundle = await build({entryPoints:[fileURLToPath(source)], bundle:true, write:false, format:'iife', globalName:'TextNavigation', target:'es2022'});
  const css = await readFile(new URL('../web/reader/text-navigation.css', import.meta.url), 'utf8');
  const tokens = await readFile(new URL('../web/reader/android.css', import.meta.url), 'utf8');
  const browser = await chromium.launch({channel:process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined), headless:true});
  try {
    const page = await browser.newPage({viewport:{width:320, height:800}});
    await page.setContent('<!doctype html><html><head><meta charset="UTF-8"></head><body><button id="opener">Open</button><main id="root"></main></body></html>');
    await page.addStyleTag({content:tokens + '\n' + css});
    await page.addScriptTag({content:bundle.outputFiles[0].text});
    await page.evaluate(() => {
      window.commits = []; window.loads = 0; window.keys = 0;
      window.addEventListener('keydown', () => { window.keys++; });
      const original = [{title:'<img src=x onerror=alert(1)>', page:1}, {title:'Middle', page:51}];
      window.panel = new TextNavigation.TextNavigationPanel(document.querySelector('#root'), {
        language:'en', pages:101, chapters:original,
        go:page => window.commits.push(page),
        moreChapters:async () => {
          window.loads++;
          if (window.loads === 1) throw new Error('offline');
          if (window.loads === 3) return new Promise(resolve => { window.finishLoad = resolve; });
          panel.addChapters([{title:'Middle', page:51}, ...Array.from({length:100}, (_, i) => ({title:`Chapter ${i}`,page:i+1}))], true);
        },
      });
      original[0].title = 'mutated';
      panel.addChapters([], true);
      document.querySelector('#opener').focus();
      panel.open(51);
    });
    const dialog = page.getByRole('dialog', {name:'Chapters & progress'});
    const range = page.getByRole('slider', {name:'Position (%)'});
    const number = page.getByRole('spinbutton', {name:'Position (%)'});
    assert.equal(await dialog.evaluate(e => e.matches(':modal')), true);
    assert.equal(await page.locator('[aria-current]').textContent(), 'MiddlePage 51');
    assert.equal(await page.locator('dialog img').count(), 0);
    assert.ok((await page.locator('.textNavigationPanelChapter').first().textContent()).startsWith('<img'));
    await page.waitForTimeout(100);
    assert.equal(await page.evaluate(() => loads), 0, 'open does not prefetch');
    await range.fill('75');
    assert.equal(await page.locator('output').textContent(), 'Page 76 of 101');
    assert.deepEqual(await page.evaluate(() => commits), [], 'slider only previews');
    await page.getByRole('button', {name:'Go',exact:true}).click();
    assert.deepEqual(await page.evaluate(() => commits), [76]);
    assert.equal(await dialog.count(), 0, 'Go closes native modal');
    await page.evaluate(() => panel.open(51));
    await number.fill('101');
    assert.equal(await number.getAttribute('aria-invalid'), 'true');
    assert.equal(await page.getByRole('button', {name:'Go',exact:true}).isDisabled(), true);
    await number.fill('0');
    await page.getByRole('button', {name:'Load more chapters',exact:true}).click();
    await page.getByRole('button', {name:'Retry loading chapters',exact:true}).waitFor();
    assert.equal(await page.evaluate(() => loads), 1);
    await page.getByRole('button', {name:'Retry loading chapters',exact:true}).click();
    await page.waitForFunction(() => document.querySelectorAll('[data-chapter]').length === 102);
    assert.equal(await page.evaluate(() => loads), 2, 'one batch per activation; duplicate chapter ignored');
    await page.locator('.textNavigationPanelList').evaluate(e => { e.scrollTop = e.scrollHeight; });
    await page.waitForFunction(() => loads === 3);
    await page.locator('.textNavigationPanelList').dispatchEvent('scroll');
    assert.equal(await page.evaluate(() => loads), 3, 'only one batch in flight');
    await page.evaluate(() => { panel.addChapters([], false); finishLoad(); });
    await page.waitForFunction(() => document.querySelector('.textNavigationPanelLoad').hidden);
    for (const scheme of ['light','dark']) {
      await page.emulateMedia({colorScheme:scheme});
      await page.evaluate(() => document.documentElement.style.fontSize = '32px');
      assert.ok(await dialog.evaluate(e => e.scrollWidth <= e.clientWidth), '320px / 200% dialog has no horizontal overflow');
      assert.ok(await page.locator('.textNavigationPanelBody').evaluate(e => e.scrollWidth <= e.clientWidth), 'body fits');
      assert.ok(await page.locator('.textNavigationPanelList').evaluate(e => e.scrollWidth <= e.clientWidth), 'chapter titles wrap');
      for (const button of await dialog.locator('button:visible').all()) assert.ok((await button.boundingBox()).height >= 44);
    }
    await page.evaluate(() => document.documentElement.style.fontSize = '16px');
    await range.focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.evaluate(() => keys), 0, 'modal keys do not reach underlying reader');
    await page.keyboard.press('Escape');
    assert.equal(await dialog.count(), 0, 'native Escape closes');
    assert.equal(await page.evaluate(() => keys), 0);
    await page.keyboard.press('ArrowRight');
    assert.equal(await page.evaluate(() => keys), 1, 'closed panel does not swallow keys');
    await page.evaluate(() => { panel.open(51); document.body.inert = true; document.querySelector('[data-chapter="0"]').click(); });
    assert.deepEqual(await page.evaluate(() => commits), [76], 'native modal cannot navigate through body inert');
    await page.evaluate(() => { panel.close(); document.body.inert = false; panel.open(51); document.querySelector('[data-chapter="0"]').click(); });
    assert.deepEqual(await page.evaluate(() => commits), [76, 1], 'chapter click commits and closes');
    await page.evaluate(() => {
      panel.dispose(); panel.dispose();
      panel = new TextNavigation.TextNavigationPanel(document.querySelector('#root'), {
        language:'zh', pages:1, chapters:[], go:page => commits.push(page), moreChapters:async () => {},
      });
      panel.open(1);
    });
    assert.equal(await page.locator('dialog').count(), 1, 'dispose removes only the old panel');
    assert.equal(await page.getByRole('slider').isDisabled(), true);
    assert.equal(await page.getByRole('spinbutton').inputValue(), '0');
    assert.equal(await page.getByRole('button', {name:'跳转',exact:true}).isDisabled(), true);
    assert.ok(await page.getByText('暂无章节，仍可通过下方百分比跳转阅读位置。').isVisible());
    await page.getByRole('button', {name:'关闭',exact:true}).click();
    assert.equal(await page.locator('dialog[open]').count(), 0);
    await page.evaluate(() => panel.dispose());
    assert.equal(await page.locator('dialog').count(), 0);
  } finally { await browser.close(); }
});
