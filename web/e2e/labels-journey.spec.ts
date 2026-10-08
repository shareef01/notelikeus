import { expect, test, type BrowserContext, type Page } from '@playwright/test';

/**
 * Journey G — labels: create, filter, and the consequence of deleting.
 *
 * Creating a label needs the explicit **Create** submit button; pressing Enter alone did not create it
 * in an earlier attempt, which then saw an empty table and looked like a product failure. That is
 * recorded in the audit so the next attempt does not repeat it.
 *
 * The created label must reach the notes screen's filter row. It did not (F15): the row's labels were
 * derived from the notes alone, so a label created before any note used it was invisible — listed in
 * the manager and unusable. `useNotes` now merges the registry, and the first test here is what would
 * have caught it.
 *
 * Deleting a label is guarded by `DeleteLabelDialog`, whose copy names the label, states the
 * consequence for the notes, and states irreversibility. The second test verifies that guard rather
 * than adding one.
 */
async function openLabels(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Open menu', exact: true }).first().click();
  await page.waitForTimeout(600);
  await page.getByRole('button', { name: /edit labels/i }).first().click();
  await page.waitForTimeout(1_200);
}

async function guestWithLabel(context: BrowserContext): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  await openLabels(page);
  await page.getByRole('textbox', { name: 'Create new label' }).fill('groceries');
  await page.waitForTimeout(400);
  // The submit button only exists once the field has text, and it is the reliable path.
  await page.locator('button[type="submit"]').first().click();
  await expect(page.getByText('groceries').first()).toBeVisible({ timeout: 10_000 });
  return page;
}

const labelChips = (page: Page): Promise<string[]> =>
  page.evaluate(() =>
    [...document.querySelectorAll('[aria-label="Label filter"] button')].map((el) =>
      (el.textContent ?? '').trim(),
    ),
  );

const closeLabels = async (page: Page) => {
  const close = page.getByRole('button', { name: 'Close', exact: true }).first();
  if (await close.count()) await close.click();
  await page.waitForTimeout(1_400);
};

const newGuest = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

test('a label created before any note uses it is filterable straight away', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithLabel(context);
  await closeLabels(page);

  // The regression F15 describes: created and listed in the manager, but absent from the filter row.
  expect(await labelChips(page), 'the new label should be offered as a filter').toContain('groceries');
  await context.close();
});

test('deleting a label states its consequence, and cancelling keeps it', async ({ browser }) => {
  const context = await newGuest(browser);
  const page = await guestWithLabel(context);

  await page.getByRole('button', { name: 'Delete label' }).first().click();
  // The labels screen is itself a dialog, so this one is named rather than matched by role alone.
  const dialog = page.getByRole('dialog', { name: 'Delete label?' });
  await expect(dialog).toBeVisible({ timeout: 10_000 });

  // The consequence, the label's name, and irreversibility — all three.
  await expect(dialog).toContainText('groceries');
  await expect(dialog).toContainText('removed from all notes');
  await expect(dialog).toContainText('cannot be undone');

  // A mistake must be escapable: cancel, and the label is still there to be filtered by.
  await page.getByRole('button', { name: 'Cancel', exact: true }).first().click();
  await page.waitForTimeout(1_000);
  await expect(page.getByRole('dialog', { name: 'Delete label?' })).toHaveCount(0);
  await expect(page.getByText('groceries').first()).toBeVisible({ timeout: 10_000 });
  await context.close();
});
