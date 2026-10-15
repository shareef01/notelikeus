import { expect, test, type Page } from '@playwright/test';
import { createNoteWithTitle } from './helpers/newNote';

/**
 * DESIGN_SYSTEM §5.1 in a real browser against local Supabase: the editor says "Synced" only once
 * the server accepted the note's latest edit, an edit made offline is called out as waiting, and
 * both clear by themselves when the connection returns.
 */

const PASSWORD = 'e2e-password-123';

async function signUp(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
  await page.locator('#test-login-email').fill(`e2e-sync-${Date.now()}@example.com`);
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

function editorStatus(page: Page) {
  return page.locator('footer [role="status"]').first();
}

test('the status line and banner follow an edit offline and back', async ({ page, context }) => {
  await signUp(page);
  const title = `Sync status ${Date.now()}`;
  await createNoteWithTitle(page, title);

  await page.getByRole('button', { name: title, exact: true }).first().click();
  await expect(editorStatus(page)).toContainText('Synced', { timeout: 20_000 });

  await context.setOffline(true);
  await page.getByPlaceholder('Start writing…').first().fill('written offline');
  await expect(editorStatus(page)).toContainText('Offline — changes saved here', { timeout: 20_000 });
  await expect(page.getByText('1 edit saved on this device will sync when you reconnect')).toBeVisible();

  await context.setOffline(false);
  await expect(editorStatus(page)).toContainText('Synced', { timeout: 30_000 });
  await expect(page.getByText(/You’re offline/)).toHaveCount(0);
});
