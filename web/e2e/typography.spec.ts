import { expect, test, type Page } from '@playwright/test';
import { createNoteWithTitle, leaveEditor, openNewNote, typeNewNoteTitle } from './helpers/newNote';

/**
 * Typography contract (docs/design/DESIGN_SYSTEM.md §3, docs/UI_UX_AUDIT_2026.md).
 *
 * 1. No rendered text is smaller than 11px. 11px is the overline (uppercase section labels) and
 *    compact-density chips; everything else starts at the 12px metadata size. The audit of
 *    2026-11-23 found a 10px shortcut hint, an 11px drawer subtitle and an 11px attachment count.
 * 2. The editor uses its named roles: 26px title / 17px body on phones, 28px / 18px from 640px.
 * 3. A pathological note — a 400-character unbroken word in the title and the body — does not
 *    make the editor or the card list scroll sideways (WCAG 1.4.10).
 *
 * Guest mode only, so it runs without a backend.
 */

const MIN_TEXT_PX = 11;

async function enterGuestMode(page: Page) {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: /^(New note|Add note)$/ }).first()).toBeVisible({ timeout: 20_000 });
}

/** Every visible element that directly holds text, with its computed font size, smallest first. */
async function smallestText(page: Page) {
  return page.evaluate(() => {
    const found: { px: number; text: string; tag: string }[] = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node.textContent?.trim() ?? '';
      const el = node.parentElement;
      if (!text || !el) continue;
      const style = getComputedStyle(el);
      const rect = el.getBoundingClientRect();
      if (style.visibility === 'hidden' || style.display === 'none' || rect.width === 0 || rect.height === 0) continue;
      if (el.closest('[aria-hidden="true"]')) continue;
      found.push({ px: parseFloat(style.fontSize), text: text.slice(0, 40), tag: el.tagName });
    }
    return found.sort((a, b) => a.px - b.px).slice(0, 5);
  });
}

async function horizontalOverflow(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

for (const width of [375, 1280]) {
  test(`no text below ${MIN_TEXT_PX}px on the notes screen and in the editor at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await enterGuestMode(page);
    await createNoteWithTitle(page, 'Typography floor');

    const onList = await smallestText(page);
    expect(onList[0]?.px ?? 99, `smallest text on the notes screen: ${JSON.stringify(onList)}`).toBeGreaterThanOrEqual(MIN_TEXT_PX);

    // The card opens through its full-bleed overlay button, which sits above the heading.
    await page.getByRole('button', { name: 'Typography floor', exact: true }).first().click();
    await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });
    const inEditor = await smallestText(page);
    expect(inEditor[0]?.px ?? 99, `smallest text in the editor: ${JSON.stringify(inEditor)}`).toBeGreaterThanOrEqual(MIN_TEXT_PX);
  });
}

for (const [width, title, body] of [
  [375, 26, 17],
  [1280, 28, 18],
] as const) {
  test(`editor type roles at ${width}px: ${title}px title, ${body}px body`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await enterGuestMode(page);
    await openNewNote(page);
    const titleField = page.getByRole('textbox', { name: 'Note title' }).first();
    const bodyField = page.getByRole('textbox', { name: 'Note body' });
    await expect(titleField).toBeVisible({ timeout: 15_000 });
    const sizes = async (locator: typeof titleField) =>
      locator.evaluate((el) => ({ size: parseFloat(getComputedStyle(el).fontSize), weight: getComputedStyle(el).fontWeight }));
    expect(await sizes(titleField)).toEqual({ size: title, weight: '600' });
    expect((await sizes(bodyField)).size).toBe(body);
  });
}

for (const width of [320, 768, 1440]) {
  test(`an unbroken 400-character word does not scroll the page sideways at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await enterGuestMode(page);
    const word = 'x'.repeat(400);
    await openNewNote(page);
    await typeNewNoteTitle(page, word);
    await page.getByRole('textbox', { name: 'Note body' }).click();
    await page.keyboard.type(word);
    expect(await horizontalOverflow(page), `editor overflows at ${width}px`).toBeLessThanOrEqual(0);
    await leaveEditor(page);
    await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible({ timeout: 15_000 });
    expect(await horizontalOverflow(page), `card list overflows at ${width}px`).toBeLessThanOrEqual(0);
  });
}
