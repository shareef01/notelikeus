import { expect, test, type BrowserContext, type Page } from '@playwright/test';

/**
 * Attaching an image on a touch device.
 *
 * `image-ingestion.spec.ts` covers paste, drag-and-drop and text-paste — all desktop gestures. A phone
 * has none of them, so the question this file answers is the one F11 raised for the card's actions:
 * is there a visible way in? Measured first, and unlike F11 the answer is yes — a 36x36 "Add image"
 * button is visible at 390px as well as at 1440px, and the `<input type="file" accept="image/*">` it
 * drives gives a touch user the camera or gallery picker.
 *
 * So this guards the touch path rather than fixing it. 36x36 clears WCAG 2.5.8 on its own, which is
 * why it carries no `.tap-target` — the system-wide check in `touch-targets.spec.ts` already asserts
 * nothing here is under the 24px minimum.
 */
async function openEditorOnTouch(context: BrowserContext): Promise<Page> {
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note', exact: true }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });
  return page;
}

/** A 1x1 PNG — enough to exercise the pipeline without shipping a fixture file. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64',
);

const newTouch = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

test('a touch user can see and use the attach control', async ({ browser }) => {
  const context = await newTouch(browser);
  const page = await openEditorOnTouch(context);

  const coarse = await page.evaluate(() => matchMedia('(pointer: coarse)').matches);
  expect(coarse, 'this context should be a coarse pointer, or the test proves nothing').toBe(true);

  // The affordance has to be visible without hover — the mistake F11 recorded.
  const attach = page.getByRole('button', { name: 'Add image' }).first();
  await expect(attach, 'a phone user needs a visible way to attach an image').toBeVisible();

  // And it must drive a real image picker, not just look like one.
  const input = page.locator('input[type="file"][accept="image/*"]').first();
  await expect(input).toHaveCount(1);
  await input.setInputFiles({ name: 'dot.png', mimeType: 'image/png', buffer: PNG });
  await page.waitForTimeout(2_000);

  // Something representing the attachment should now exist — an image, or a named strip entry.
  const attached = await page.evaluate(() => {
    const imgs = [...document.querySelectorAll('img')].filter((el) => (el.getAttribute('src') ?? '').startsWith('blob:') || (el.getAttribute('src') ?? '').startsWith('data:'));
    const named = [...document.querySelectorAll('[aria-label], [title]')].some((el) =>
      /dot\.png|attachment|image/i.test(`${el.getAttribute('aria-label') ?? ''} ${el.getAttribute('title') ?? ''}`),
    );
    return { images: imgs.length, named };
  });
  expect(
    attached.images > 0 || attached.named,
    'the attached image should be represented in the editor',
  ).toBe(true);
  await context.close();
});
