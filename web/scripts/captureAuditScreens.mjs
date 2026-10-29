// Captures the states the 2026-10-29 UI/UX audit changed or checked, against a running dev server
// (default http://localhost:5173), in guest mode: nothing is sent to any backend. The notes are
// synthetic and imported as a JSON backup, so labels, colours, checklists, an untitled note, a
// conflict copy and very long text are all present without driving the editor.
//
//   node scripts/captureAuditScreens.mjs <outDir> [baseUrl]
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const outDir = process.argv[2];
const baseUrl = process.argv[3] ?? 'http://localhost:5173';
if (!outDir) throw new Error('usage: captureAuditScreens.mjs <outDir> [baseUrl]');
mkdirSync(outDir, { recursive: true });

const now = Date.now();
const HOUR = 3_600_000;
const note = (id, fields) => ({
  id,
  title: '',
  content: '',
  timestamp: now - id * HOUR,
  color: 0,
  isPinned: false,
  isArchived: false,
  isTrashed: false,
  position: id,
  labels: [],
  checklist: [],
  ...fields,
});
const LABELS = ['Travel', 'Work', 'iPhone backup', 'ToDo', 'Q4', 'Personal', 'Recipes to try this winter'];
const NOTES = [
  note(1, { title: 'Lisbon weekend', content: 'Flights land Friday 18:40.\nTram 28 early, before the crowds.', color: -4464901, isPinned: true, labels: ['Travel', 'Personal', 'ToDo'] }),
  note(2, { title: 'Standup — Thursday', content: 'Shipped sign-in with an email code\nBlocked on staging credentials', color: -8014, isPinned: true, labels: ['Work', 'Q4'] }),
  note(3, { content: 'Call the dentist about moving the appointment to the 21st.' }),
  note(4, { title: 'Groceries', checklist: ['Oat milk', 'Eggs', 'Sourdough', 'Lemons', 'Coffee beans', 'Rice'].map((text, position) => ({ text, isChecked: position % 2 === 1, position })), labels: ['Personal'] }),
  note(5, { title: 'Phone migration', content: 'Export the authenticator first.\nCheck photos finished uploading.', labels: ['iPhone backup', 'ToDo', 'Personal', 'Work'] }),
  note(6, { title: 'Groceries (conflicted copy)', content: '- [ ] Oat milk\n- [ ] Bread (added on the laptop)' }),
  note(7, { title: 'Winter stews', content: 'Lentil and chorizo. Beef and Guinness. White bean with rosemary.', color: -3610935, labels: ['Recipes to try this winter'] }),
  note(8, { title: 'Server log excerpt', content: 'https://example.com/a/very/long/path/without/any/spaces/that/would/otherwise/push/the/card/wider/than/its/column/0123456789' }),
  note(9, { title: 'Q4 planning — notes from the offsite with the platform team about sync reliability', content: 'Three themes: sync reliability, onboarding, and search speed.', labels: ['Work', 'Q4'] }),
  note(10, { content: '- [ ] Renew passport\n- [x] Book vaccinations' }),
];
const backup = { version: 3, exportedAt: now, app: 'Notelikeus', appVersion: '2.4.0', labels: LABELS.map((name, i) => ({ id: i + 1, name })), notes: NOTES };
const seedDir = mkdtempSync(join(tmpdir(), 'nlk-audit-'));
const seedPath = join(seedDir, 'audit-seed.json');
writeFileSync(seedPath, JSON.stringify(backup));

async function enter(page) {
  await page.goto(baseUrl);
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await page.getByRole('button', { name: 'New note' }).first().waitFor();
}

async function importSeed(page) {
  await page.locator('input[type="file"][accept*="json"]').first().setInputFiles(seedPath);
  const confirm = page.getByRole('button', { name: 'Import', exact: true });
  await confirm.waitFor();
  await confirm.click();
  await page.getByText('Lisbon weekend').first().waitFor();
  // Let the import toast leave so the grid is unobstructed.
  await page.waitForTimeout(6000);
}

const WIDTHS = [
  ['1440', 1440, 900],
  ['1280', 1280, 800],
  ['768', 768, 1024],
  ['390', 390, 844],
  ['320', 320, 568],
];

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  const page = await ctx.newPage();
  await enter(page);
  await importSeed(page);

  for (const scheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
    for (const [name, width, height] of WIDTHS) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(300);
      await page.screenshot({ path: join(outDir, `notes-${name}-${scheme}.png`) });
    }
  }

  // Compact and list densities at desktop width.
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 1440, height: 900 });
  for (const [mode, file] of [['Compact', 'compact'], ['List', 'list']]) {
    await page.getByRole('radio', { name: new RegExp(mode, 'i') }).first().click();
    await page.waitForTimeout(300);
    await page.screenshot({ path: join(outDir, `notes-${file}-1440-light.png`) });
  }
  await page.getByRole('radio', { name: /Grid/i }).first().click();

  // Editor: toolbar with the checklist glyph.
  await page.getByRole('button', { name: /^Lisbon weekend/ }).first().click();
  await page.getByRole('textbox', { name: 'Title' }).waitFor();
  await page.waitForTimeout(400);
  await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined));
  await page.screenshot({ path: join(outDir, 'editor-1440-light.png') });
  await page.keyboard.press('Escape');
  await page.waitForTimeout(400);

  // Recent searches reached with Tab: the panel must still be open, with the chip focused.
  const search = page.getByRole('searchbox', { name: 'Search notes' });
  await search.fill('lisbon');
  await search.press('Enter');
  await search.fill('');
  await page.mouse.click(1300, 860);
  await search.focus();
  await page.waitForTimeout(200);
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  await page.waitForTimeout(400);
  await page.screenshot({ path: join(outDir, 'search-recent-tab-1440-light.png'), clip: { x: 240, y: 0, width: 1200, height: 260 } });

  await ctx.close();
} finally {
  await browser.close();
}
console.log('captured to', outDir);
