import { expect, test } from '@playwright/test';

/**
 * Archiving or trashing a note from its card's quick actions removes the card that held focus.
 * Focus used to fall to <body>, sending a keyboard user back to the top of the page; it now moves
 * to the card that takes the removed one's place.
 */
test('focus moves to the next card when a card leaves the list', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  for (const title of ['Second', 'First']) {
    await page.getByRole('button', { name: 'New note' }).first().click();
    await page.getByRole('textbox', { name: 'Note title' }).first().fill(title);
    await page.getByRole('button', { name: 'Back' }).click();
  }

  const archive = page
    .locator('article')
    .filter({ has: page.getByRole('button', { name: /^First/ }) })
    .getByRole('button', { name: 'Archive note' });
  await archive.focus();
  await page.keyboard.press('Enter');

  await expect(page.getByRole('button', { name: /^First/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^Second/ }).first()).toBeFocused();
});
