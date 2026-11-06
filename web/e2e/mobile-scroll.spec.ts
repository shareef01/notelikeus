import { expect, test, type Page } from '@playwright/test';
import type { Note } from '../src/types/note';

test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

async function swipeUp(page: Page, x: number, y: number, distance = 220) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  for (let step = 1; step <= 10; step++) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - distance * step / 10 }] });
    await page.evaluate(() => new Promise(requestAnimationFrame));
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible();
});

for (const view of ['Grid', 'Compact', 'List']) {
 test('vertical touch scrolling in ' + view + ' never opens or acts on a note', async ({ page }) => {
  for (let attempt = 0; attempt < 3 && !await page.getByRole('button', { name: 'View: ' + view + '. Tap to change', exact: true }).isVisible(); attempt++) {
    await page.getByRole('button', { name: /^View:/ }).click();
  }
  const notes: Note[] = Array.from({ length: 60 }, (_, i) => ({
    id: `scroll-${i}`, localId: i + 1, title: `Scroll note ${i}`, content: 'A note card for native mobile touch scrolling.\nMore words to fill the card.',
    timestamp: 1700000000000 + i, serverUpdatedAt: null, position: i, color: 0,
    isPinned: false, isArchived: false, isTrashed: false, reminderTimestamp: null,
    labels: [], attachments: [], checklist: [],
  }));
  await page.evaluate(notes => {
    (window as unknown as { __NOTELIKEUS_NOTES_STORE__: { getState(): { setNotes(notes: Note[]): void } } }).__NOTELIKEUS_NOTES_STORE__.getState().setNotes(notes);
  }, notes);
  const card = page.locator('article').first();
  await expect(card).toBeVisible();
  const bounds = (await card.boundingBox())!;
  await swipeUp(page, bounds.x + bounds.width / 2, Math.min(bounds.y + bounds.height - 15, 650), 130);
  await expect(page.getByRole('dialog', { name: 'Note editor', exact: true })).toHaveCount(0);

  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(50);
  await expect(page.getByRole('button', { name: 'Clear selection', exact: true })).toHaveCount(0);
  await expect(page.locator('header.shadow-header-scroll')).toBeVisible();
  const position = await page.evaluate(() => window.scrollY);
  await page.locator('[data-note-open]').nth(1).tap();
  await expect(page.getByRole('dialog', { name: 'Note editor', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Note editor', exact: true })).toHaveCount(0);
  // Include the asynchronous browser traversal, which can restore scroll after
  // the editor has already unmounted and released its body lock.
  await page.waitForFunction(() => !history.state?.notelikeusEditor);
  expect(await page.evaluate(() => window.scrollY)).toBeCloseTo(position, 0);
 });
}

test('a long note scrolls inside the editor without moving the notes underneath', async ({ page }) => {
  await page.getByRole('button', { name: 'New note' }).first().click();
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill(Array.from({ length: 70 }, (_, i) => `Line ${i + 1}: keep scrolling through this long note.`).join('\n'));
  await page.getByRole('textbox', { name: 'Note title' }).fill('Long scrollable note');
  const scroller = page.getByRole('dialog', { name: 'Note editor', exact: true }).locator('.overflow-y-auto');
  await scroller.evaluate(element => { element.scrollTop = 0; });
  await swipeUp(page, 180, 550);
  await expect.poll(() => scroller.evaluate(element => element.scrollTop)).toBeGreaterThan(100);
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await expect(page.getByRole('button', { name: 'Back', exact: true })).toBeInViewport();
});

test('the typing caret stays above the footer when the keyboard covers the viewport', async ({ page }) => {
  await page.getByRole('button', { name: 'New note' }).first().click();
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill(Array.from({ length: 50 }, (_, i) => `Paragraph ${i}: writing with the keyboard open.`).join('\n'));
  // A headless browser has no OS keyboard. Reproduce Chrome's visual-viewport
  // resize while retaining the full layout viewport underneath it.
  await page.evaluate(() => {
    const viewport = window.visualViewport!;
    Object.defineProperty(viewport, 'height', { configurable: true, get: () => 420 });
    viewport.dispatchEvent(new Event('resize'));
  });
  await body.press('Control+End');
  await body.pressSequentially(' more text');
  const caretBottom = await page.evaluate(() => {
    const selection = window.getSelection()!;
    const range = selection.getRangeAt(0).cloneRange();
    range.collapse(false);
    return range.getBoundingClientRect().bottom;
  });
  const footer = await page.getByRole('dialog', { name: 'Note editor', exact: true }).locator('footer').boundingBox();
  expect(caretBottom).toBeGreaterThan(0);
  expect(caretBottom).toBeLessThan(footer!.y - 4);
});
