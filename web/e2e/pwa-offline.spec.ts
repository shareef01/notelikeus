import { expect, test } from '@playwright/test';

test('the installed service worker serves the app after an offline reload', async ({ page, context }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller) {
      await new Promise<void>((resolve) => {
        navigator.serviceWorker.addEventListener('controllerchange', () => resolve(), { once: true });
      });
    }
  });
  await context.setOffline(true);
  await page.reload();
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Continue without an account' })).toBeVisible();
  expect(await page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
});
