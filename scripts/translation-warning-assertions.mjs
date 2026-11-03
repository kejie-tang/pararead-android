import assert from 'node:assert/strict';

// Tests generated PDF/TXT assets, never a separately styled warning mock.
export async function assertTranslationWarningStyle(page, code = 'protected_token_mismatch') {
  for (const colorScheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme });
    const result = await page.locator('.bilingualSentence[data-status="done"]').evaluateAll((elements, code) => {
      const warning = elements.find(el => el.textContent.includes(`*****（${code}）`));
      const normal = warning && elements.find(el => !el.textContent.includes('*****（') &&
        getComputedStyle(el).fontWeight === getComputedStyle(warning).fontWeight);
      if (!warning || !normal) return {missing:true,count:elements.length,text:elements.map(el => el.textContent).join('\n').slice(0,800)};
      const style = el => {
        const css = getComputedStyle(el);
        return { color: css.color, decoration: css.textDecorationLine, weight: css.fontWeight, font: css.fontFamily };
      };
      return { text: warning.textContent, title: warning.title, children: warning.childElementCount,
        warning: style(warning), normal: style(normal) };
    }, code);
    assert.ok(!result.missing, `${colorScheme}: both warned and ordinary translated sentences exist: ${JSON.stringify(result)}`);
    assert.ok(result.text.trimEnd().endsWith(`*****（${code}）`));
    assert.equal(result.children, 0, 'stars and parentheses stay text, not HTML/Markdown');
    assert.equal(result.title, '', 'accepted warning is not an error tooltip');
    assert.deepEqual(result.warning, result.normal, `${colorScheme}: warning has exactly the neighbouring translation style`);
    assert.equal(result.warning.decoration, 'none', 'no underline, wavy or otherwise');
    const channels = result.warning.color.match(/[\d.]+/g).slice(0, 3).map(Number);
    // Existing TXT text has a subtle neutral theme tint (#20262c), not exact equal RGB.
    // Preserve that same nearby text color rather than introducing warning red.
    assert.ok(Math.max(...channels) - Math.min(...channels) <= 24, 'normal neutral text, not warning red');
  }
  await page.emulateMedia({ colorScheme: 'light' });
}
