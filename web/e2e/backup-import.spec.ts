import { expect, test, type Page } from '@playwright/test';

/**
 * Importing a backup adds its notes as new notes — it does not restore over what is already
 * here. Importing the same file twice therefore produces two sets, which is a surprise worth
 * stating before it happens rather than explaining afterwards.
 *
 * Driven through guest mode, so this needs no backend.
 */

const BACKUP = JSON.stringify({
  version: 3,
  notes: [
    { title: 'Imported one', content: 'first', timestamp: 1_725_000_000 },
    { title: 'Imported two', content: 'second', timestamp: 1_725_000_001 },
  ],
});

async function enterGuest(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({
    timeout: 20_000,
  });
}

async function chooseBackupFile(page: Page): Promise<void> {
  await page.locator('input[type="file"][accept*="json"]').setInputFiles({
    name: 'notelikeus-backup.json',
    mimeType: 'application/json',
    buffer: Buffer.from(BACKUP),
  });
}

test('importing asks first, and says the notes arrive as copies', async ({ page }) => {
  await enterGuest(page);
  await chooseBackupFile(page);

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 15_000 });
  // The count comes from the parsed file, so the user knows the size before committing.
  await expect(dialog).toContainText('2 notes');
  await expect(dialog).toContainText('another copy');
  // Attachment scope is disclosed rather than left to be discovered.
  await expect(dialog).toContainText(/[Ii]mages aren't part of a JSON backup/);
});

test('cancelling imports nothing', async ({ page }) => {
  await enterGuest(page);
  await chooseBackupFile(page);

  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();

  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Imported one', exact: true })).toHaveCount(0);
});

test('confirming imports, and a second import adds another copy', async ({ page }) => {
  await enterGuest(page);

  await chooseBackupFile(page);
  await page.getByRole('button', { name: 'Import', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Imported one', exact: true })).toHaveCount(1, {
    timeout: 20_000,
  });

  // The documented consequence of "copies, not restore": the same file again doubles them.
  await chooseBackupFile(page);
  await page.getByRole('button', { name: 'Import', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Imported one', exact: true })).toHaveCount(2, {
    timeout: 20_000,
  });
});
