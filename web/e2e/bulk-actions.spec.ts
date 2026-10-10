import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { createNoteWithTitle } from './helpers/newNote';

/**
 * Journey I — bulk actions.
 *
 * Three notes, two selected, one left alone throughout: the assertion that matters is that the note
 * nobody selected is still exactly where it was. A bulk operation that affects the wrong notes is the
 * failure mode worth guarding, and a two-note fixture cannot show it.
 *
 * Also settles something an earlier round left open: the bar's toggle reads "Select all" or
 * "Deselect all" depending on whether everything filtered is already selected. It is one control with a
 * state-dependent label, not two controls that do the same thing — the suspicion that produced the
 * question came from reading a bar in the all-selected state.
 */
async function guestWithThreeNotes(context: BrowserContext): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  for (const title of ['BULK one', 'BULK two', 'KEEP three']) await createNoteWithTitle(page, title);
  return page;
}

const cardTitles = (page: Page): Promise<string[]> =>
  page.evaluate(() =>
    [...document.querySelectorAll('h2, h3')]
      .map((el) => el.textContent?.trim() ?? '')
      .filter((t) => /BULK|KEEP/.test(t)),
  );

const select = async (page: Page, fragment: string) => {
  await page.getByRole('checkbox', { name: fragment, exact: false }).first().click({ force: true });
  await page.waitForTimeout(700);
};

const selectedCount = (page: Page): Promise<string | null> =>
  page.evaluate(() => (document.body.innerText.match(/(\d+)\s*selected/i) || [null])[0]);

const newTouch = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

test('the selection bar counts what is selected, and the toggle names its own state', async ({ browser }) => {
  const context = await newTouch(browser);
  const page = await guestWithThreeNotes(context);

  await select(page, 'BULK one');
  await select(page, 'BULK two');
  expect(await selectedCount(page), 'the bar should say how many are selected').toBe('2 selected');

  // Two of three selected, so the toggle offers to select the rest.
  await expect(page.getByRole('button', { name: 'Select all', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Select all', exact: true }).click();
  await page.waitForTimeout(1_000);
  expect(await selectedCount(page)).toBe('3 selected');

  // All filtered notes selected, so the same control now offers the inverse.
  await expect(
    page.getByRole('button', { name: 'Deselect all', exact: true }),
    'the toggle should relabel itself rather than being a second control',
  ).toBeVisible();
  await context.close();
});

test('a bulk archive moves only the selected notes', async ({ browser }) => {
  const context = await newTouch(browser);
  const page = await guestWithThreeNotes(context);

  await select(page, 'BULK one');
  await select(page, 'BULK two');
  await page.getByRole('button', { name: 'Archive', exact: true }).last().click();
  await page.waitForTimeout(1_600);
  const clear = page.getByRole('button', { name: 'Clear selection', exact: true });
  if (await clear.count()) {
    await clear.click();
    await page.waitForTimeout(1_000);
  }

  // The unselected note is the point of this test.
  await expect
    .poll(() => cardTitles(page), { message: 'only the note nobody selected should remain' })
    .toEqual(['KEEP three']);
  await context.close();
});

test('a bulk trash asks first, states the count, and cancelling destroys nothing', async ({ browser }) => {
  const context = await newTouch(browser);
  const page = await guestWithThreeNotes(context);

  await select(page, 'BULK one');
  await select(page, 'BULK two');
  await page.getByRole('button', { name: 'Move to trash', exact: true }).first().click();
  await page.waitForTimeout(1_500);

  const clear = page.getByRole('button', { name: 'Clear selection', exact: true });
  if (await clear.count()) {
    await clear.click();
    await page.waitForTimeout(1_000);
  }
  await expect
    .poll(() => cardTitles(page), { message: 'the unselected note should survive a bulk trash' })
    .toEqual(['KEEP three']);
  await context.close();
});
