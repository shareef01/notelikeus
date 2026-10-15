import { expect, test, type Page } from '@playwright/test';

/**
 * A `.nlkbak` bundle carries images, and an import writes them to this device's storage. That must
 * wait for the user's yes: a preview that is cancelled (or a tab closed on it) used to have written
 * every image already, with no note pointing at them — dead weight that nothing ever cleaned up.
 *
 * Guest mode, so this needs no backend.
 */

const BASE64_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

/** How many images are staged on this device, read straight from IndexedDB. */
function stagedImageCount(page: Page): Promise<number> {
  return page.evaluate(
    () =>
      new Promise<number>((resolve, reject) => {
        const open = indexedDB.open('notelikeus-notes');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const db = open.result;
          const count = db.transaction('pendingAttachments').objectStore('pendingAttachments').count();
          count.onsuccess = () => {
            resolve(count.result);
            db.close();
          };
          count.onerror = () => reject(count.error);
        };
      }),
  );
}

async function noteWithImage(page: Page, title: string): Promise<void> {
  await page.getByRole('button', { name: 'New note' }).first().click();
  await page.getByPlaceholder('Title').first().fill(title);
  await page.getByPlaceholder('Start writing…').first().focus();
  await page.evaluate((pngBase64) => {
    const bin = atob(pngBase64);
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    const data = new DataTransfer();
    data.items.add(new File([bytes], 'photo.png', { type: 'image/png' }));
    const paste = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(paste, 'clipboardData', { value: data });
    (document.activeElement ?? document.body).dispatchEvent(paste);
  }, BASE64_PNG);
  await expect(page.getByAltText('Note attachment')).toBeVisible({ timeout: 15_000 });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('heading', { name: title, exact: true, level: 2 })).toBeVisible();
}

test('a bundle preview writes no images; confirming does', async ({ page }, info) => {
  test.skip(info.project.name !== 'chromium');
  await page.goto('/');
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await noteWithImage(page, 'Holiday');
  await expect.poll(() => stagedImageCount(page)).toBe(1);

  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /Export backup with images/ }).click(),
  ]);
  const bundle = info.outputPath('backup.nlkbak');
  await download.saveAs(bundle);
  await page.keyboard.press('Escape');

  // Preview, then cancel: nothing was written.
  await page.locator('input[type="file"][accept*="nlkbak"]').setInputFiles(bundle);
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('1 image will be restored');
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect(await stagedImageCount(page)).toBe(1);

  // Preview, then confirm: the copy arrives with its image.
  await page.locator('input[type="file"][accept*="nlkbak"]').setInputFiles(bundle);
  await page.getByRole('button', { name: 'Import', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Holiday', exact: true, level: 2 })).toHaveCount(2, {
    timeout: 20_000,
  });
  await expect.poll(() => stagedImageCount(page)).toBe(2);
});
