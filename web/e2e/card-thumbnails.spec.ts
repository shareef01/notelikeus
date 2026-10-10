import { expect, test, type Page } from '@playwright/test';
import { leaveEditor, typeNewNoteTitle } from './helpers/newNote';

/**
 * A note's first picture shows on its card.
 *
 * An image-only note used to be a blank card with a tiny icon, indistinguishable from any other. The
 * card now carries a downscaled, cropped copy of the picture. This drives the real thing in a real
 * browser — attach a large image, go back to the list — because what matters is that the picture is
 * decoded, cropped and shown, which a fake DOM cannot say.
 */

/** A tall 900x1800 gradient, like a phone screenshot, made in the page so no fixture file is shipped. */
async function makeTallPng(page: Page): Promise<Buffer> {
  const base64 = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        const canvas = document.createElement('canvas');
        canvas.width = 900;
        canvas.height = 1800;
        const context = canvas.getContext('2d')!;
        const gradient = context.createLinearGradient(0, 0, 900, 1800);
        gradient.addColorStop(0, '#d33');
        gradient.addColorStop(1, '#36c');
        context.fillStyle = gradient;
        context.fillRect(0, 0, 900, 1800);
        canvas.toBlob((blob) => {
          const reader = new FileReader();
          reader.onload = () => resolve(String(reader.result).split(',')[1]);
          reader.readAsDataURL(blob!);
        }, 'image/png');
      }),
  );
  return Buffer.from(base64, 'base64');
}

test('an image note shows its picture on the card, downscaled', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note', exact: true }).first().click();
  await typeNewNoteTitle(page, 'Receipt photo');

  const png = await makeTallPng(page);
  await page
    .locator('input[type="file"][accept="image/*"]')
    .first()
    .setInputFiles({ name: 'tall.png', mimeType: 'image/png', buffer: png });
  await page.waitForTimeout(2_000);

  await leaveEditor(page);

  const thumbnail = page.locator('[data-testid="note-card-thumbnail"] img').first();
  await expect(thumbnail, 'the image note should show a thumbnail on its card').toBeVisible({ timeout: 15_000 });

  const size = await thumbnail.evaluate((img: HTMLImageElement) => ({
    width: img.naturalWidth,
    height: img.naturalHeight,
    complete: img.complete,
  }));
  expect(size.complete && size.width > 0, 'the thumbnail must actually decode').toBe(true);
  // Cropped to 4:3 and capped at 480 wide: a card never holds the full 900x1800 picture.
  expect(size.width).toBeLessThanOrEqual(480);
  expect(size.height).toBeLessThanOrEqual(360);

  // It is decorative — the card's label already says the note has an image.
  expect(await thumbnail.getAttribute('alt')).toBe('');
  await expect(page.getByRole('button', { name: /Receipt photo.*Has image/ })).toBeVisible();

  await context.close();
});

test('a note without an image has no thumbnail', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note', exact: true }).first().click();
  await typeNewNoteTitle(page, 'Just text');
  await leaveEditor(page);

  await expect(page.getByRole('button', { name: /Just text/ })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('[data-testid="note-card-thumbnail"]')).toHaveCount(0);

  await context.close();
});
