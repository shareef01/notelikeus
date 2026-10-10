import { expect, test, type Page } from '@playwright/test';
import { createNoteWithTitle } from './helpers/newNote';

/**
 * The sort control used to cycle through its orders on every tap, so the destination was not
 * visible before the action and a user who wanted the far end of the list had to press, read the
 * toast, and press again if they guessed wrong.
 *
 * It now opens a chooser listing all three destinations with the active one marked. These tests
 * hold that contract, and the last one checks the thing that actually matters: that choosing a
 * destination changes the order of the notes, not just the label on the button.
 */
async function enterNotes(page: Page, width = 390): Promise<void> {
  await page.setViewportSize({ width, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
}

const sortChip = (page: Page) => page.getByRole('button', { name: /^Sort: / });

test('the sort chip offers every destination instead of cycling through them', async ({ page }) => {
  await enterNotes(page);
  await expect(sortChip(page)).toHaveAttribute('aria-label', /Sort: Newest/);

  await sortChip(page).click();
  const sheet = page.getByRole('dialog', { name: 'Sort notes' });
  await expect(sheet).toBeVisible();

  // All three destinations, listed, before anything is chosen.
  await expect(page.getByRole('button', { name: /^Newest first/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: /^Oldest first/ })).toHaveAttribute('aria-pressed', 'false');
});

test('choosing a destination applies it and closes the chooser', async ({ page }) => {
  await enterNotes(page);
  await sortChip(page).click();
  await page.getByRole('button', { name: /^Oldest first/ }).click();

  await expect(page.getByRole('dialog', { name: 'Sort notes' })).toHaveCount(0);
  await expect(sortChip(page)).toHaveAttribute('aria-label', /Sort: Oldest/);

  // Reopening shows the new choice as the active one — the state is real, not just the label.
  await sortChip(page).click();
  await expect(page.getByRole('button', { name: /^Oldest first/ })).toHaveAttribute('aria-pressed', 'true');
  // Not merely unpressed: not offered. The web client cannot reorder notes (F18), and the
  // decision was to stop advertising an order it cannot produce.
  await expect(page.getByRole('button', { name: /^Manual/ })).toHaveCount(0);
});

test('the order of the notes themselves follows the choice', async ({ page }) => {
  await enterNotes(page);

  // Two notes, written in a known order, so newest-first and oldest-first must disagree.
  // The helper follows the same path a user would: the empty state's "New note" first, then the
  // floating "Add note" once notes exist.
  for (const title of ['Alpha first', 'Beta second']) await createNoteWithTitle(page, title);

  // Which note sits higher on screen is the order, without depending on the card's markup — an
  // earlier version of this assertion matched the "LIBRARY" section heading instead of a note.
  const topOf = async (title: string): Promise<number> => {
    const box = await page.getByText(title, { exact: false }).filter({ visible: true }).first().boundingBox();
    return box?.y ?? Number.NaN;
  };

  await sortChip(page).click();
  await page.getByRole('button', { name: /^Oldest first/ }).click();
  await page.waitForTimeout(900);
  const oldestAlphaY = await topOf('Alpha first');
  const oldestBetaY = await topOf('Beta second');
  expect(Number.isNaN(oldestAlphaY) || Number.isNaN(oldestBetaY), 'both notes should be on screen').toBe(false);
  expect(oldestAlphaY, 'oldest first puts Alpha first').toBeLessThan(oldestBetaY);

  await sortChip(page).click();
  await page.getByRole('button', { name: /^Newest first/ }).click();
  await page.waitForTimeout(900);
  const newestAlphaY = await topOf('Alpha first');
  const newestBetaY = await topOf('Beta second');
  expect(newestBetaY, 'newest first puts Beta first').toBeLessThan(newestAlphaY);
});

test('the settings group and the toolbar chip are one state, not two', async ({ page }) => {
  await enterNotes(page);

  // The settings row used to cycle on tap while the toolbar opened a chooser. Both choose now, from
  // the same store, so a change made in one has to be visible in the other.
  await page.getByRole('button', { name: 'Open settings' }).first().click();
  await expect(page.getByRole('group', { name: 'Sort order' })).toBeVisible({ timeout: 15_000 });

  await expect(page.getByRole('button', { name: 'Newest first', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Oldest first', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Oldest first', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('button', { name: 'Newest first', exact: true })).toHaveAttribute('aria-pressed', 'false');

  await page.keyboard.press('Escape');
  await expect(sortChip(page)).toHaveAttribute('aria-label', /Sort: Oldest/);
});
