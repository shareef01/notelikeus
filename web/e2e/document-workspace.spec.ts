import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import { leaveEditor, openNewNote, typeNewNoteTitle } from './helpers/newNote';

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note', exact: true }).first()).toBeVisible();
});

test('settings is a reloadable destination with Back, Forward and persistent preferences', async ({ page }, info) => {
  const openSettings = page.getByRole('button', { name: 'Open settings', exact: true }).first();
  const settings = page.getByRole('main', { name: 'Settings' });
  await openSettings.click();
  await expect(page).toHaveURL(/\/settings$/);
  await expect(settings.getByRole('heading', { level: 1 })).toHaveText('Settings');
  await expect(page.getByRole('dialog', { name: 'Settings' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Close settings' })).toBeFocused();

  await settings.getByRole('group', { name: 'Default view' }).getByRole('button', { name: 'List' }).click();
  await page.screenshot({ path: info.outputPath('settings.png') });
  await page.goBack();
  await expect(settings).toHaveCount(0);
  await expect(openSettings).toBeFocused();
  await page.goForward();
  await expect(settings).toBeVisible();
  await settings.getByRole('button', { name: 'Privacy policy' }).click();
  const privacy = page.getByRole('dialog', { name: 'Privacy policy' });
  await expect(privacy).toBeVisible();
  await page.goBack();
  await expect(privacy).toHaveCount(0);
  await expect(page).toHaveURL(/\/settings$/);
  await page.reload();
  await expect(settings.getByRole('group', { name: 'Default view' }).getByRole('button', { name: 'List' }))
    .toHaveAttribute('aria-pressed', 'true');
  await settings.getByRole('button', { name: 'Close settings' }).click();
  await expect(settings).toHaveCount(0);
});

test('an image opens fitted, zooms and pans, and returns to the note', async ({ page }, info) => {
  await openNewNote(page);
  await typeNewNoteTitle(page, 'A field journal');
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('A few observations from the day.');
  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 320;
    canvas.height = 600;
    const context = canvas.getContext('2d')!;
    const gradient = context.createLinearGradient(0, 0, 320, 600);
    gradient.addColorStop(0, '#116466');
    gradient.addColorStop(1, '#ffc6a1');
    context.fillStyle = gradient;
    context.fillRect(0, 0, 320, 600);
    return canvas.toDataURL('image/png').split(',')[1]!;
  });
  await page.locator('input[type=file][accept="image/*"]').setInputFiles({
    name: 'portrait.png',
    mimeType: 'image/png',
    buffer: Buffer.from(png, 'base64'),
  });
  const openImage = page.getByRole('button', { name: 'Open image' }).first();
  await expect(openImage).toBeVisible();
  const preview = (await openImage.getByAltText('Note attachment').boundingBox())!;
  expect(preview.width / preview.height).toBeCloseTo(320 / 600, 2);
  await openImage.click();
  const viewer = page.getByRole('dialog', { name: 'Image viewer' });
  await expect(viewer.getByRole('button', { name: 'Close image' })).toBeFocused();
  const image = viewer.getByAltText('Enlarged note attachment');
  await expect(image).toBeVisible();
  const enlarged = (await image.boundingBox())!;
  expect(enlarged.width / enlarged.height).toBeCloseTo(320 / 600, 2);
  expect(enlarged.height).toBeGreaterThan(preview.height);
  await page.screenshot({ path: info.outputPath('portrait-viewer.png') });

  await viewer.getByRole('button', { name: 'Zoom in' }).click();
  await expect(viewer.getByRole('status')).toHaveText('150% of fit');
  await page.keyboard.press('+');
  await expect(viewer.getByRole('status')).toHaveText('200% of fit');
  expect((await image.boundingBox())!.height).toBeCloseTo(enlarged.height * 2, 0);

  const canvas = viewer.getByRole('region', { name: 'Image canvas' });
  const midScroll = await canvas.evaluate((element) => element.scrollTop);
  expect(midScroll).toBeGreaterThan(0);
  const bounds = (await canvas.boundingBox())!;
  const x = Math.round(bounds.x + bounds.width / 2);
  const y = Math.round(bounds.y + bounds.height / 2);
  if (info.project.name === 'mobile-chrome') {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y - 105 }] });
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await cdp.detach();
  } else {
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x, y - 100, { steps: 4 });
    await page.mouse.up();
  }
  await expect.poll(() => canvas.evaluate((element) => element.scrollTop)).toBeGreaterThan(midScroll + 30);

  await canvas.focus();
  const panned = await canvas.evaluate((element) => element.scrollTop);
  await page.keyboard.press('ArrowUp');
  await expect.poll(() => canvas.evaluate((element) => element.scrollTop)).toBeLessThan(panned);
  await viewer.getByRole('button', { name: 'Fit image to viewer' }).click();
  await expect(viewer.getByRole('status')).toHaveText('Fit to viewer');
  expect((await image.boundingBox())!.height).toBeCloseTo(enlarged.height, 0);
  expect(await canvas.evaluate((element) => element.scrollTop)).toBe(0);

  await page.keyboard.press('Escape');
  await expect(viewer).toHaveCount(0);
  await expect(openImage).toBeFocused();
  await expect(body).toHaveText('A few observations from the day.');

  await page.locator('input[type=file][accept="image/*"]').setInputFiles({
    name: 'another-portrait.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64'),
  });
  await expect(page.getByRole('button', { name: 'Open image' })).toHaveCount(2);
  await openImage.click();
  await expect(viewer.getByRole('status')).toHaveText('Fit to viewer');
  await expect(viewer.getByRole('button', { name: 'Zoom in' })).toHaveAttribute('aria-disabled', 'false');
  await viewer.getByRole('button', { name: 'Zoom in' }).click();
  await expect(viewer.getByRole('status')).toHaveText('150% of fit');
  await viewer.getByRole('button', { name: 'Next image' }).click();
  await expect(viewer.getByText('2 of 2')).toBeVisible();
  await expect(viewer.getByRole('status')).toHaveText('Fit to viewer');
  await page.keyboard.press('Escape');
  await expect(viewer).toHaveCount(0);
  await expect(openImage).toBeFocused();
});

test('switching editor layout keeps an unsaved draft in the same editor', async ({ page }, info) => {
  test.skip(info.project.name === 'mobile-chrome');
  await openNewNote(page);
  await typeNewNoteTitle(page, 'Draft in progress');
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.fill('Keep these words when moving to the dock.');
  await page.getByRole('radio', { name: 'Dock note' }).click();
  await expect(body).toHaveText('Keep these words when moving to the dock.');
  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveValue('Draft in progress');
  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  await expect(page.getByRole('main', { name: 'Settings' })).toBeVisible();
  await page.goBack();
  await expect(body).toHaveText('Keep these words when moving to the dock.');
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(body).toHaveText('Keep these words when moving to the dock.');
  await expect(page.getByRole('textbox', { name: 'Note title' })).toHaveValue('Draft in progress');
});

test('a guest image follows its paragraph through edits, reload and removal from the body', async ({ page }) => {
  const title = 'Image between paragraphs';
  await openNewNote(page);
  await typeNewNoteTitle(page, title);
  const body = page.getByRole('textbox', { name: 'Note body' });
  await body.pressSequentially('First');
  await page.keyboard.insertText(' 🧭');
  await page.keyboard.press('Enter');
  await page.keyboard.insertText('Second');
  const png = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#116466';
    context.fillRect(0, 0, canvas.width, canvas.height);
    return canvas.toDataURL('image/png').split(',')[1]!;
  });
  await page.locator('input[type=file][accept="image/*"]').setInputFiles({
    name: 'compass.png', mimeType: 'image/png', buffer: Buffer.from(png, 'base64'),
  });

  const first = body.locator(':scope > p').first();
  await first.click();
  await page.keyboard.press('End');
  await page.getByRole('button', { name: 'Place after cursor paragraph' }).click();
  const inline = body.locator('[data-inline-attachment]');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeVisible();
  expect(await inline.evaluate((element) => [element.previousElementSibling?.textContent, element.nextElementSibling?.textContent]))
    .toEqual(['First 🧭', 'Second']);
  await inline.getByRole('button', { name: 'Open image' }).click();
  await expect(page.getByRole('dialog', { name: 'Image viewer' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeFocused();

  await first.click();
  await page.keyboard.press('End');
  await page.keyboard.insertText(' updated');
  await expect(first).toHaveText('First 🧭 updated');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeVisible();

  await leaveEditor(page);
  const card = page.getByRole('button', { name: `${title}, Has image`, exact: true });
  await expect(card).toBeVisible();
  await page.reload();
  await card.click();
  await expect(first).toHaveText('First 🧭 updated');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeVisible();

  await body.locator(':scope > p').last().click({ position: { x: 2, y: 8 } });
  await expect.poll(() => page.evaluate(() => window.getSelection()?.anchorNode?.textContent)).toBe('Second');
  await page.keyboard.press('Home');
  await page.keyboard.press('Backspace');
  await expect(body.locator(':scope > p')).toHaveCount(1);
  await expect(inline).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open image' })).toBeVisible();
  await page.keyboard.press('Control+z');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeVisible();

  await inline.getByRole('button', { name: 'Move to attachment strip' }).click();
  await expect(inline).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Open image' })).toBeVisible();
  await expect(body.locator(':scope > p')).toHaveText(['First 🧭 updated', 'Second']);
});

test('a guest backup restores an image at its saved position', async ({ page }) => {
  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: /^Import/ }).first().click();
  await (await chooser).setFiles(fileURLToPath(new URL('../../contracts/backup/v4-positioned-image.nlkbak', import.meta.url)));
  await page.getByRole('dialog', { name: /^Import 1 note\b/ }).getByRole('button', { name: 'Import', exact: true }).click();
  await page.getByRole('button', { name: /^Positioned image\b.*Has image$/ }).click();
  const body = page.getByRole('textbox', { name: 'Note body' });
  const inline = body.locator('[data-inline-attachment]');
  await expect(inline.getByRole('button', { name: 'Open image' })).toBeVisible();
  expect(await inline.evaluate((element) => [element.previousElementSibling?.textContent, element.nextElementSibling?.textContent]))
    .toEqual(['', 'After']);
});
