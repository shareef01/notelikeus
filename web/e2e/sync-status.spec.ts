import { expect, test, type Page, type Route } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createNoteWithTitle, leaveEditor } from './helpers/newNote';
import type { useNotesStore } from '../src/store/notesStore';

/**
 * DESIGN_SYSTEM §5.1 in a real browser against local Supabase: the editor says "Synced" only once
 * the server accepted the note's latest edit, an edit made offline is called out as waiting, and
 * both clear by themselves when the connection returns.
 */

const PASSWORD = 'e2e-password-123';

async function signUp(page: Page): Promise<string> {
  const email = `e2e-sync-${Date.now()}@example.com`;
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#test-login-email')).toBeVisible({ timeout: 20_000 });
  await page.locator('#test-login-email').fill(email);
  await page.locator('#test-login-password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.locator('#test-login-email')).toHaveCount(0, { timeout: 30_000 });
  return email;
}

function editorStatus(page: Page) {
  return page.locator('footer [role="status"]').first();
}

test('the status line and banner follow an edit offline and back', async ({ page, context }) => {
  await signUp(page);
  const title = `Sync status ${Date.now()}`;
  await createNoteWithTitle(page, title);

  await page.getByRole('button', { name: title, exact: true }).first().click();
  await expect(editorStatus(page)).toContainText('Synced', { timeout: 20_000 });

  await context.setOffline(true);
  await page.getByPlaceholder('Start writing…').first().fill('written offline');
  await expect(editorStatus(page)).toContainText('Offline — changes saved here', { timeout: 20_000 });
  await expect(page.getByText('1 edit saved on this device will sync when you reconnect')).toBeVisible();

  await context.setOffline(false);
  await expect(editorStatus(page)).toContainText('Synced', { timeout: 30_000 });
  await expect(page.getByText(/You’re offline/)).toHaveCount(0);
});

test('a large image survives an interrupted upload and an incomplete backup can be retried', async ({ page, context }, info) => {
  const worker = process.env.VITE_ATTACHMENTS_WORKER_URL;
  test.skip(!worker, 'Requires the local attachments Worker');
  const workerUrl = new URL(worker!);
  expect(['127.0.0.1', 'localhost']).toContain(workerUrl.hostname);
  test.setTimeout(120_000);

  const email = await signUp(page);
  const title = 'Large attachment recovery';
  await createNoteWithTitle(page, title);
  const card = page.getByRole('button', { name: title, exact: true }).first();
  await card.click();
  await expect(editorStatus(page)).toContainText('Synced');

  const base64 = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1600;
    const drawing = canvas.getContext('2d')!;
    const pixels = drawing.createImageData(canvas.width, canvas.height);
    // Noise keeps this PNG large while its dimensions stay practical for an editor preview.
    let seed = 1;
    for (let pixel = 0; pixel < pixels.data.length; pixel += 4) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      pixels.data[pixel] = seed >>> 24;
      pixels.data[pixel + 1] = (seed >>> 16) & 255;
      pixels.data[pixel + 2] = (seed >>> 8) & 255;
      pixels.data[pixel + 3] = 255;
    }
    drawing.putImageData(pixels, 0, 0);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const image = Buffer.from(base64, 'base64');
  expect(image.length).toBeGreaterThan(7 * 1024 * 1024);
  expect(image.length).toBeLessThanOrEqual(10 * 1024 * 1024);
  await writeFile(info.outputPath('original.png'), image);
  await writeFile(info.outputPath('account-email.txt'), email);

  const uploadRoute = `${workerUrl.origin}/v2/attachments/**`;
  let lostUploadReply = false;
  await context.route(uploadRoute, async (route) => {
    if (route.request().method() !== 'PUT') return route.continue();
    if (lostUploadReply) return route.abort('connectionreset');
    const uploaded = await route.fetch();
    expect(uploaded.ok()).toBe(true);
    lostUploadReply = true;
    await route.abort('connectionreset');
  });
  await page.locator('input[type="file"][accept="image/*"]').setInputFiles({
    name: 'large.png', mimeType: 'image/png', buffer: image,
  });
  await expect(page.getByAltText('Note attachment')).toBeVisible();
  await expect.poll(() => lostUploadReply).toBe(true);

  await context.setOffline(true);
  await expect(editorStatus(page)).toContainText('Offline');
  await leaveEditor(page);
  await context.unroute(uploadRoute);
  const acknowledged = page.waitForResponse((response) =>
    response.request().method() === 'PUT' && response.url().startsWith(`${workerUrl.origin}/v2/attachments/`) && response.ok(),
  );
  await context.setOffline(false);
  await acknowledged;
  await expect.poll(() => page.evaluate(() => {
    const app = window as unknown as { __NOTELIKEUS_NOTES_STORE__: typeof useNotesStore };
    return app.__NOTELIKEUS_NOTES_STORE__.getState().notes.flatMap((note) => note.attachments.map((attachment) => attachment.storagePath));
  })).toEqual([expect.stringMatching(/^r2:/)]);

  await page.getByRole('button', { name: 'Open settings', exact: true }).first().click();
  const exportButton = page.getByRole('button', { name: /^Export complete backup/ });
  const downloadRoute = `${workerUrl.origin}/v1/attachments/**`;
  const rejectDownloads = (route: Route) =>
    route.request().method() === 'GET' ? route.abort('connectionreset') : route.continue();
  await context.route(downloadRoute, rejectDownloads);
  const [incomplete] = await Promise.all([page.waitForEvent('download'), exportButton.click()]);
  await expect(incomplete.failure()).resolves.toBeNull();
  await expect(page.getByText(/Incomplete backup: 0 of 1 image included/)).toBeVisible();
  await context.unroute(downloadRoute, rejectDownloads);

  const [complete] = await Promise.all([page.waitForEvent('download'), exportButton.click()]);
  const bundlePath = info.outputPath('recovered.nlkbak');
  await complete.saveAs(bundlePath);
  await expect(page.getByText('Complete backup exported with all 1 image')).toBeVisible();

  const restored = await context.browser()!.newContext();
  try {
    const restorePage = await restored.newPage();
    await restorePage.goto('/');
    await restorePage.getByRole('button', { name: 'Continue without an account' }).click();
    await restorePage.getByRole('button', { name: 'Open settings', exact: true }).first().click();
    const [chooser] = await Promise.all([
      restorePage.waitForEvent('filechooser'), restorePage.getByRole('button', { name: /^Import/ }).first().click(),
    ]);
    await chooser.setFiles(bundlePath);
    await restorePage.getByRole('dialog').getByRole('button', { name: 'Import', exact: true }).click();
    await restorePage.getByRole('button', { name: /^Large attachment recovery(?:,|$)/ }).click({ timeout: 15_000 });
    const restoredImage = restorePage.getByAltText('Note attachment');
    await expect(restoredImage).toBeVisible();
    await expect.poll(() => restoredImage.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBe(1600);
    const restoredHash = await restoredImage.evaluate(async (element: HTMLImageElement) => {
      const bytes = await (await fetch(element.src)).arrayBuffer();
      const hash = await crypto.subtle.digest('SHA-256', bytes);
      return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('');
    });
    expect(restoredHash).toBe(createHash('sha256').update(image).digest('hex'));
  } finally {
    await restored.close();
  }
});
