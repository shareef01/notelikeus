import { expect, test, type Page } from '@playwright/test';

/**
 * What happens to a user's text when the local write fails.
 *
 * The editor is the only place an unsaved edit exists, so a failed IndexedDB write must not be
 * followed by the editor closing. These drive the real app with `notes` writes forced to reject,
 * because the whole point is the behaviour at the boundary where persistence actually fails.
 */

function uniqueEmail(): string {
  return `e2e-save-${Date.now()}-${Math.floor(Math.random() * 10_000)}@example.com`;
}

const PASSWORD = 'e2e-password-123';

/**
 * Fails every write to the `notes` object store, leaving other stores working so the app still
 * boots. Installed before any script runs so the very first save already sees it.
 */
async function failNoteWrites(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const originalPut = IDBObjectStore.prototype.put;
    // eslint-disable-next-line func-names
    IDBObjectStore.prototype.put = function (this: IDBObjectStore, ...args: unknown[]) {
      if (this.name === 'notes' && (window as unknown as { __failNotes?: boolean }).__failNotes) {
        throw new DOMException('Injected write failure', 'InvalidStateError');
      }
      return originalPut.apply(this, args as never);
    } as typeof IDBObjectStore.prototype.put;
  });
}

async function signUp(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
  await page.locator('#test-login-email').fill(uniqueEmail());
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

test('a failed local save keeps the editor open with the text intact', async ({ page }) => {
  await failNoteWrites(page);
  await signUp(page);

  const body = 'text that must survive a failed write';
  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByRole('textbox', { name: 'Note title' }).first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  await titleField.fill('Failed save note');
  await page.getByRole('textbox', { name: 'Note body' }).first().fill(body);

  await page.evaluate(() => {
    (window as unknown as { __failNotes?: boolean }).__failNotes = true;
  });

  // Escape asks to leave. The write fails, so the editor must stay put.
  await page.keyboard.press('Escape');

  await expect(page.getByRole('alert')).toContainText('Could not save this note', {
    timeout: 15_000,
  });
  await expect(page.getByRole('textbox', { name: 'Note body' }).first()).toHaveValue(body);
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toHaveValue(
    'Failed save note',
  );

  // No success toast may have appeared for a write that never landed.
  await expect(page.getByText('Note saved', { exact: true })).toHaveCount(0);
});

test('retrying after the failure clears the error and closes the editor', async ({ page }) => {
  await failNoteWrites(page);
  await signUp(page);

  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByRole('textbox', { name: 'Note title' }).first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  const title = `Retry note ${Date.now()}`;
  await titleField.fill(title);

  await page.evaluate(() => {
    (window as unknown as { __failNotes?: boolean }).__failNotes = true;
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });

  // Storage recovers, and the retry is the user's explicit second attempt.
  await page.evaluate(() => {
    (window as unknown as { __failNotes?: boolean }).__failNotes = false;
  });
  await page.getByRole('button', { name: 'Retry save' }).click();

  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveCount(0, {
    timeout: 20_000,
  });
  await expect(page.getByRole('button', { name: title, exact: true }).first()).toBeVisible({
    timeout: 20_000,
  });
});

test('discarding after a failed save is explicit and closes without claiming success', async ({
  page,
}) => {
  await failNoteWrites(page);
  await signUp(page);

  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByRole('textbox', { name: 'Note title' }).first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  await titleField.fill('Discarded note');

  await page.evaluate(() => {
    (window as unknown as { __failNotes?: boolean }).__failNotes = true;
  });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });

  await page.getByRole('button', { name: 'Discard changes' }).click();

  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveCount(0, {
    timeout: 20_000,
  });
  await expect(page.getByText('Note saved', { exact: true })).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Discarded note', exact: true }),
  ).toHaveCount(0);
});
