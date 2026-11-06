import { expect, test } from '@playwright/test';
import type { Note } from '../src/types/note';

test('opening and closing an older note does not change its date', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible();
  const older: Note = {
    id: '1', localId: 1, title: 'Older note', content: 'Reading is not editing.',
    timestamp: Date.UTC(2020, 0, 1), serverUpdatedAt: null, position: 0, color: 0,
    isPinned: false, isArchived: false, isTrashed: false, reminderTimestamp: null,
    labels: [], attachments: [], checklist: [],
  };
  await page.evaluate((note) => {
    const store = (window as unknown as {
      __NOTELIKEUS_NOTES_STORE__: { getState: () => { setNotes: (notes: Note[]) => void } };
    }).__NOTELIKEUS_NOTES_STORE__;
    store.getState().setNotes([note]);
  }, older);
  await page.locator('[data-note-open="1"]').click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toHaveValue('Older note');
  await page.keyboard.press('Escape');
  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveCount(0);
  const timestamp = await page.evaluate(() => {
    const store = (window as unknown as {
      __NOTELIKEUS_NOTES_STORE__: { getState: () => { notes: Note[] } };
    }).__NOTELIKEUS_NOTES_STORE__;
    return store.getState().notes.find((note) => note.id === '1')?.timestamp;
  });
  expect(timestamp).toBe(older.timestamp);
});
