import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * The keyboard contract every dialog claims by using `role="dialog"` and `aria-modal`.
 *
 * axe can tell you a dialog has an accessible name; it cannot tell you Escape closes it, that
 * focus went inside it, or that focus came back to whatever opened it. Those are the parts a
 * keyboard or screen-reader user actually depends on, so they are asserted directly here.
 *
 * Driven through guest mode, so this needs no backend.
 */

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

async function openEditor(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'New note' }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({
    timeout: 15_000,
  });
}

/** Whether focus currently sits inside the given dialog. */
function focusIsInside(page: Page, dialogName: string) {
  return page.evaluate((name) => {
    const dialogs = [...document.querySelectorAll('[role="dialog"]')];
    const target = dialogs.find(
      (node) =>
        node.getAttribute('aria-label') === name ||
        document.getElementById(node.getAttribute('aria-labelledby') ?? '')?.textContent?.trim() ===
          name,
    );
    return target != null && document.activeElement != null && target.contains(document.activeElement);
  }, dialogName);
}

test('the link dialog traps focus, closes on Escape, and returns to the note body', async ({
  page,
}) => {
  await openEditor(page);
  await page.getByRole('button', { name: 'Link', exact: true }).click();

  const dialog = page.getByRole('dialog', { name: /link/i });
  await expect(dialog).toBeVisible();
  expect(await focusIsInside(page, 'Add link')).toBe(true);

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  // Back to the body rather than the toolbar button: the toolbar suppresses its own focus on
  // pointerdown so a tap cannot blur the text being formatted, so the body is where the user
  // was and where the caret belongs.
  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeFocused();
});

test('the reminder picker traps focus, closes on Escape, and gives focus back', async ({
  page,
}) => {
  await openEditor(page);
  const trigger = page.getByRole('button', { name: 'Set reminder' });
  await trigger.click();

  const dialog = page.getByRole('dialog', { name: /reminder/i });
  await expect(dialog).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test('the options sheet closes on Escape and gives focus back', async ({ page }) => {
  await openEditor(page);
  const trigger = page.getByRole('button', { name: 'More options' });
  await trigger.click();

  const sheet = page.getByRole('dialog', { name: 'Note options' });
  await expect(sheet).toBeVisible();
  expect(await focusIsInside(page, 'Note options')).toBe(true);

  await page.keyboard.press('Escape');
  await expect(sheet).toHaveCount(0);
  await expect(trigger).toBeFocused();
});

test('Escape on a nested confirm closes only the confirm, not the sheet under it', async ({
  page,
}) => {
  await openEditor(page);
  await page.getByRole('textbox', { name: 'Note title' }).first().fill('Has a delete confirm');
  await page.getByRole('button', { name: 'More options' }).click();
  await expect(page.getByRole('dialog', { name: 'Note options' })).toBeVisible();

  await page.getByRole('button', { name: /delete/i }).first().click();
  const confirm = page.getByRole('dialog', { name: 'Delete note?' });
  await expect(confirm).toBeVisible();

  await page.keyboard.press('Escape');

  // One Escape, one dialog: the sheet underneath must survive, or a mis-tap on Delete would
  // dismiss everything and lose the user's place.
  await expect(confirm).toHaveCount(0);
  await expect(page.getByRole('dialog', { name: 'Note options' })).toBeVisible();
});

test('every editor dialog exposes an accessible name and no WCAG violations', async ({ page }) => {
  await openEditor(page);

  for (const [trigger, dialogPattern] of [
    ['Link', /link/i],
    ['Set reminder', /reminder/i],
  ] as const) {
    await page.getByRole('button', { name: trigger, exact: true }).click();
    const dialog = page.getByRole('dialog', { name: dialogPattern });
    await expect(dialog).toBeVisible();

    const scan = await new AxeBuilder({ page }).withTags(WCAG).analyze();
    expect(scan.violations, `violations while ${trigger} dialog open`).toEqual([]);

    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  }
});

test('Tab stays inside an open dialog', async ({ page }) => {
  await openEditor(page);
  await page.getByRole('button', { name: 'Set reminder' }).click();
  await expect(page.getByRole('dialog', { name: /reminder/i })).toBeVisible();

  // Tabbing further than the dialog holds must wrap rather than walk out into the editor behind
  // it, which is the whole point of aria-modal.
  for (let index = 0; index < 12; index++) {
    await page.keyboard.press('Tab');
    expect(await focusIsInside(page, 'Set reminder')).toBe(true);
  }
});
