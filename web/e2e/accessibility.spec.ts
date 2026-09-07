import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * Accessibility smoke scans plus the keyboard contracts axe cannot prove.
 *
 * axe catches missing names and broken structure; it cannot tell whether a control that claims
 * `role="radio"` actually responds to the arrow keys that role promises. Both are covered here.
 */

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

function uniqueEmail(): string {
  return `e2e-a11y-${Date.now()}-${Math.floor(Math.random() * 10_000)}@example.com`;
}

const PASSWORD = 'e2e-password-123';

async function scan(page: Page) {
  // Freeze entry animations first. A scan that lands mid-fade measures a part-transparent
  // element composited over whatever is behind it and reports contrast failures that do not
  // exist once the frame settles — a race these scans win locally and can lose on CI.
  await page.addStyleTag({
    content: '*,*::before,*::after{animation:none !important;transition:none !important}',
  });
  return new AxeBuilder({ page }).withTags(WCAG).analyze();
}

async function signUp(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
  await page.locator('#test-login-email').fill(uniqueEmail());
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
}

async function openNewNote(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'New note' }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({
    timeout: 15_000,
  });
}

test('the entry screen has no WCAG violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  expect((await scan(page)).violations).toEqual([]);
});

test('the notes grid has no WCAG violations', async ({ page }) => {
  await signUp(page);

  expect((await scan(page)).violations).toEqual([]);
});

test('the editor has no WCAG violations', async ({ page }) => {
  await signUp(page);
  await openNewNote(page);

  expect((await scan(page)).violations).toEqual([]);
});

test('the checklist editor has no WCAG violations', async ({ page }) => {
  await signUp(page);
  await openNewNote(page);
  await page.getByRole('button', { name: /Add checklist|Convert to checklist/ }).first().click();

  expect((await scan(page)).violations).toEqual([]);
});

test('the editor options sheet has no WCAG violations', async ({ page }) => {
  await signUp(page);
  await openNewNote(page);
  await page.getByRole('textbox', { name: 'Note title' }).first().fill('Options sheet');
  await page.getByRole('button', { name: 'More options' }).first().click();

  expect((await scan(page)).violations).toEqual([]);
});

test('the note title and body expose accessible names, not just placeholders', async ({ page }) => {
  await signUp(page);
  await openNewNote(page);

  // A placeholder is a hint, not a label: it disappears the moment the field has content.
  await expect(page.getByRole('textbox', { name: 'Note title' })).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Note body' })).toBeVisible();

  await page.getByRole('textbox', { name: 'Note title' }).fill('Still named once filled');
  await expect(page.getByRole('textbox', { name: 'Note title' })).toBeVisible();
});

test('the editor layout radiogroup follows the keyboard contract its roles promise', async ({
  page,
}, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'Layout controls only render on tablet and up');
  await signUp(page);
  await openNewNote(page);

  const group = page.getByRole('radiogroup', { name: 'Editor layout' });
  await expect(group).toBeVisible();
  const options = group.getByRole('radio');
  const count = await options.count();
  expect(count).toBeGreaterThan(1);

  // Exactly one tab stop, as a radiogroup is meant to have.
  await expect(group.locator('[tabindex="0"]')).toHaveCount(1);

  await options.first().focus();
  await expect(options.first()).toBeFocused();

  await page.keyboard.press('ArrowRight');
  await expect(options.nth(1)).toBeFocused();
  await expect(options.nth(1)).toHaveAttribute('aria-checked', 'true');

  await page.keyboard.press('ArrowLeft');
  await expect(options.first()).toBeFocused();
  await expect(options.first()).toHaveAttribute('aria-checked', 'true');

  await page.keyboard.press('End');
  await expect(options.nth(count - 1)).toBeFocused();

  await page.keyboard.press('Home');
  await expect(options.first()).toBeFocused();
});

test('the editor dialog traps focus and Escape returns to the grid', async ({ page }) => {
  await signUp(page);
  await openNewNote(page);

  const dialog = page.getByRole('dialog', { name: 'Note editor' });
  await expect(dialog).toBeVisible();

  await page.keyboard.press('Escape');
  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveCount(0, {
    timeout: 20_000,
  });
});
