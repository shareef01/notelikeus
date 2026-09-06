import { expect, test, type Page } from '@playwright/test';

/**
 * High-value accessibility smoke: named controls, dialogs, and selection chrome.
 * Avoids axe snapshots so layout polish cannot flake the suite.
 */

async function continueAsGuest(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: /continue without an account/i }).click();
  await expect(page.getByRole('searchbox', { name: /search notes/i })).toBeVisible({
    timeout: 20_000,
  });
}

test('auth dialog names its actions and does not trap a guest behind Google-only copy', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  const dialog = page.getByRole('dialog').first();
  await expect(dialog).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('button', { name: /sign in with google/i })).toBeVisible();
  await expect(page.getByRole('button', { name: /continue without an account/i })).toBeVisible();
  await expect(page.getByText(/for this session/i)).toHaveCount(0);
});

test('main notes view, settings, editor, and selection mode expose accessible names', async ({
  page,
}) => {
  await page.setViewportSize({ width: 900, height: 800 });
  await continueAsGuest(page);

  await expect(page.getByRole('button', { name: /open settings/i })).toBeVisible();
  await expect(page.getByRole('button', { name: /new note/i }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /^filters$/i })).toBeVisible();

  await page.getByRole('button', { name: /open settings/i }).click();
  await expect(page.getByRole('dialog', { name: /settings/i })).toBeVisible();
  await page.getByRole('button', { name: /close settings/i }).click();

  await page.getByRole('button', { name: /new note/i }).first().click();
  const title = page.getByPlaceholder('Title').first();
  await expect(title).toBeVisible({ timeout: 15_000 });
  await expect(page.getByLabel(/note editor/i).first()).toBeVisible();
  const noteTitle = `A11y note ${Date.now()}`;
  await title.fill(noteTitle);
  await page.getByPlaceholder('Start writing…').first().fill('accessible body');
  await page.keyboard.press('Escape');
  const card = page.getByRole('button', { name: noteTitle, exact: true }).first();
  await expect(card).toBeVisible({ timeout: 20_000 });

  const box = await card.boundingBox();
  expect(box).not.toBeNull();
  await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.waitForTimeout(550);
  await page.mouse.up();

  await expect(page.getByRole('button', { name: /clear selection/i })).toBeVisible({
    timeout: 10_000,
  });
  await expect(page.getByRole('button', { name: /move to trash/i })).toBeVisible();
});
