// Captures the secondary screens of the 2026-10-29 UI/UX audit — conflict review, reminder
// picker, editor layouts, settings, sign-in — against a running dev server (default
// http://localhost:5173), in guest mode with synthetic notes: nothing is sent to any backend.
//
//   node scripts/captureSecondaryScreens.mjs <outDir> [baseUrl]
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const outDir = process.argv[2];
const baseUrl = process.argv[3] ?? 'http://localhost:5173';
if (!outDir) throw new Error('usage: captureSecondaryScreens.mjs <outDir> [baseUrl]');
mkdirSync(outDir, { recursive: true });

const now = Date.now();
const HOUR = 3_600_000;
const note = (id, fields) => ({
  id, title: '', content: '', timestamp: now - id * HOUR, color: 0, isPinned: false,
  isArchived: false, isTrashed: false, position: id, labels: [], checklist: [], ...fields,
});
const items = (texts, checked = []) =>
  texts.map((text, position) => ({ text, isChecked: checked.includes(position), position }));
const backup = {
  version: 3, exportedAt: now, app: 'Notelikeus', appVersion: '2.4.0', labels: [],
  notes: [
    note(1, { title: 'Groceries', checklist: items(['Oat milk', 'Eggs', 'Sourdough', 'Lemons'], [1]) }),
    note(2, { title: 'Groceries (conflicted copy)', checklist: items(['Oat milk', 'Eggs', 'Bread (added on the laptop)', 'Lemons'], [0]) }),
    ...Array.from({ length: 10 }, (_, i) => note(10 + i, { title: `Errand ${i + 1}`, content: 'Pick up\nDrop off\nPay' })),
    note(3, { title: 'Trip plan', content: 'Day 1: arrive, walk the old town.\nDay 2: coastal train to Cascais.\nDay 3: museums, then the airport.\n'.repeat(4) }),
  ],
};
const seedPath = join(mkdtempSync(join(tmpdir(), 'nlk-secondary-')), 'seed.json');
writeFileSync(seedPath, JSON.stringify(backup));

const shot = (page, name) => page.screenshot({ path: join(outDir, `${name}.png`) });

const browser = await chromium.launch();
try {
  for (const [vp, width, height] of [['1280', 1280, 800], ['390', 390, 844]]) {
    const ctx = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' });
    const page = await ctx.newPage();
    await page.goto(baseUrl);
    await page.getByRole('button', { name: 'Continue without an account' }).waitFor();
    await page.waitForTimeout(300);
    await shot(page, `auth-${vp}-light`);
    await page.getByRole('button', { name: 'Continue without an account' }).click();
    await page.getByRole('button', { name: 'New note' }).first().waitFor();
    await page.locator('input[type="file"][accept*="json"]').first().setInputFiles(seedPath);
    await page.getByRole('button', { name: 'Import', exact: true }).click();
    await page.getByText('Trip plan').first().waitFor();
    await page.waitForTimeout(6000);

    // Conflict review, opened from the card's chip.
    await page.getByRole('button', { name: /^Conflict copy/ }).first().click();
    await page.getByRole('dialog').waitFor();
    await page.waitForTimeout(300);
    await shot(page, `conflict-review-${vp}-light`);
    await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
    await page.waitForTimeout(200);
    await shot(page, `conflict-review-${vp}-dark`);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);

    // Editor, and its reminder picker with a time in the past.
    await page.getByRole('button', { name: /^Trip plan/ }).first().click();
    await page.getByRole('textbox', { name: 'Title' }).waitFor();
    await page.waitForTimeout(300);
    await shot(page, `editor-${vp}-light`);
    if (width >= 768) {
      for (const layout of ['Dock note', 'Full screen', 'Float note']) {
        await page.getByRole('radio', { name: layout }).or(page.getByRole('button', { name: layout })).first().click();
        await page.waitForTimeout(400);
        await shot(page, `editor-${layout.split(' ')[0].toLowerCase()}-${vp}-light`);
      }
    }
    if (width >= 768) {
      // Dock again and scroll the page: the docked editor and the sidebar must stay put.
      await page.getByRole('radio', { name: 'Dock note' }).or(page.getByRole('button', { name: 'Dock note' })).first().click();
      await page.waitForTimeout(400);
      await page.mouse.move(400, 500);
      await page.mouse.wheel(0, 1200);
      await page.waitForTimeout(400);
      await shot(page, `editor-dock-scrolled-${vp}-light`);
    }
    await page.getByRole('button', { name: 'Set reminder' }).first().click();
    await page.getByRole('dialog', { name: 'Set reminder' }).waitFor();
    await page.getByLabel('Or choose date & time').fill('2020-01-01T09:00');
    await page.waitForTimeout(200);
    await shot(page, `reminder-past-${vp}-light`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);

    await page.getByRole('button', { name: 'Open settings' }).click();
    await page.waitForTimeout(400);
    await shot(page, `settings-${vp}-light`);
    await ctx.close();
  }
} finally {
  await browser.close();
}
console.log('captured to', outDir);
