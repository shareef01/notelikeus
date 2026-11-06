import { expect, test } from '@playwright/test';
import { selectNoteText as select } from './helpers/richNoteSelection';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'New note' }).first().click();
});

test('combined formatting stays visible while typing and survives reopening', async ({ page }, testInfo) => {
  await page.getByRole('textbox', { name: 'Note title' }).fill('A calmer place for your ideas');
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('Make these ideas stand out.');
  await select(body, 11, 16);
  await page.getByRole('button', { name: 'Bold', exact: true }).click();
  await page.getByRole('button', { name: 'Italic', exact: true }).click();
  await expect(body.locator('strong em, em strong')).toHaveText('ideas');
  await expect(page.getByRole('button', { name: 'Bold', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('textbox', { name: 'Note title' }).focus();
  await expect(body).toBeVisible();
  await page.screenshot({ path: `test-results/editor-after-${testInfo.project.name}.png` });
  await body.press('Escape');
  await page.getByRole('button', { name: 'A calmer place for your ideas', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Note body' }).locator('strong em, em strong')).toHaveText('ideas');
});

test('bold toggles cleanly and keyboard undo restores it', async ({ page }) => {
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('A clear idea');
  await select(body, 8, 12);
  const bold = page.getByRole('button', { name: 'Bold', exact: true });
  await bold.click();
  await expect(body.locator('strong')).toHaveText('idea');
  await bold.click();
  await expect(body.locator('strong')).toHaveCount(0);
  await expect(body).toHaveText('A clear idea');
  await body.press('Control+b');
  await expect(body.locator('strong')).toHaveText('idea');
});

test('links preserve the selected words and list button toggles', async ({ page }) => {
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('Read this later');
  await select(body, 5, 9);
  await page.getByRole('button', { name: 'Link', exact: true }).click();
  await page.getByRole('textbox', { name: 'Link URL' }).fill('example.com');
  await page.getByRole('button', { name: 'OK', exact: true }).click();
  await expect(body.locator('a')).toHaveText('this');
  await expect(body.locator('a')).toHaveAttribute('href', 'https://example.com');
  await page.getByRole('button', { name: 'Bullet list', exact: true }).click();
  await expect(body.locator('ul li')).toHaveText('Read this later');
  await page.getByRole('button', { name: 'Bullet list', exact: true }).click();
  await expect(body.locator('ul')).toHaveCount(0);
});

test('checklist conversion uses visible text and typed checklist shortcut still works', async ({ page }) => {
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('A clear idea');
  await select(body, 8, 12);
  await page.getByRole('button', { name: 'Bold', exact: true }).click();
  await page.getByRole('button', { name: 'Checklist', exact: true }).click();
  await expect(page.getByPlaceholder('List item')).toHaveValue('A clear idea');
});

test('typed checklist shortcut starts a structured checklist', async ({ page }) => {
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.pressSequentially('[] ');
  await expect(page.getByPlaceholder('List item')).toBeVisible();
});

test('undo removes a formatting change without removing the words', async ({ page }) => {
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('Keep these words');
  await page.waitForTimeout(600); // separate typing and formatting undo groups
  await select(body, 5, 10);
  await page.getByRole('button', { name: 'Bold', exact: true }).click();
  await expect(body.locator('strong')).toHaveText('these');
  await body.press('Control+z');
  await expect(body.locator('strong')).toHaveCount(0);
  await expect(body).toHaveText('Keep these words');
});
