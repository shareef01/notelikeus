import { expect, test, type Page } from '@playwright/test';
import { selectNoteText } from './helpers/richNoteSelection';

async function openEditor(page: Page) {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'New note' }).first().click();
  return { title: page.getByRole('textbox', { name: 'Note title' }), body: page.getByRole('textbox', { name: 'Note body' }) };
}

test('the toolbar formats text directly while typing', async ({ page }) => {
  const { body } = await openEditor(page);
  await body.fill('alpha beta gamma');
  await selectNoteText(body, 0, 16);
  await page.getByRole('button', { name: 'Bold', exact: true }).click();
  await page.getByRole('button', { name: 'Italic', exact: true }).click();
  await expect(body.locator('strong em, em strong')).toHaveText('alpha beta gamma');
});

test('the body keeps its place and size when it loses focus', async ({ page }) => {
  const { title, body } = await openEditor(page);
  await body.fill('Stays at the top');
  const typed = await body.evaluate(field => ({ top: field.getBoundingClientRect().top, fontSize: getComputedStyle(field).fontSize }));
  await title.focus();
  const shown = await body.evaluate(field => ({ top: field.getBoundingClientRect().top, fontSize: getComputedStyle(field).fontSize }));
  expect(shown).toEqual(typed);
});

test('a formatted guest note survives a reload', async ({ page }) => {
  const { title, body } = await openEditor(page);
  await title.fill('Persistence check');
  await body.fill('this should come back');
  await selectNoteText(body, 0, 4);
  await body.press('Control+b');
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  // Back waits for IndexedDB before closing; a click alone does not await that work.
  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Persistence check', exact: true })).toBeVisible();
  await page.reload();
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'Persistence check', exact: true }).click();
  await expect(page.getByRole('textbox', { name: 'Note body' }).locator('strong')).toHaveText('this');
});

test('toolbar Enter and Space preserve the selected words', async ({ page }) => {
  const { body } = await openEditor(page);
  await body.fill('alpha beta');
  await selectNoteText(body, 6, 10);
  await page.getByRole('button', { name: 'Bold', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(body.locator('strong')).toHaveText('beta');
  await expect(body).toBeFocused();
  await page.getByRole('button', { name: 'Italic', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(body.locator('strong em, em strong')).toHaveText('beta');
});

test('Ctrl+B and Ctrl+I format separate selections', async ({ page }) => {
  const { body } = await openEditor(page);
  await body.fill('one two');
  await selectNoteText(body, 0, 3);
  await body.press('Control+b');
  await selectNoteText(body, 4, 7);
  await body.press('Control+i');
  await expect(body.locator('strong')).toHaveText('one');
  await expect(body.locator('em')).toHaveText('two');
});
