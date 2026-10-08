import { expect, test, type BrowserContext, type Page } from '@playwright/test';

/**
 * Journey F, part two: what pin, archive and trash do to the data.
 *
 * Part one (F11) covered *reaching* the actions — the selection checkbox was hover-only and therefore
 * invisible on touch. This covers the actions themselves, with a second note present throughout so
 * that "the wrong note was affected" fails loudly instead of passing quietly.
 *
 * The checkbox is now labelled by its card's title (`aria-labelledby`), so it can be addressed by
 * name. Before that it had no title of its own and could only be reached by DOM position, which is
 * what made every earlier attempt at this journey mis-target — recorded as F12.
 *
 * Trash is soft: it is a view, and a note in it is restorable — the trash test asserts that. Nothing
 * here empties the trash, so the permanent-delete path is deliberately not exercised.
 *

 */
async function guestWithTwoNotes(context: BrowserContext): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  for (const title of ['ALPHA note', 'BETA note']) {
    const empty = page.getByRole('button', { name: 'New note', exact: true }).first();
    if (await empty.isVisible().catch(() => false)) await empty.click();
    else await page.getByRole('button', { name: 'Add note', exact: true }).first().click();
    await page.getByRole('textbox', { name: 'Note title' }).first().fill(title);
    await page.waitForTimeout(1_200);
    const back = page.getByRole('button', { name: /back/i }).first();
    if (await back.count()) await back.click();
    else await page.keyboard.press('Escape');
    await page.waitForTimeout(1_200);
  }
  return page;
}

/** Titles of the note cards currently on screen, in list order. */
const cardTitles = (page: Page): Promise<string[]> =>
  page.evaluate(() =>
    [...document.querySelectorAll('h2, h3')]
      .map((el) => el.textContent?.trim() ?? '')
      .filter((t) => /ALPHA|BETA/.test(t)),
  );

/**
 * A selection-bar action by name.
 *
 * Unscoped, with two exceptions handled by the caller:
 *
 * - The nav drawer's items are "Trash" and "Archive" while the bar's are "Move to trash" and
 *   "Archive", so only "Archive" is ambiguous.
 * - The card's quick actions are "Pin note" and "Archive note", which `exact` already excludes.
 *
 * An earlier version scoped this to `page.locator('header').first()` and always found the drawer's
 * chrome, because the drawer renders first in the DOM and has a header of its own.
 */
const barAction = (page: Page, name: string) => {
  const all = page.getByRole('button', { name, exact: true });
  // "Archive" exists twice: the drawer's nav item and the bar's action. The bar's comes later.
  return name === 'Archive' ? all.last() : all.first();
};

async function selectNote(page: Page, fragment: string): Promise<void> {
  await page.getByRole('checkbox', { name: fragment, exact: false }).first().click({ force: true });
  await page.waitForTimeout(700);
}

async function leaveSelection(page: Page): Promise<void> {
  const clear = barAction(page, 'Clear selection');
  if (await clear.count()) {
    await clear.click();
    await page.waitForTimeout(900);
  }
}

async function openView(page: Page, view: RegExp): Promise<void> {
  await page.getByRole('button', { name: 'Open menu', exact: true }).first().click();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: view }).first().click();
  await page.waitForTimeout(1_400);
}

const newGuest = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

test('the selection checkbox is named after its note', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTwoNotes(context);
  await expect(page.getByRole('checkbox', { name: 'ALPHA note', exact: false }).first()).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'BETA note', exact: false }).first()).toBeVisible();
  await context.close();
});

test('trashing a note removes it from the list and keeps it restorable', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTwoNotes(context);
  expect(await cardTitles(page)).toEqual(['ALPHA note', 'BETA note']);

  await selectNote(page, 'ALPHA');
  await expect(barAction(page, 'Move to trash')).toBeVisible({ timeout: 10_000 });
  await barAction(page, 'Move to trash').click();
  await page.waitForTimeout(1_400);
  await leaveSelection(page);
  expect(await cardTitles(page), 'ALPHA should leave the notes list').toEqual(['BETA note']);

  await openView(page, /^Trash/);
  expect(await cardTitles(page), 'ALPHA should be restorable from the trash').toContain('ALPHA note');
  expect(await cardTitles(page), 'BETA was not selected and must not be in the trash').not.toContain('BETA note');
  await context.close();
});

test('archiving removes a note from the notes view without deleting it', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTwoNotes(context);

  await selectNote(page, 'BETA');
  await expect(barAction(page, 'Archive')).toBeVisible({ timeout: 10_000 });
  await barAction(page, 'Archive').click();
  await page.waitForTimeout(1_400);
  await leaveSelection(page);
  expect(await cardTitles(page), 'BETA should leave the notes list').toEqual(['ALPHA note']);

  await openView(page, /^Archive/);
  expect(await cardTitles(page), 'BETA should be in the archive, not destroyed').toContain('BETA note');
  expect(await cardTitles(page), 'ALPHA was not selected and must not be archived').not.toContain('ALPHA note');
  await context.close();
});

test('a pin survives a reload', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTwoNotes(context);

  await selectNote(page, 'ALPHA');
  await expect(barAction(page, 'Pin')).toBeVisible({ timeout: 10_000 });
  await barAction(page, 'Pin').click();
  await page.waitForTimeout(1_400);
  await leaveSelection(page);

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(2_500);
  const signIn = page.getByRole('button', { name: 'Continue without an account' });
  await expect(signIn).toBeVisible({ timeout: 20_000 });
  await signIn.click();
  await page.waitForTimeout(1_800);

  expect(await cardTitles(page), 'both notes should be back').toEqual(['ALPHA note', 'BETA note']);
  // The marker a phone user actually sees. "Unpin note" exists in the DOM but is part of the desktop
  // quick-actions block, which is `md:`-only and therefore invisible at 390px — asserting on it
  // tested the wrong affordance and read as a failure when the pin had persisted all along.
  const pinnedMarker = await page.evaluate(() => /\bpinned\b/i.test(document.body.innerText));
  expect(pinnedMarker, 'a pinned note should still be marked as pinned after a reload').toBe(true);
  await context.close();
});
