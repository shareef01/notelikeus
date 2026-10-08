import { expect, test, type BrowserContext, type Page } from '@playwright/test';

/**
 * The irreversible path: permanent deletion.
 *
 * Everything else in this product is recoverable — trash is a view, deletes are soft (F12). These two
 * dialogs are the only places a note is destroyed, so they are the ones worth guarding.
 *
 * What is asserted here was measured before it was written, and the answer was that the product
 * already does this well: the count matches reality, the copy says "cannot be undone", focus starts on
 * Cancel rather than the destructive button, the confirm is visually distinct, and the dialog is named
 * through `aria-labelledby` (reading `aria-label` returns null and looks like a missing name — it is
 * not, and that false alarm is recorded in the audit as F13).
 *
 * The test that matters most is the first one: cancelling must destroy nothing.
 */
async function guestWithTrashedNotes(context: BrowserContext, titles: string[]): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  for (const title of titles) {
    const empty = page.getByRole('button', { name: 'New note', exact: true }).first();
    if (await empty.isVisible().catch(() => false)) await empty.click();
    else await page.getByRole('button', { name: 'Add note', exact: true }).first().click();
    await page.getByRole('textbox', { name: 'Note title' }).first().fill(title);
    await page.waitForTimeout(1_200);
    const back = page.getByRole('button', { name: /\bback\b/i }).first();
    if (await back.count()) await back.click();
    else await page.keyboard.press('Escape');
    await page.waitForTimeout(1_200);
  }

  for (const title of titles) {
    await page.getByRole('checkbox', { name: title, exact: false }).first().click({ force: true });
    await page.waitForTimeout(700);
    await page.getByRole('button', { name: 'Move to trash', exact: true }).first().click();
    await page.waitForTimeout(1_400);
    const clear = page.getByRole('button', { name: 'Clear selection', exact: true });
    if (await clear.count()) {
      await clear.click();
      await page.waitForTimeout(900);
    }
  }

  await page.getByRole('button', { name: 'Open menu', exact: true }).first().click();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: /^Trash/ }).first().click();
  await page.waitForTimeout(1_400);
  return page;
}

const newGuest = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

test('the confirmation states the real count, says it cannot be undone, and starts focus on Cancel', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTrashedNotes(context, ['PERM one', 'PERM two']);

  await page.getByRole('button', { name: /empty trash/i }).first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 10_000 });

  // The count has to describe what will actually be destroyed.
  await expect(dialog).toContainText('2 notes will be deleted permanently');
  await expect(dialog).toContainText('cannot be undone');

  // Focus on the safe option, not the destructive one: a stray Enter must not delete anything.
  const focused = await page.evaluate(() => document.activeElement?.textContent?.trim());
  expect(focused, 'focus should start on Cancel').toBe('Cancel');

  // Distinct styling for the irreversible action. Asserting the *property* — the two buttons must not
  // look alike — rather than a colour literal: an earlier version hard-coded rgb and failed against
  // Tailwind v4's oklch output, which is my regex being brittle rather than the product being wrong.
  const backgrounds = await dialog.evaluate((el) => {
    const buttons = [...el.querySelectorAll('button')];
    const find = (re: RegExp) => buttons.find((b) => re.test((b.textContent ?? '').trim()));
    const safe = find(/^Cancel$/);
    const destructive = buttons.find((b) => /empty trash/i.test(b.textContent ?? ''));
    return {
      safe: safe ? getComputedStyle(safe).backgroundColor : null,
      destructive: destructive ? getComputedStyle(destructive).backgroundColor : null,
    };
  });
  expect(backgrounds.safe).toBeTruthy();
  expect(backgrounds.destructive, 'the destructive action must be styled differently from Cancel').not.toBe(backgrounds.safe);

  // And the dialog is named, via aria-labelledby rather than aria-label.
  const labelled = await dialog.evaluate((el) => el.getAttribute('aria-labelledby'));
  expect(labelled, 'the dialog should be named through its title').toBeTruthy();
  await context.close();
});

test('cancelling the confirmation destroys nothing', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithTrashedNotes(context, ['PERM three']);

  await page.getByRole('button', { name: /empty trash/i }).first().click();
  await expect(page.getByRole('dialog')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('dialog')).toContainText('1 note will be deleted permanently');

  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();
  await page.waitForTimeout(1_200);

  // Escape from a mistake must be a no-op. The note is still in the trash.
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByText('PERM three').first()).toBeVisible({ timeout: 10_000 });
  await context.close();
});
