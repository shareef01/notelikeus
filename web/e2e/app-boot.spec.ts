import { expect, test, type ConsoleMessage } from '@playwright/test';

/**
 * Boots the production-mode e2e bundle in a real browser against local Supabase.
 */

function isIgnorableError(message: string): boolean {
  return (
    message.includes('ServiceWorker') ||
    message.includes('service worker')
  );
}

function collectConsoleErrors(page: import('@playwright/test').Page): string[] {
  const errors: string[] = [];
  page.on('console', (message: ConsoleMessage) => {
    if (message.type() === 'error' && !isIgnorableError(message.text())) {
      errors.push(message.text());
    }
  });
  page.on('pageerror', (error) => {
    if (!isIgnorableError(error.message)) errors.push(`pageerror: ${error.message}`);
  });
  return errors;
}

test('the app boots and replaces the loading splash', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/');

  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#root')).not.toBeEmpty();

  expect(errors, `unexpected console errors:\n${errors.join('\n')}`).toEqual([]);
});

test('the signed-out app reaches an interactive state', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  const signIn = page.getByRole('button', { name: /sign in/i }).first();
  await expect(signIn).toBeVisible({ timeout: 20_000 });
});
