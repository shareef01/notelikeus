import { expect, test } from '@playwright/test';

/**
 * Reflow contract (WCAG 1.4.10): no screen may require horizontal scrolling.
 *
 * This exists because the notes screen did. The header built its toolbar as a flex item without
 * `min-w-0`, so the row could not shrink below its content — at 320–390px the search field, the
 * view toggle and the shortcut hint together measured 412px inside a 320px shell and the whole
 * page scrolled sideways. A unit test cannot see that; a browser can, which is why the assertion
 * is the measurement itself rather than a snapshot of the classes that happen to fix it.
 *
 * Entry screen and guest-mode notes screen both, at every width the design reviews against.
 */
const WIDTHS = [320, 360, 390, 768, 1024, 1440];

async function horizontalOverflow(page: import('@playwright/test').Page): Promise<number> {
  return page.evaluate(() => {
    const el = document.documentElement;
    return el.scrollWidth - el.clientWidth;
  });
}

test.describe('reflow: no screen scrolls sideways', () => {
  for (const width of WIDTHS) {
    test(`entry screen at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      await page.goto('/');
      await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
      expect(await horizontalOverflow(page), `entry screen overflows at ${width}px`).toBeLessThanOrEqual(0);
    });

    test(`notes screen at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 });
      await page.goto('/');
      await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

      // Guest mode needs no backend, which is why this test can run anywhere the web app builds.
      // Same entry as the other guest specs use.
      await page.getByRole('button', { name: 'Continue without an account' }).click();
      // "New note" is present on the notes screen at every width — unlike the drawer's "Library"
      // heading, which is off-canvas below md and therefore invisible on the widths this test cares
      // about most.
      await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
      expect(await horizontalOverflow(page), `notes screen overflows at ${width}px`).toBeLessThanOrEqual(0);
    });
  }

  test('editor matches the screen it opens from', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 844 });
    await page.goto('/');
    await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
    await page.getByRole('button', { name: 'Continue without an account' }).click();
    await page.getByRole('button', { name: 'New note' }).first().click();
    await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });
    expect(await horizontalOverflow(page), 'editor overflows at 320px').toBeLessThanOrEqual(0);
  });
});
