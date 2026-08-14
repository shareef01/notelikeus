import { expect, test, type Page } from '@playwright/test';

/**
 * A note's full round trip through a real browser and a real Firestore.
 *
 * The boot suite proves the bundle loads and Firebase initialises. It does not prove a note can be
 * written, read back after a reload, and edited — which is what the app exists to do, and what a
 * production deploy actually risks. Everything in between (the store, the repository, the mapper,
 * the security rules, the snapshot listener) is only covered end to end here; the emulator sync
 * suite exercises that layer under Node, without the UI.
 *
 * Signs in with email/password against the Auth emulator. That path is enabled for e2e builds only
 * (see isTestLoginEnabled), and the same flag forces Firebase at the emulators, so this can never
 * reach a real account.
 */

/** Each run gets its own account, so notes from a previous run cannot bleed into this one. */
function uniqueEmail(): string {
  return `e2e-${Date.now()}-${Math.floor(Math.random() * 10_000)}@example.com`;
}

const PASSWORD = 'e2e-password-123';

/**
 * Resolves once Firestore has accepted a write.
 *
 * The editor debounces saves by roughly a second, and the note appears in the list optimistically
 * before that. Reloading in between does not just race the write — it destroys it, because the
 * save has not been handed to Firestore yet, so nothing is queued to survive the reload. Arming
 * this *before* the action that triggers the save is what makes "durable" a real assertion rather
 * than a sleep long enough to usually work.
 */
function writeAccepted(page: Page) {
  return page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/google.firestore.v1.Firestore/Write/channel'),
    { timeout: 30_000 },
  );
}

async function signUp(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  // Signed out, the app opens straight onto the auth screen, and in an e2e build the test-login
  // form is already on it — nothing to click open first. (A /sign in/i click here would hit
  // "Sign in with Google" and hang on a provider the emulator cannot serve.)
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
  await page.locator('#test-login-email').fill(uniqueEmail());
  await page.locator('#test-login-password').fill(PASSWORD);
  // "Create account", not "Sign in": the Auth emulator starts with no users.
  await page.getByRole('button', { name: 'Create account', exact: true }).click();

  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

/** The clickable handle for a note in the list. */
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

  // --- create ---------------------------------------------------------------------------------
  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByPlaceholder('Title').first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  await titleField.fill(title);
  await page.getByPlaceholder('Start writing…').first().fill('written by the e2e suite');

  const created = writeAccepted(page);
  await page.keyboard.press('Escape');
  // Each note card exposes a button whose accessible name is the title. Matching the raw text
  // instead picks up the <h2> inside the card, which is not the clickable element.
  await expect(noteCard(page, title)).toBeVisible({ timeout: 20_000 });
  await created;

  // --- it is really persisted, not just rendered ----------------------------------------------
  // A reload drops all local React state, so the note can only come back from Firestore.
  await reloadApp(page);
  await expect(noteCard(page, title)).toBeVisible({ timeout: 30_000 });

  // --- edit -----------------------------------------------------------------------------------
  await noteCard(page, title).click();
  const editTitle = page.getByPlaceholder('Title').first();
  await expect(editTitle).toBeVisible({ timeout: 15_000 });
  await editTitle.fill(edited);

  const updated = writeAccepted(page);
  await page.keyboard.press('Escape');
  await expect(noteCard(page, edited)).toBeVisible({ timeout: 20_000 });
  await updated;

  // The edit must be durable too, or it only ever existed in the browser.
  await reloadApp(page);
  await expect(noteCard(page, edited)).toBeVisible({ timeout: 30_000 });
});
