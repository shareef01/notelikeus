// Captures the web UI at the audit's reference widths, in light and dark, against a running dev
// server (default http://localhost:5173). Guest mode only: nothing is sent to any backend.
//
//   node scripts/captureUiScreens.mjs <outDir> [baseUrl]
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const outDir = process.argv[2];
const baseUrl = process.argv[3] ?? 'http://localhost:5173';
if (!outDir) throw new Error('usage: captureUiScreens.mjs <outDir> [baseUrl]');
mkdirSync(outDir, { recursive: true });

const NOTES = [
  { title: 'Weekend in Lisbon', body: 'Flights land Friday 18:40.\nTram 28 early, before the crowds.\nPastéis de Belém — go on Saturday morning.' },
  { title: 'Groceries', body: '- [ ] Oat milk\n- [x] Eggs\n- [ ] Sourdough\n- [ ] Lemons\n- [x] Coffee beans' },
  { title: 'Q4 planning — notes from the offsite with the platform team', body: 'Three themes: sync reliability, onboarding, and search speed. **Owner** for each by Friday.' },
  { title: '', body: 'Call the dentist about moving the appointment to the 21st.' },
  { title: 'Book list', body: 'The Left Hand of Darkness\nPiranesi\nThe Dispossessed\nKlara and the Sun' },
  { title: 'Wi-Fi at the studio', body: 'Network: studio-5g\nAsk Priya for the guest code.' },
  { title: 'Ideas for the garden', body: 'Raised bed by the fence. Tomatoes, basil, and a row of marigolds to keep pests away.' },
  { title: 'Groceries (conflicted copy)', body: '- [ ] Oat milk\n- [ ] Bread (added on the laptop)' },
  { title: 'Recipe: weeknight dal', body: '1 cup red lentils, 1 onion, 2 cloves garlic, cumin, turmeric, chilli. 25 minutes.' },
];

async function seed(page) {
  await page.goto(baseUrl);
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  for (const note of NOTES) {
    await page.getByRole('button', { name: 'New note' }).first().click();
    if (note.title) await page.getByRole('textbox', { name: 'Title' }).fill(note.title);
    await page.getByRole('textbox', { name: 'Note body' }).fill(note.body);
    await page.getByRole('button', { name: 'Back' }).click();
    await page.waitForTimeout(150);
  }
}

const WIDTHS = [
  ['desktop-1440', 1440, 900],
  ['desktop-1280', 1280, 800],
  ['tablet-768', 768, 1024],
  ['mobile-390', 390, 844],
  ['mobile-360', 360, 740],
];

const browser = await chromium.launch();
try {
  // Empty workspace first, in a fresh profile.
  {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const page = await ctx.newPage();
    await page.goto(baseUrl);
    await page.screenshot({ path: join(outDir, 'auth-1280-light.png') });
    await page.getByRole('button', { name: 'Continue without an account' }).click();
    await page.waitForTimeout(400);
    await page.screenshot({ path: join(outDir, 'empty-1280-light.png') });
    await ctx.close();
  }

  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();
  await seed(page);

  for (const scheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: 'reduce' });
    for (const [name, width, height] of WIDTHS) {
      await page.setViewportSize({ width, height });
      await page.waitForTimeout(250);
      await page.screenshot({ path: join(outDir, `notes-${name}-${scheme}.png`) });
    }
  }

  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole('button', { name: 'Weekend in Lisbon' }).first().click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outDir, 'editor-1440-light.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  await page.screenshot({ path: join(outDir, 'editor-390-light.png') });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(baseUrl);
  const guest = page.getByRole('button', { name: 'Continue without an account' });
  await page.waitForTimeout(800);
  if (await guest.isVisible().catch(() => false)) await guest.click();
  await page.getByRole('searchbox', { name: 'Search notes' }).fill('lentils');
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outDir, 'search-1440-light.png') });
  await page.getByRole('searchbox', { name: 'Search notes' }).fill('');

  await page.getByRole('button', { name: 'Open settings' }).click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: join(outDir, 'settings-1440-light.png') });

  await ctx.close();
} finally {
  await browser.close();
}
console.log('captured to', outDir);
