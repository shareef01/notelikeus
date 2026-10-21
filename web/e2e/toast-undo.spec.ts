import { expect, test, type Page } from '@playwright/test';

/**
 * The Undo toast is the only way back from moving a note to trash, deleting it permanently or
 * emptying the trash. On main it vanished after five seconds whatever the user was doing —
 * measured at 5.3s with the pointer resting on it — and had no keyboard route at all.
 */
async function noteInTrashToast(page: Page) {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'New note' }).first().click();
  await page.getByRole('textbox', { name: 'Note title' }).first().fill('Toast subject');
  await page.getByRole('button', { name: 'Back' }).click();

  const card = page.getByRole('button', { name: /^Toast subject/ }).first();
  await card.hover();
  await page.getByRole('button', { name: 'Delete note' }).first().click();
  // The live region is always mounted and has no box of its own, so the panel is found by id.
  const toast = page.getByTestId('toast').filter({ hasText: 'Note moved to trash' });
  await expect(toast).toBeVisible();
  return { card, toast };
}

test('the toast stays while the pointer rests on it', async ({ page }) => {
  const { toast } = await noteInTrashToast(page);
  await toast.hover();
  await page.waitForTimeout(9_500);
  await expect(toast).toBeVisible();

  await page.mouse.move(0, 0);
  await expect(toast).toBeHidden({ timeout: 12_000 });
});

test('Ctrl+Z undoes the action the toast offers', async ({ page }) => {
  const { toast } = await noteInTrashToast(page);
  await page.keyboard.press('Control+z');
  await expect(toast).toBeHidden();
  await expect(page.getByRole('button', { name: /^Toast subject/ }).first()).toBeVisible();
});

test('the toast can be dismissed', async ({ page }) => {
  const { toast } = await noteInTrashToast(page);
  await toast.getByRole('button', { name: 'Dismiss' }).click();
  await expect(toast).toBeHidden();
});
