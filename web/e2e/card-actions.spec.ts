import { expect, test } from '@playwright/test';

/**
 * Journey F — pin, archive and trash, and the affordance that leads to them.
 *
 * The actions live in selection mode, and selection mode is entered from a checkbox on each card.
 * That checkbox was revealed by `group-hover` alone, so on a phone it was `opacity-0` forever: there
 * is no hover on a touch device, which left pin, archive and trash unreachable at every touch width
 * while looking perfectly fine on a desktop. Measured before the fix: opacity 0 at 390px with a
 * coarse pointer.
 *
 * These tests use their own contexts because `hasTouch` is a context option, not a media query that
 * `emulateMedia` can fake — and the whole point is the difference between a coarse and a fine
 * pointer.
 */
async function notesScreen(context: import('@playwright/test').BrowserContext, label: string) {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note' }).first().click();
  await page.getByRole('textbox', { name: 'Note title' }).first().fill(label);
  await page.waitForTimeout(1_200);
  const back = page.getByRole('button', { name: /\bback\b/i }).first();
  if (await back.count()) await back.click();
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(1_200);
  return page;
}

test('on a touch device the way into selection mode is visible', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  const page = await notesScreen(context, 'Touch affordance');

  const coarse = await page.evaluate(() => matchMedia('(pointer: coarse)').matches);
  expect(coarse, 'this context should report a coarse pointer, or the test proves nothing').toBe(true);

  const checkbox = page.getByRole('checkbox').first();
  await expect(checkbox, 'the selection checkbox must be visible without hover').toBeVisible();
  const opacity = await checkbox.evaluate((el) => Number(getComputedStyle(el).opacity));
  expect(opacity).toBeGreaterThan(0.9);

  // And it does what it is for: the destructive-capable actions become reachable.
  await checkbox.click();
  await expect(page.getByRole('button', { name: 'Move to trash' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('button', { name: 'Archive' }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Pin' }).first()).toBeVisible();
  await context.close();
});

test('on a fine pointer the checkbox stays hover-revealed', async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, hasTouch: false });
  const page = await notesScreen(context, 'Desktop affordance');

  const coarse = await page.evaluate(() => matchMedia('(pointer: coarse)').matches);
  expect(coarse).toBe(false);

  // Hidden at rest, shown on hover — the behaviour desktop users already had, and the fix must not
  // have quietly turned every card into a checkbox rail.
  const checkbox = page.getByRole('checkbox').first();
  expect(await checkbox.evaluate((el) => Number(getComputedStyle(el).opacity))).toBeLessThan(0.1);

  await page.getByRole('button', { name: 'Desktop affordance' }).first().hover();
  await page.waitForTimeout(500);
  expect(await checkbox.evaluate((el) => Number(getComputedStyle(el).opacity))).toBeGreaterThan(0.9);
  await context.close();
});
