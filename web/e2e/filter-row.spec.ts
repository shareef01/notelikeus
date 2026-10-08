import { expect, test, type Page } from '@playwright/test';

/**
 * The filter rows scroll sideways with their scrollbar hidden. On a phone the colour swatches were
 * therefore cut off at the right edge with nothing to say more existed, and — because an overflow
 * container is not focusable by default — the hidden colours could only be reached by swiping.
 *
 * These assertions are made in a real browser on purpose. happy-dom reports `scrollWidth` and
 * `clientWidth` as 0, so a unit test cannot see any of this; the numbers only exist after layout.
 */
const FILTERS = '[role="group"][aria-label="Filters"]';
const CUE = 'Scroll filters right';

async function enterNotes(page: Page, width = 390): Promise<void> {
  await page.setViewportSize({ width, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
}

async function scrollFiltersTo(page: Page, position: 'start' | 'end'): Promise<void> {
  await page.evaluate((edge) => {
    const el = document.querySelector('[role="group"][aria-label="Filters"]');
    if (el) el.scrollLeft = edge === 'end' ? el.scrollWidth : 0;
  }, position);
  await page.waitForTimeout(400);
}

test('a narrow screen offers a way to reach the clipped filters', async ({ page }) => {
  await enterNotes(page);

  const row = page.locator(FILTERS);
  const overflows = await row.evaluate((el) => el.scrollWidth - el.clientWidth > 1);
  expect(overflows, 'the colour row is expected to overflow at 390px, or this test proves nothing').toBe(true);

  const cue = page.getByRole('button', { name: CUE });
  await expect(cue).toBeVisible();

  // Pressing it does what a user reaching for the clipped chips was trying to do.
  const before = await row.evaluate((el) => el.scrollLeft);
  await cue.click();
  await page.waitForTimeout(600);
  expect(await row.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before);
});

test('the cue disappears once there is nothing more to the right', async ({ page }) => {
  await enterNotes(page);
  const cue = page.getByRole('button', { name: CUE });
  await expect(cue).toBeVisible();

  await scrollFiltersTo(page, 'end');
  // A cue that keeps offering more after the end is reached is worse than no cue: it is wrong.
  await expect(cue).toHaveCount(0);

  await scrollFiltersTo(page, 'start');
  await expect(cue).toBeVisible();
});

test('the filter rows are reachable and scrollable by keyboard', async ({ page }) => {
  await enterNotes(page);

  const row = page.locator(FILTERS);
  await row.focus();
  await expect(row).toBeFocused();

  const before = await row.evaluate((el) => el.scrollLeft);
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(400);
  // Everything off-screen in these rows has to be reachable without a pointer (WCAG 2.1.1).
  expect(await row.evaluate((el) => el.scrollLeft)).toBeGreaterThan(before);
});

test('a wide screen shows no cue, because nothing is hidden', async ({ page }) => {
  await enterNotes(page, 1440);
  const row = page.locator(FILTERS);
  const overflows = await row.evaluate((el) => el.scrollWidth - el.clientWidth > 1);
  expect(overflows).toBe(false);
  await expect(page.getByRole('button', { name: CUE })).toHaveCount(0);
});
