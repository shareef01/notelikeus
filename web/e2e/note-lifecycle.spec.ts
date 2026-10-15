import { expect, test, type Page } from '@playwright/test';
import { leaveEditor, openNewNote } from './helpers/newNote';

/**
 * A note's full round trip through a real browser and local Supabase.
 *
 * Signs in with email/password. That path is enabled for e2e builds only
 * (`VITE_E2E`), and the runner refuses a non-localhost Supabase URL.
 */

function uniqueEmail(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 10_000)}@example.com`;
}

const PASSWORD = 'e2e-password-123';

function writeAccepted(page: Page) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/rpc/apply_note_change'),
    { timeout: 30_000 },
  );
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

function noteCard(page: Page, title: string) {
  return page.getByRole('button', { name: title, exact: true }).first();
}

async function reloadApp(page: Page): Promise<void> {
  await page.reload();
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
}

test('a note survives create, reload and edit', async ({ page }) => {
  await signUp(page);

  const title = `E2E note ${Date.now()}`;
  const edited = `${title} edited`;

  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByPlaceholder('Title').first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  await titleField.fill(title);
  await page.getByPlaceholder('Start writing…').first().fill('written by the e2e suite');

  const created = writeAccepted(page);
  await page.keyboard.press('Escape');
  await expect(noteCard(page, title)).toBeVisible({ timeout: 20_000 });
  await created;

  await reloadApp(page);
  await expect(noteCard(page, title)).toBeVisible({ timeout: 30_000 });

  await noteCard(page, title).click();
  const editTitle = page.getByPlaceholder('Title').first();
  await expect(editTitle).toBeVisible({ timeout: 15_000 });
  await editTitle.fill(edited);

  const updated = writeAccepted(page);
  await page.keyboard.press('Escape');
  await expect(noteCard(page, edited)).toBeVisible({ timeout: 20_000 });
  await updated;

  await reloadApp(page);
  await expect(noteCard(page, edited)).toBeVisible({ timeout: 30_000 });
});

// AU-20: the new-note editor moves focus to the body one frame after it opens. A title typed
// straight away used to lose that race and land in the body, so the note was saved untitled.
test('a title typed the moment a new note opens stays the title', async ({ page }) => {
  await signUp(page);
  // A busy main thread (the cold start AU-20 was seen on) stretches that one frame. Slowing frames
  // makes the race certain here instead of depending on runner speed.
  await page.evaluate(() => {
    window.requestAnimationFrame = (callback) =>
      window.setTimeout(() => callback(performance.now()), 500);
    window.cancelAnimationFrame = (id) => window.clearTimeout(id);
  });

  const title = `E2E instant ${Date.now()}`;
  await openNewNote(page);
  // No wait for the editor to settle: click and type the way a quick user does.
  await page.locator('#note-title').click();
  // A person's typing spans that frame: half the title before it fires, half after.
  await page.keyboard.type(title.slice(0, 6));
  await page.waitForTimeout(800);
  await page.keyboard.type(title.slice(6));
  await expect(page.locator('#note-title')).toHaveValue(title);
  await expect(page.getByRole('textbox', { name: 'Note body' })).toHaveValue('');

  const created = writeAccepted(page);
  await leaveEditor(page);
  await expect(noteCard(page, title)).toBeVisible({ timeout: 20_000 });
  await created;

  await reloadApp(page);
  await expect(noteCard(page, title)).toBeVisible({ timeout: 30_000 });
});
