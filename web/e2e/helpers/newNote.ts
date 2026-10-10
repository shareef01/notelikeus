import { expect, type Page } from '@playwright/test';

/**
 * Creating a note the way a user does, without racing the editor.
 *
 * A new note's editor moves focus to the body textarea shortly after it mounts (an animation-frame
 * effect in EditorScreen), and `dialog-a11y.spec.ts` pins that as intended. A spec that fills the title
 * the instant the editor opens can therefore overlap that focus move on a slow runner. CI traces of the
 * `bulk-actions` flake show exactly that: `fill` resolved to the title input, yet the text ended up in
 * the body textarea. The card then had no title, so `getByRole('checkbox', { name: 'BULK two' })` never
 * existed and the test sat waiting for it until the 60-second timeout.
 *
 * So these helpers wait for the autofocus to land first, type the title only after that, and check the
 * title field really holds it. A bad save then fails on the spot, saying what went wrong.
 */

/** Opens a blank note from whichever entry point exists: the empty state's "New note", or the floating "Add note". */
export async function openNewNote(page: Page): Promise<void> {
  const emptyState = page.getByRole('button', { name: 'New note', exact: true }).first();
  if (await emptyState.isVisible().catch(() => false)) await emptyState.click();
  else await page.getByRole('button', { name: 'Add note', exact: true }).first().click();
}

/** Types the title of the note that was just opened, once the editor has finished moving focus. */
export async function typeNewNoteTitle(page: Page, title: string): Promise<void> {
  await expect(
    page.getByRole('textbox', { name: 'Note body' }),
    'a new note should have moved focus to its body before the title is typed',
  ).toBeFocused({ timeout: 15_000 });
  const titleField = page.getByRole('textbox', { name: 'Note title' }).first();
  await titleField.fill(title);
  await expect(titleField, 'the title field should hold what was typed, not the body').toHaveValue(title);
}

/** Leaves the editor the way a user does: the Back button, or Escape on layouts without one. */
export async function leaveEditor(page: Page): Promise<void> {
  const back = page.getByRole('button', { name: /\bback\b/i }).first();
  if (await back.count()) await back.click();
  else await page.keyboard.press('Escape');
}

/** Creates a note with [title] and returns once its card is in the list. */
export async function createNoteWithTitle(page: Page, title: string): Promise<void> {
  await openNewNote(page);
  await typeNewNoteTitle(page, title);
  await leaveEditor(page);
  await expect(
    page.getByRole('heading', { name: title, exact: true, level: 2 }).first(),
    `the card for "${title}" should be in the list`,
  ).toBeVisible({ timeout: 15_000 });
}
