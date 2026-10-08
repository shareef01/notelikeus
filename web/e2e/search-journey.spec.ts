import { expect, test, type Page } from '@playwright/test';

/**
 * Journey E — search and smart views, and the chrome states around it.
 *
 * This exists because the toolbar is where this branch made its first change (`min-w-0 flex-1` on
 * the header rows). Every other spec exercised that chrome at rest; search puts different content in
 * the same row — a disabled sort chip with a longer accessible name, a history overlay under the
 * field — and none of it had been measured. It measured clean, so this pins it rather than fixing it.
 */
async function enterNotes(page: Page, width = 390): Promise<void> {
  await page.setViewportSize({ width, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
}

const sortChip = (page: Page) => page.getByRole('button', { name: /^Sort/ });

test('the sort control locks to relevance while searching, and says why', async ({ page }) => {
  await enterNotes(page);
  const search = page.getByRole('searchbox').first();

  await expect(sortChip(page)).toHaveAttribute('aria-label', /Choose sort order/);
  await expect(sortChip(page)).toBeEnabled();

  await search.fill('alpha');
  // Relevance is not something the user chose, so the control has to explain itself rather than
  // simply go dead.
  await expect(sortChip(page)).toBeDisabled();
  await expect(sortChip(page)).toHaveAttribute('aria-label', /Sort locked to relevance while searching/);

  await search.fill('');
  await expect(sortChip(page)).toBeEnabled();
  await expect(sortChip(page)).toHaveAttribute('aria-label', /Choose sort order/);
});

test('searching does not push the page sideways', async ({ page }) => {
  for (const width of [320, 390, 768]) {
    await enterNotes(page, width);
    const search = page.getByRole('searchbox').first();
    await search.fill('a query that matches nothing at all');
    await page.waitForTimeout(700);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow, `searching overflows at ${width}px`).toBeLessThanOrEqual(0);
  }
});

test('the search history stays inside the field it belongs to', async ({ page }) => {
  await enterNotes(page, 320);
  const search = page.getByRole('searchbox').first();

  // A search has to be committed for there to be history at all.
  await search.fill('alpha');
  await search.press('Enter');
  await page.waitForTimeout(700);
  await search.fill('');
  await page.waitForTimeout(500);
  await search.click();
  await page.waitForTimeout(700);

  const list = page.getByText('Recent searches').first();
  await expect(list).toBeVisible({ timeout: 10_000 });

  // 320px is the width where an absolutely positioned list under the field would escape the viewport
  // if it were sized to its contents.
  const inside = await page.evaluate(() => {
    const field = document.querySelector('[role="searchbox"], input[type="search"], input[placeholder]');
    const label = [...document.querySelectorAll('*')].find((el) => el.textContent?.trim() === 'Recent searches');
    const panel = label?.closest('div');
    if (!field || !panel) return null;
    const f = field.getBoundingClientRect();
    const p = panel.getBoundingClientRect();
    return p.left >= f.left - 1 && p.right <= f.right + 1 && p.left >= 0 && p.right <= window.innerWidth + 1;
  });
  expect(inside, 'the history panel should sit within the field and the viewport').toBe(true);
});

test('Ctrl+K reaches the search field, as the hint claims', async ({ page }) => {
  await enterNotes(page);
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  await page.keyboard.press('Control+k');
  await page.waitForTimeout(500);
  const focused = await page.evaluate(() => document.activeElement?.getAttribute('aria-label'));
  expect(focused).toBe('Search notes');
});
