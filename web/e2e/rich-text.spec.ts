import { expect, test, type Page } from '@playwright/test';

/**
 * Journey D — the editor's formatting and the note's survival.
 *
 * Two things this pins down, both of which I got wrong on the first pass and only measurement
 * corrected:
 *
 * 1. There is no rich-text engine on web. The body is a `<textarea aria-label="Note body">` with no
 *    contenteditable and no ProseMirror/TipTap anywhere — but the feature still exists: the
 *    formatting toolbar writes markdown into that plain text. Concluding "no rich text" from the
 *    absence of `contenteditable` would have been wrong.
 * 2. Guest notes survive a reload; the guest *session* does not. After a reload the sign-in screen
 *    is shown again, so a test that checks only the notes list reads it as data loss. Re-entering
 *    guest mode shows the note intact, which is consistent with the sign-in copy: "Notes are saved
 *    locally on this device."
 */
async function openEditor(page: Page): Promise<{ title: ReturnType<Page['getByRole']>; body: ReturnType<Page['getByRole']> }> {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note' }).first().click();

  const title = page.getByRole('textbox', { name: 'Note title' }).first();
  const body = page.getByRole('textbox', { name: 'Note body' }).first();
  await expect(title).toBeVisible({ timeout: 15_000 });
  return { title, body };
}

const bodyValue = (page: Page) =>
  page.evaluate(() => (document.querySelector('textarea[aria-label="Note body"]') as HTMLTextAreaElement | null)?.value ?? null);

test('the formatting toolbar writes markdown into the plain-text body', async ({ page }) => {
  const { title, body } = await openEditor(page);
  const toolbar = page.getByRole('toolbar', { name: 'Text formatting' });
  await expect(toolbar).toBeVisible();

  await title.fill('Journey D note');
  await body.click();
  await body.fill('alpha beta gamma');
  await page.waitForTimeout(300);

  await page.keyboard.press('Control+a');
  await toolbar.getByRole('button', { name: 'Bold' }).click();
  await page.waitForTimeout(400);
  expect(await bodyValue(page)).toBe('**alpha beta gamma**');

  // Italic nests rather than replacing, so both marks survive.
  await toolbar.getByRole('button', { name: 'Italic' }).click();
  await page.waitForTimeout(400);
  expect(await bodyValue(page)).toBe('**_alpha beta gamma_**');
});

test('a guest note survives a reload, even though the session does not', async ({ page }) => {
  const { title, body } = await openEditor(page);
  await title.fill('Persistence check');
  await body.fill('this should come back');
  await page.waitForTimeout(2_000); // autosave

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(3_000);

  // The session is gone — that is the behaviour, not a failure.
  const signIn = page.getByRole('button', { name: 'Continue without an account' });
  await expect(signIn).toBeVisible({ timeout: 20_000 });
  await signIn.click();
  await page.waitForTimeout(1_500);

  // The data is not.
  await expect(page.getByText('Persistence check').first()).toBeVisible({ timeout: 20_000 });
});
