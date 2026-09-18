import { expect, test, type Page } from '@playwright/test';

/**
 * End-to-end tests for Web Rapid Image Capture:
 * - Clipboard image paste (Ctrl+V / Cmd+V)
 * - Drag-and-drop file ingestion
 * - Text paste regression assurance
 *
 * Driven through guest mode to verify local IndexedDB durability
 * with zero required cloud worker requests.
 */

async function enterGuest(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({
    timeout: 20_000,
  });
}

// 1x1 transparent PNG base64 for test events
const BASE64_PNG =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

test.describe('Web Rapid Image Capture E2E', () => {
  test('E2E-A — clipboard image paste stages attachment and survives save/reopen without worker traffic', async ({
    page,
  }) => {
    const workerRequests: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('8787')) {
        workerRequests.push(`${req.method()} ${req.url()}`);
      }
    });

    await enterGuest(page);

    const title = `Paste Note ${Date.now()}`;
    await page.getByRole('button', { name: 'New note' }).first().click();

    const titleField = page.getByPlaceholder('Title').first();
    await expect(titleField).toBeVisible({ timeout: 15_000 });
    await titleField.fill(title);

    // Focus the textarea and simulate image clipboard paste
    const textarea = page.getByPlaceholder('Start writing…').first();
    await textarea.focus();

    await page.evaluate(
      ({ pngBase64 }) => {
        const binStr = atob(pngBase64);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) {
          bytes[i] = binStr.charCodeAt(i);
        }
        const file = new File([bytes], 'pasted_screenshot.png', { type: 'image/png' });

        const dt = new DataTransfer();
        dt.items.add(file);

        const pasteEvt = new Event('paste', { bubbles: true, cancelable: true });
        Object.defineProperty(pasteEvt, 'clipboardData', {
          value: dt,
          writable: false,
        });

        const activeEl = document.activeElement || document.querySelector('[role="dialog"]') || document.body;
        activeEl.dispatchEvent(pasteEvt);
      },
      { pngBase64: BASE64_PNG },
    );

    // Attachment preview image should appear in the editor
    const previewImg = page.getByAltText('Note attachment');
    await expect(previewImg).toBeVisible({ timeout: 15_000 });

    // Close editor to save
    await page.keyboard.press('Escape');
    const card = page.getByRole('button', { name: new RegExp(title) }).first();
    await expect(card).toBeVisible({ timeout: 20_000 });

    // Reload page and resume guest mode to verify local IndexedDB durability across sessions
    await page.reload();
    await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
    await page.getByRole('button', { name: 'Continue without an account' }).click();

    const reloadedCard = page.getByRole('button', { name: new RegExp(title) }).first();
    await expect(reloadedCard).toBeVisible({ timeout: 20_000 });

    // Reopen the saved note and verify attachment preview is restored
    await reloadedCard.click();
    await expect(page.getByAltText('Note attachment')).toBeVisible({ timeout: 15_000 });

    await page.keyboard.press('Escape');

    // Local durability must not require or initiate worker network requests
    expect(workerRequests).toHaveLength(0);
  });

  test('E2E-B — file drag and drop activates overlay, attaches image, and dismisses overlay', async ({
    page,
  }) => {
    const workerRequests: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('8787')) {
        workerRequests.push(`${req.method()} ${req.url()}`);
      }
    });

    await enterGuest(page);

    const title = `Drop Note ${Date.now()}`;
    await page.getByRole('button', { name: 'New note' }).first().click();

    const titleField = page.getByPlaceholder('Title').first();
    await expect(titleField).toBeVisible({ timeout: 15_000 });
    await titleField.fill(title);

    // Simulate dragenter with file payload
    await page.evaluate(() => {
      const dt = new DataTransfer();
      const evt = new Event('dragenter', { bubbles: true, cancelable: true });
      Object.defineProperty(dt, 'types', { value: ['Files'] });
      Object.defineProperty(evt, 'dataTransfer', { value: dt });

      const dialog = document.querySelector('[role="dialog"]');
      dialog?.dispatchEvent(evt);
    });

    // Drop overlay should become visible
    const overlay = page.getByTestId('editor-drop-overlay');
    await expect(overlay).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('Drop image to attach')).toBeVisible();

    // Simulate drop with actual image file
    await page.evaluate(
      ({ pngBase64 }) => {
        const binStr = atob(pngBase64);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) {
          bytes[i] = binStr.charCodeAt(i);
        }
        const file = new File([bytes], 'dropped_image.png', { type: 'image/png' });

        const dt = new DataTransfer();
        dt.items.add(file);

        const dropEvt = new Event('drop', { bubbles: true, cancelable: true });
        Object.defineProperty(dt, 'types', { value: ['Files'] });
        Object.defineProperty(dropEvt, 'dataTransfer', { value: dt });

        const dialog = document.querySelector('[role="dialog"]');
        dialog?.dispatchEvent(dropEvt);
      },
      { pngBase64: BASE64_PNG },
    );

    // Overlay must disappear immediately
    await expect(overlay).toHaveCount(0, { timeout: 5_000 });

    // Attachment preview image should be rendered
    await expect(page.getByAltText('Note attachment')).toBeVisible({ timeout: 15_000 });

    await page.keyboard.press('Escape');

    expect(workerRequests).toHaveLength(0);
  });

  test('E2E-C — ordinary text paste remains functional without creating attachments', async ({
    page,
  }) => {
    const workerRequests: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('8787')) {
        workerRequests.push(`${req.method()} ${req.url()}`);
      }
    });

    await enterGuest(page);

    const title = `Text Paste Note ${Date.now()}`;
    await page.getByRole('button', { name: 'New note' }).first().click();

    const titleField = page.getByPlaceholder('Title').first();
    await expect(titleField).toBeVisible({ timeout: 15_000 });
    await titleField.fill(title);

    const textarea = page.getByPlaceholder('Start writing…').first();
    await textarea.focus();

    // Paste ordinary text payload
    await page.evaluate(() => {
      const dt = new DataTransfer();
      dt.setData('text/plain', 'Standard copied paragraph from clipboard');

      const pasteEvt = new Event('paste', { bubbles: true, cancelable: true });
      Object.defineProperty(pasteEvt, 'clipboardData', { value: dt });

      const activeEl = document.activeElement || document.body;
      activeEl.dispatchEvent(pasteEvt);
    });

    // Verify no attachment was created
    await expect(page.getByAltText('Note attachment')).toHaveCount(0);

    await page.keyboard.press('Escape');

    expect(workerRequests).toHaveLength(0);
  });
});
