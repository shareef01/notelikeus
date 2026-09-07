import { expect, test, type Page } from '@playwright/test';

/**
 * Account A must never see account B's notes in the same browser profile, and
 * leftover IndexedDB from A must not list under B after a local sign-out.
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

async function waitForAuthForm(page: Page): Promise<void> {
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
}

async function createAccount(page: Page, email: string): Promise<void> {
  await waitForAuthForm(page);
  await page.locator('#test-login-email').fill(email);
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

async function signIn(page: Page, email: string): Promise<void> {
  await waitForAuthForm(page);
  await page.locator('#test-login-email').fill(email);
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

async function signOut(page: Page): Promise<void> {
  // Open the drawer whenever the layout has one, rather than inferring it from whether the Sign
  // out button looks visible.
  //
  // On mobile the navigation lives in a drawer that starts closed, and a closed drawer is only
  // translated off-canvas — it keeps a real bounding box, so Playwright reports its buttons as
  // visible. Asking the Sign out button therefore concluded the drawer was already open, skipped
  // the menu, and then spent the full timeout trying to click an element sitting at x=-284 in a
  // 393px viewport. The menu trigger only renders in the layout that actually has a drawer, so
  // its presence is what genuinely answers "is there something to open here".
  const menuTrigger = page.getByRole('button', { name: 'Open menu' });
  if (await menuTrigger.isVisible().catch(() => false)) {
    await menuTrigger.click();
  }
  await page.getByRole('button', { name: 'Sign out', exact: true }).first().click();
  await page.getByRole('button', { name: 'Sign out', exact: true }).last().click();
  await waitForAuthForm(page);
}

function noteCard(page: Page, title: string) {
  return page.getByRole('button', { name: title, exact: true }).first();
}

async function createNote(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: 'New note' }).first().click();
  const titleField = page.getByPlaceholder('Title').first();
  await expect(titleField).toBeVisible({ timeout: 15_000 });
  await titleField.fill(title);
  await page.getByPlaceholder('Start writing…').first().fill(`body for ${title}`);
  const created = writeAccepted(page);
  await page.keyboard.press('Escape');
  await expect(noteCard(page, title)).toBeVisible({ timeout: 20_000 });
  await created;
}

test('switching accounts does not leak the previous account notes', async ({ page }) => {
  const emailA = uniqueEmail();
  const emailB = uniqueEmail();
  const noteA = `A secret ${Date.now()}`;
  const noteB = `B only ${Date.now()}`;

  await page.goto('/');
  await createAccount(page, emailA);
  await createNote(page, noteA);

  await signOut(page);
  await createAccount(page, emailB);
  await expect(noteCard(page, noteA)).toHaveCount(0);
  await createNote(page, noteB);
  await expect(noteCard(page, noteA)).toHaveCount(0);

  await signOut(page);
  await signIn(page, emailA);
  await expect(noteCard(page, noteA)).toBeVisible({ timeout: 30_000 });
  await expect(noteCard(page, noteB)).toHaveCount(0);
});
