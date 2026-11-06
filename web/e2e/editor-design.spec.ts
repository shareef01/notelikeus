import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

for (const base of ['light', 'dark'] as const) {
  for (const width of [320, 1280]) {
    test(`editor remains readable and accessible: ${base} at ${width}px`, async ({ page }, info) => {
      await page.setViewportSize({ width, height: 844 });
      await page.addInitScript(base => localStorage.setItem('notelikeus-settings', JSON.stringify({ state: { theme: { base, accent: 'neutral', amoled: false } }, version: 1 })), base);
      await page.goto('/');
      await page.getByRole('button', { name: 'Continue without an account' }).click();
      await page.getByRole('button', { name: 'New note' }).first().click();
      const title = page.getByRole('textbox', { name: 'Note title' });
      await title.fill('A long title that stays fully visible and wraps naturally on smaller screens');
      await title.press('Enter');
      const body = page.getByRole('textbox', { name: 'Note body' });
      await expect(body).toBeFocused();
      await body.fill('Space to think.\nA comfortable place to write and collect your ideas.');
      await page.addStyleTag({ content: '*,*::before,*::after{animation:none!important;transition:none!important}' });
      expect(await title.evaluate(field => field.scrollHeight - field.clientHeight)).toBeLessThanOrEqual(1);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      const results = await new AxeBuilder({ page }).include('[role="dialog"]').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(results.violations).toEqual([]);
      await page.screenshot({ path: `test-results/editor-design-${base}-${width}-${info.project.name}.png` });
    });
  }
}
