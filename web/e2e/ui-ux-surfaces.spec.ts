import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// This file uses the dev-only audit harness; run with playwright.surfaces.config.ts.
test.skip(({baseURL}) => !baseURL?.endsWith(':4174'), 'Requires isolated fixture server');

test('theme options show keyboard focus in forced colors', async ({ page }) => {
  await page.emulateMedia({ forcedColors: 'active' });
  await page.goto('/e2e/fixtures/ui-ux-audit/index.html?surface=settings&theme=light');
  const theme = page.getByRole('radiogroup', { name: 'Theme' });
  await theme.getByRole('radio', { name: 'Light' }).focus();
  await page.keyboard.press('ArrowRight');
  const dark = theme.getByRole('radio', { name: 'Dark' });
  await expect(dark).toBeFocused();
  await expect(dark).toHaveCSS('outline-style', 'solid');
  await expect(dark).toHaveCSS('outline-width', '2px');
});

test('conflict dialog actions remain reachable at 200% text size', async ({ page }) => {
  await page.setViewportSize({ width: 720, height: 450 });
  await page.goto('/e2e/fixtures/ui-ux-audit/index.html?surface=conflict&theme=light');
  await page.addStyleTag({ content: 'html{font-size:200%} *{animation:none!important}' });
  const dialog = page.getByRole('dialog');
  const action = dialog.getByRole('button', { name: 'Keep both', exact: true });
  await action.scrollIntoViewIfNeeded();
  await expect(action).toBeInViewport({ ratio: 1 });
  await expect(action).toBeEnabled();
});

for (const theme of ['light', 'dark']) {
  for (const surface of ['cloud-wipe', 'settings']) {
    test(`${surface} has readable, usable narrow actions in ${theme}`, async ({page}, testInfo) => {
      await page.setViewportSize({width: 320, height: 568});
      await page.goto(`/e2e/fixtures/ui-ux-audit/index.html?surface=${surface}&theme=${theme}`);
      await page.addStyleTag({content: '*,*::before,*::after{animation:none!important;transition:none!important}'});
      await expect(page.getByText(surface === 'settings' ? 'Sign out' : 'Your cloud notes were deleted from another device', {exact: true})).toBeVisible();
      const results = await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
      expect(results.violations).toEqual([]);
      if (surface === 'settings') {
        const themePicker = page.getByRole('radiogroup', {name: 'Theme'});
        const darkOption = await themePicker.getByRole('radio', {name: 'Dark'}).boundingBox();
        expect(darkOption!.x + darkOption!.width).toBeLessThanOrEqual(320);
      }
      if (surface === 'cloud-wipe') {
        expect(await page.getByText('Your cloud notes were deleted from another device', {exact: true}).evaluate(el => el.getBoundingClientRect().width)).toBeGreaterThan(250);
        for (const button of await page.getByRole('button').all()) {
          const box = await button.boundingBox();
          expect(box!.x).toBeGreaterThanOrEqual(0);
          expect(box!.x + box!.width).toBeLessThanOrEqual(320);
        }
      }
      await page.screenshot({path: testInfo.outputPath(`${surface}-${theme}.png`), fullPage: true});
    });
  }
}
for (const viewport of [{width:320,height:568}, {width:720,height:450}]) {
  test(`conflict comparison keeps every action reachable at ${viewport.width}x${viewport.height}`, async ({page}) => {
    await page.setViewportSize(viewport);
    await page.goto('/e2e/fixtures/ui-ux-audit/index.html?surface=conflict&theme=light');
    await page.addStyleTag({content:'*{animation:none!important;transition:none!important}'});
    const dialog = page.getByRole('dialog');
    const box = await dialog.boundingBox();
    expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height);
    const action = dialog.getByRole('button', {name:'Keep both', exact:true});
    await action.scrollIntoViewIfNeeded();
    await expect(action).toBeInViewport();
    // Actions are intercepted by the fixture; no note mutation is executed.
  });
  test(`incomplete account backup keeps confirmation reachable at ${viewport.width}x${viewport.height}`, async ({page}) => {
    await page.setViewportSize(viewport);
    await page.goto('/e2e/fixtures/ui-ux-audit/index.html?surface=delete-account&theme=light');
    await page.addStyleTag({content:'*{animation:none!important;transition:none!important}'});
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', {name:'Export a complete backup first', exact:true}).click();
    await expect(dialog.getByText('This backup is incomplete:', {exact:false})).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height);
    await dialog.evaluate(el => {el.scrollTop = el.scrollHeight;});
    const cancel = dialog.getByRole('button', {name:'Cancel', exact:true});
    await expect(cancel).toBeInViewport();
    await cancel.click();
    await expect(dialog).toHaveCount(0);
  });
  test(`long confirmation remains scrollable at ${viewport.width}x${viewport.height}`, async ({page}, testInfo) => {
    await page.setViewportSize(viewport);
    await page.goto('/e2e/fixtures/ui-ux-audit/index.html?surface=long-confirm&theme=light');
    await page.addStyleTag({content: '*{animation:none!important;transition:none!important}'});
    const dialog = page.getByRole('dialog');
    const box = await dialog.boundingBox();
    expect(box!.y).toBeGreaterThanOrEqual(0);
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height);
    await dialog.evaluate(el => { el.scrollTop = el.scrollHeight; });
    const cancel = dialog.getByRole('button', {name: 'Cancel', exact: true});
    await expect(cancel).toBeInViewport();
    await page.screenshot({path:testInfo.outputPath('short-dialog.png')});
    await cancel.click();
    await expect(dialog).toHaveCount(0);
  });
}
