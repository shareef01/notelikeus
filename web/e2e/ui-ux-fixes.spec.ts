import { expect, test } from '@playwright/test';
import { selectNoteText } from './helpers/richNoteSelection';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0);
  await page.getByRole('button', {name: 'Continue without an account'}).click();
  await page.getByRole('button', {name: 'New note'}).first().click();
});

test('conversion Undo restores saved checklist text and checked state, then persists it', async ({ page }) => {
  await page.getByRole('textbox', {name: 'Note title'}).fill('Conversion regression');
  await page.getByRole('textbox', {name: 'Note body'}).fill('First task\nSecond task');
  await page.getByRole('button', {name: 'Checklist', exact: true}).click();
  await page.getByRole('checkbox', {name: 'First task', exact: true}).check();
  await page.getByRole('button', {name: 'Back', exact: true}).click();
  await page.getByRole('button', {name: 'Conversion regression', exact: true}).click();
  await page.getByRole('button', {name: 'Convert to text', exact: true}).click();
  const body = page.getByRole('textbox', {name: 'Note body'});
  await expect(body).toContainText('First task');
  await body.press('Control+z');
  await expect(page.getByRole('checkbox', {name: 'First task', exact: true})).toBeChecked();
  await expect(page.getByPlaceholder('List item')).toHaveCount(2);
  await page.getByRole('button', {name: 'Redo conversion', exact: true}).click();
  await expect(body).toContainText('Second task');
  await page.getByRole('button', {name: 'Undo conversion', exact: true}).click();
  await page.getByRole('button', {name: 'Back', exact: true}).click();
  await page.getByRole('button', {name: 'Conversion regression', exact: true}).click();
  await expect(page.getByRole('checkbox', {name: 'First task', exact: true})).toBeChecked();
  await expect(page.getByPlaceholder('List item')).toHaveCount(2);
});

test('Enter focuses the inserted unchecked row before completed rows', async ({ page }) => {
  await page.getByRole('textbox', {name: 'Note body'}).fill('Completed\nSecond\nThird');
  await page.getByRole('button', {name: 'Checklist', exact: true}).click();
  await page.getByRole('checkbox', {name: 'Completed', exact: true}).check();
  await page.getByPlaceholder('List item').filter({visible: true}).nth(0).press('Enter');
  const rows = page.getByPlaceholder('List item');
  await expect(rows).toHaveCount(4);
  await expect(rows.nth(1)).toHaveValue('');
  await expect(rows.nth(1)).toBeFocused();
  await rows.nth(1).fill('Inserted');
  await page.getByRole('button', {name: '+ List item', exact: true}).click();
  await expect(rows.nth(3)).toHaveValue('');
  await expect(rows.nth(3)).toBeFocused();
});

test('existing links can be edited and removed without changing their label', async ({ page }) => {
  const body = page.getByRole('textbox', {name: 'Note body'});
  await body.fill('Read the guide.');
  await selectNoteText(body, 5, 14);
  await page.getByRole('button', {name: 'Link', exact: true}).click();
  await page.getByRole('textbox', {name: 'Link URL'}).fill('https://example.com/old');
  await page.getByRole('button', {name: 'OK', exact: true}).click();
  await selectNoteText(body, 8, 8);
  await page.getByRole('button', {name: 'Link', exact: true}).click();
  await expect(page.getByRole('textbox', {name: 'Link URL'})).toHaveValue('https://example.com/old');
  await page.getByRole('textbox', {name: 'Link URL'}).fill('https://example.com/new');
  await page.getByRole('button', {name: 'Update link', exact: true}).click();
  await expect(body).toHaveText('Read the guide.');
  await expect(body.locator('a')).toHaveCount(1);
  await expect(body.locator('a')).toHaveAttribute('href', 'https://example.com/new');
  await selectNoteText(body, 8, 8);
  await page.getByRole('button', {name: 'Link', exact: true}).click();
  await page.getByRole('button', {name: 'Remove link', exact: true}).click();
  await expect(body.locator('a')).toHaveCount(0);
  await expect(body).toHaveText('Read the guide.');
});

test('browser Back closes a nested dialog, then saves the note; Forward reopens it', async ({ page }) => {
  await page.getByRole('textbox', {name: 'Note title'}).fill('History regression');
  await page.getByRole('textbox', {name: 'Note body'}).fill('Keep this draft');
  await page.getByRole('button', {name: 'Link', exact: true}).click();
  await page.evaluate(() => history.back());
  await expect(page.getByRole('textbox', {name: 'Link URL'})).toHaveCount(0);
  await expect(page.getByRole('textbox', {name: 'Note body'})).toHaveText('Keep this draft');
  await page.evaluate(() => history.back());
  await expect(page.getByRole('textbox', {name: 'Note body'})).toHaveCount(0);
  await expect(page.getByRole('button', {name: 'History regression', exact: true})).toBeVisible();
  await page.evaluate(() => history.forward());
  await expect(page.getByRole('textbox', {name: 'Note body'})).toHaveText('Keep this draft');
});

test('link dialog fits above a keyboard-sized visual viewport', async ({ page }) => {
  await page.setViewportSize({width: 390, height: 844});
  await page.getByRole('button', {name: 'Link', exact: true}).click();
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport!, 'height', {configurable: true, value: 420});
    window.visualViewport!.dispatchEvent(new Event('resize'));
  });
  const dialog = page.getByRole('dialog', {name: 'Add link', exact: true});
  await expect.poll(async () => { const box = await dialog.boundingBox(); return box ? box.y + box.height : Infinity; }).toBeLessThanOrEqual(420);
  await page.getByRole('textbox', {name: 'Link URL'}).fill('example.com');
  await page.getByRole('button', {name: 'OK', exact: true}).click();
  await expect(dialog).toHaveCount(0);
});

test('label creation stays inside a narrow viewport and explains duplicates', async ({page}, testInfo) => {
  await page.getByRole('button', {name:'Back', exact:true}).click();
  await page.setViewportSize({width:320,height:568});
  await page.getByRole('button', {name:'Open menu', exact:true}).click();
  await page.getByRole('button', {name:'Edit labels', exact:true}).click();
  const input = page.getByRole('textbox', {name:'Create new label', exact:true});
  await input.fill('Work');
  const create = page.getByRole('button', {name:'Create', exact:true});
  const box = await create.boundingBox();
  expect(box!.x + box!.width).toBeLessThanOrEqual(320);
  await create.click();
  await input.fill(' WORK ');
  await create.click();
  await expect(page.getByRole('alert')).toHaveText('A label with this name already exists.');
  await expect(input).toHaveValue(' WORK ');
  await expect(page.getByRole('button', {name:'Work',exact:true})).toHaveCount(1);
  await input.fill('Home');
  await create.click();
  await page.getByRole('button', {name:'Home', exact:true}).click();
  const rename = page.getByRole('textbox', {name:'Edit label name', exact:true});
  await rename.fill('work');
  await rename.press('Enter');
  await expect(page.getByRole('alert')).toHaveText('A label with this name already exists.');
  await expect(rename).toHaveValue('work');
  await page.screenshot({path:testInfo.outputPath('labels-narrow.png')});
});

test('the card selection circle never covers the first title character', async ({page}) => {
  await page.getByRole('textbox', {name:'Note title'}).fill('Selection clearance');
  await page.getByRole('textbox', {name:'Note body'}).fill('A note preview');
  await page.getByRole('button', {name:'Back', exact:true}).click();
  const card = page.locator('article').filter({has:page.getByRole('heading', {name:'Selection clearance', exact:true})});
  await card.hover();
  const overlap = await card.evaluate(el => {
    const heading = el.querySelector('h2')!;
    const range = document.createRange();
    range.setStart(heading.firstChild!, 0); range.setEnd(heading.firstChild!, 1);
    const text = range.getBoundingClientRect();
    const checkbox = el.querySelector('[role="checkbox"]')!.getBoundingClientRect();
    return Math.max(0, Math.min(text.right, checkbox.right) - Math.max(text.left, checkbox.left)) *
      Math.max(0, Math.min(text.bottom, checkbox.bottom) - Math.max(text.top, checkbox.top));
  });
  expect(overlap).toBe(0);
});

test('save feedback changes immediately while the new revision is debouncing', async ({page}) => {
  const body = page.getByRole('textbox', {name:'Note body'});
  const status = page.locator('footer [role="status"]');
  await body.fill('Saved revision');
  await expect(status).toContainText('Saved on this device');
  await body.fill('Pending revision');
  await expect(status).toHaveText('Saving…', {timeout:500});
  await body.pressSequentially(' still typing', {delay:100});
  await expect(status).toHaveText('Saving…', {timeout:500});
  await expect(status).toContainText('Saved on this device');
});
