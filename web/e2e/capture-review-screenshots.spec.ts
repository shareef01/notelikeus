import { expect, test } from '@playwright/test';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';
import { leaveEditor, openNewNote, typeNewNoteTitle } from './helpers/newNote';

const currentDir = dirname(fileURLToPath(import.meta.url));
const outputDir = join(currentDir, '..', '..', 'docs', 'evidence', 'web-review');
mkdirSync(outputDir, { recursive: true });

test('capture complete visual verification screenshots', async ({ page }) => {
  test.setTimeout(120_000);

  // 1. Initial Empty State at 1440x900
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.screenshot({ path: join(outputDir, '01-desktop-empty-state-1440x900.png') });

  // 2. Create 4 realistic test notes
  // Note 1: Architecture note
  await openNewNote(page);
  await typeNewNoteTitle(page, 'Local-First Architecture & Privacy');
  const body1 = page.getByRole('textbox', { name: 'Note body' }).first();
  await body1.click();
  await body1.fill('Notelikeus operates strictly on local-first durability using WebCrypto AES-GCM and IndexedDB.\n\n- Zero cloud telemetry\n- Instant search\n- Offline encryption at rest');
  await leaveEditor(page);
  await expect(page.getByRole('heading', { name: 'Local-First Architecture & Privacy' })).toBeVisible({ timeout: 15_000 });

  // Note 2: Design Tokens note
  await openNewNote(page);
  await typeNewNoteTitle(page, 'Design Tokens & Quiet Tactile Elevation');
  const body2 = page.getByRole('textbox', { name: 'Note body' }).first();
  await body2.click();
  await body2.fill('Tactile card hover elevation with subtle border lift:\n- Decouple text typography from brand accent\n- Eliminate backdrop-blur compositing stutter\n- Implement W3C ARIA APG roving tabindex toolbar');
  const pinBtn = page.getByRole('button', { name: 'Pin', exact: true }).first();
  if (await pinBtn.isVisible()) await pinBtn.click();
  await leaveEditor(page);
  await expect(page.getByRole('heading', { name: 'Design Tokens & Quiet Tactile Elevation' })).toBeVisible({ timeout: 15_000 });

  // Note 3: Staging note
  await openNewNote(page);
  await typeNewNoteTitle(page, 'Staging Verification & Performance Gate');
  const body3 = page.getByRole('textbox', { name: 'Note body' }).first();
  await body3.click();
  await body3.fill('Validating 10,000 notes benchmark throughput, WebKit reflow at 320px, and PWA service worker precaching across all devices.');
  await leaveEditor(page);
  await expect(page.getByRole('heading', { name: 'Staging Verification & Performance Gate' })).toBeVisible({ timeout: 15_000 });

  // Note 4: Key Rotation note
  await openNewNote(page);
  await typeNewNoteTitle(page, 'Cryptographic Key Rotation & Recovery');
  const body4 = page.getByRole('textbox', { name: 'Note body' }).first();
  await body4.click();
  await body4.fill('Envelope encryption with PBKDF2 key derivation protects user content even before authentication.');
  await leaveEditor(page);
  await expect(page.getByRole('heading', { name: 'Cryptographic Key Rotation & Recovery' })).toBeVisible({ timeout: 15_000 });

  // Wait for layout to settle
  await page.waitForTimeout(500);

  // 3. Desktop 1440x900 Light Theme
  await page.screenshot({ path: join(outputDir, '02-desktop-notes-grid-light-1440x900.png') });

  // 4. Desktop 1440x900 Dark Theme
  await page.evaluate(() => {
    document.documentElement.classList.remove('theme-light');
    document.documentElement.classList.add('theme-dark', 'dark');
  });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '03-desktop-notes-grid-dark-1440x900.png') });

  // 5. Desktop 1440x900 OLED Theme
  await page.evaluate(() => {
    document.documentElement.classList.add('amoled');
  });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '04-desktop-notes-grid-oled-1440x900.png') });

  // Reset to Light Theme
  await page.evaluate(() => {
    document.documentElement.classList.remove('theme-dark', 'dark', 'amoled');
    document.documentElement.classList.add('theme-light');
  });
  await page.waitForTimeout(300);

  // 6. Sidebar Collapsed at 1440x900
  const collapseBtn = page.getByRole('button', { name: 'Collapse sidebar' });
  if (await collapseBtn.isVisible().catch(() => false)) {
    await collapseBtn.click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(outputDir, '05-desktop-sidebar-collapsed-1440x900.png') });
    await page.getByRole('button', { name: 'Expand sidebar' }).click();
    await page.waitForTimeout(300);
  }

  // 7. Search Results with Filter Chips
  const searchInput = page.getByRole('searchbox');
  await searchInput.fill('Architecture');
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '06-desktop-search-results-1440x900.png') });
  await page.getByRole('button', { name: 'Clear search' }).click();
  await page.waitForTimeout(300);

  // 8. Multi-selection Mode
  const firstCheckbox = page.locator('article[data-note-id] button[role="checkbox"]').first();
  await firstCheckbox.click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '07-desktop-multi-selection-1440x900.png') });
  await page.getByRole('button', { name: 'Clear selection' }).click();
  await page.waitForTimeout(300);

  // 9. Rich-text Editor Open at 1440x900
  await page.locator('button[data-note-open]').first().click();
  await expect(page.getByRole('toolbar', { name: 'Text formatting' })).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '08-desktop-editor-open-1440x900.png') });
  await leaveEditor(page);
  await page.waitForTimeout(300);

  // 10. Label Management Modal
  const editLabelsBtn = page.getByRole('button', { name: 'Edit labels' });
  if (await editLabelsBtn.isVisible().catch(() => false)) {
    await editLabelsBtn.click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(outputDir, '09-desktop-labels-management-1440x900.png') });
    await page.getByRole('button', { name: 'Close' }).click();
    await page.waitForTimeout(300);
  }

  // 11. Settings & Appearance Sheet
  const settingsBtn = page.getByRole('button', { name: 'Open settings' });
  if (await settingsBtn.isVisible().catch(() => false)) {
    await settingsBtn.click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(outputDir, '10-desktop-settings-sheet-1440x900.png') });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }

  // 12. Laptop Viewport: 1280 × 800
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '11-laptop-notes-grid-1280x800.png') });

  // 13. Tablet Viewport: 768 × 1024
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '12-tablet-notes-grid-768x1024.png') });

  // 14. Mobile Viewport: 390 × 844 Light
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '13-mobile-notes-grid-light-390x844.png') });

  // 15. Mobile Viewport: 390 × 844 Dark
  await page.evaluate(() => {
    document.documentElement.classList.remove('theme-light');
    document.documentElement.classList.add('theme-dark', 'dark');
  });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '14-mobile-notes-grid-dark-390x844.png') });

  // 16. Mobile Editor at 390 × 844
  await page.locator('button[data-note-open]').first().click();
  await expect(page.getByRole('toolbar', { name: 'Text formatting' })).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '15-mobile-editor-390x844.png') });
  await leaveEditor(page);
  await page.waitForTimeout(300);

  // 17. Small Mobile Reflow: 320 × 700
  await page.setViewportSize({ width: 320, height: 700 });
  await page.evaluate(() => {
    document.documentElement.classList.remove('theme-dark', 'dark');
    document.documentElement.classList.add('theme-light');
  });
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outputDir, '16-small-mobile-reflow-320x700.png') });
});
