import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import fs from 'node:fs';

/**
 * Backup round trip, both formats.
 *
 * F16 verified the `.nlkbak` bundle by hand and left the JSON-only path unverified: the two export
 * controls are different formats, and a hand check of one says nothing about the other. This covers
 * both, so "backup works" stops being an unqualified claim.
 *
 * Note the locators: both controls' accessible names concatenate the label with the subtitle inside
 * the button ("Export notes onlyDownload notes as JSON, without images"), so they are matched by
 * prefix. An exact-name locator matches nothing and silently clicks nothing, which is how two earlier
 * hand probes failed.
 *
 * The import copy is asserted too, because it is unusually good: count, consequence, and a duplicate
 * warning, stated before anything happens.
 */
async function guestWithPinnedNote(context: BrowserContext): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  await page.getByRole('button', { name: 'New note', exact: true }).first().click();
  await page.getByRole('textbox', { name: 'Note title' }).first().fill('BACKUP note');
  const body = page.getByRole('textbox', { name: 'Note body' }).first();
  if (await body.count()) await body.fill('round trip body');
  await page.waitForTimeout(1_300);
  const back = page.getByRole('button', { name: /\bback\b/i }).first();
  if (await back.count()) await back.click();
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(1_300);

  await page.getByRole('checkbox', { name: 'BACKUP note', exact: false }).first().click({ force: true });
  await page.waitForTimeout(700);
  await page.getByRole('button', { name: 'Pin', exact: true }).first().click();
  await page.waitForTimeout(1_200);
  const clear = page.getByRole('button', { name: 'Clear selection', exact: true });
  if (await clear.count()) {
    await clear.click();
    await page.waitForTimeout(900);
  }
  return page;
}

async function openSettings(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  await page.waitForTimeout(1_000);
}

async function exportVia(page: Page, prefix: RegExp, saveAs: string): Promise<Buffer> {
  const button = page.getByRole('button', { name: prefix }).first();
  expect(await button.count(), 'the export control should exist').toBeGreaterThan(0);
  const [download] = await Promise.all([page.waitForEvent('download', { timeout: 20_000 }), button.click()]);
  await download.saveAs(saveAs);
  return fs.readFileSync(saveAs);
}

async function importFile(page: Page, path: string): Promise<string | null> {
  const chooser = page.waitForEvent('filechooser', { timeout: 20_000 });
  await page.getByRole('button', { name: /^Import/ }).first().click();
  (await chooser).setFiles(path);
  await page.waitForTimeout(1_600);
  return page.evaluate(() => {
    const dialog = document.querySelector('[role="dialog"][aria-labelledby]');
    return dialog ? (dialog.textContent ?? '').replace(/\s+/g, ' ').trim() : null;
  });
}

const newGuest = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true, acceptDownloads: true });

test('both export controls produce the format they promise', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithPinnedNote(context);
  await openSettings(page);

  // JSON, per "Download notes as JSON, without images".
  const json = await exportVia(page, /^Export notes only/, '/tmp/e2e-notes-only.json');
  const text = json.toString('utf8');
  expect(text.startsWith('{'), 'the notes-only export should be JSON').toBe(true);
  const parsed = JSON.parse(text);
  expect(Array.isArray(parsed.notes), 'it should carry a notes array').toBe(true);
  expect(parsed.notes.length).toBe(1);
  expect(parsed.notes[0].title).toBe('BACKUP note');

  // The bundle, per "Download notes and images as one .nlkbak file".
  const bundle = await exportVia(page, /^Export backup with images/, '/tmp/e2e-complete.nlkbak');
  expect(bundle.subarray(0, 2).toString('latin1'), 'the bundle should be a zip').toBe('PK');
  await context.close();
});

test('a JSON export imports back into a fresh session with its fields intact', async ({ browser }) => {
  // Export from one guest.
  const source = await newGuest(browser);
  const exporter = await guestWithPinnedNote(source);
  await openSettings(exporter);
  await exportVia(exporter, /^Export notes only/, '/tmp/e2e-roundtrip.json');
  await source.close();

  // Import into a different one, so nothing can be satisfied by in-memory state.
  const target = await newGuest(browser);
  const page = await target.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.waitForTimeout(1_500);
  await openSettings(page);

  const copy = await importFile(page, '/tmp/e2e-roundtrip.json');
  expect(copy, 'the import should explain itself').toContain('1 note');
  expect(copy).toContain('another copy');

  // Confirm, then look for the note.
  await page.getByRole('button', { name: /^(Import|Add)/ }).last().click();
  await page.waitForTimeout(2_000);
  const restored = await page.evaluate(() => ({
    title: /BACKUP note/.test(document.body.innerText),
    pinned: /\bpinned\b/i.test(document.body.innerText),
  }));
  expect(restored.title, 'the title should survive the round trip').toBe(true);
  expect(restored.pinned, 'the pinned marker should survive the round trip').toBe(true);
  await target.close();
});
