import { expect, test, type Page } from '@playwright/test';
import { createNoteWithTitle } from './helpers/newNote';

/**
 * Keyboard shortcuts belong to whatever holds the keyboard. A main-screen key must not act behind
 * an open dialog, a shortcut with nothing to do must not swallow the key (Ctrl+F is the browser's
 * find bar), and Escape in the list's search box must not close a note docked beside it.
 */

test.beforeEach(async ({ page }, info) => {
  // The docked editor and Ctrl+F are desktop behaviour; the dialog case is the same on both.
  test.skip(info.project.name !== 'chromium');
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
});

/** Presses [key] and reports whether anything called preventDefault on it. */
async function pressAndCheckPrevented(page: Page, key: string): Promise<boolean> {
  await page.evaluate(() => {
    const w = window as unknown as { lastKeyPrevented?: boolean };
    delete w.lastKeyPrevented;
    // Registered after the app's own listener, so it sees the outcome. A chord sends the modifier's
    // own keydown first; only the key that completes it counts.
    const record = (event: KeyboardEvent) => {
      if (['Control', 'Meta', 'Shift', 'Alt'].includes(event.key)) return;
      w.lastKeyPrevented = event.defaultPrevented;
      window.removeEventListener('keydown', record);
    };
    window.addEventListener('keydown', record);
  });
  await page.keyboard.press(key);
  return page.evaluate(() => (window as unknown as { lastKeyPrevented?: boolean }).lastKeyPrevented ?? false);
}

test('N does nothing behind an open dialog', async ({ page }) => {
  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  const settings = page.getByRole('dialog').first();
  await expect(settings).toBeVisible();

  await page.keyboard.press('n');
  // Nothing should happen, so give a wrong outcome the time to show before looking.
  await page.waitForTimeout(500);
  await page.keyboard.press('Escape');

  await expect(settings).toBeHidden();
  await expect(page.getByRole('textbox', { name: 'Note body' })).toHaveCount(0);
});

test('Ctrl+F and N inside an open note are left to the browser', async ({ page }) => {
  await createNoteWithTitle(page, 'Keyboard');
  await page.getByRole('button', { name: 'Keyboard', exact: true }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible();

  expect(await pressAndCheckPrevented(page, 'Control+f')).toBe(false);
  // N belongs to the list, not to an open note; with focus on a button it is no one's to swallow.
  expect(await pressAndCheckPrevented(page, 'n')).toBe(false);
});

test('Escape in the search box leaves a docked note open', async ({ page }) => {
  await createNoteWithTitle(page, 'Docked');
  await page.getByRole('button', { name: 'Docked', exact: true }).first().click();
  await page.getByRole('radio', { name: 'Dock note' }).click();
  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible();

  await page.getByPlaceholder('Search notes').click();
  await page.keyboard.press('Escape');
  // The editor should stay, so give a close the time to happen before looking.
  await page.waitForTimeout(500);

  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible();
});
