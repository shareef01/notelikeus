// Captures the README's web screenshots (screenshots/web-*.png) against a running dev server,
// in guest mode: nothing is sent to any backend. The notes come from a .nlkbak bundle, so the same
// seed can be imported on Android and Windows for their screenshots.
//
//   node scripts/captureReadmeScreens.mjs scripts/readme-screenshot-seed.nlkbak <outDir> [baseUrl] [signInUrl]
//
// readme-screenshot-seed.nlkbak holds the seven notes, four labels and one image the README shows;
// import the same file on Android and Windows (Settings, Import backup) for their screenshots.
//
// signInUrl defaults to the production site, whose sign-in screen carries the Turnstile check.
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium } from '@playwright/test';

const [seed, outDir, baseUrl = 'http://localhost:5173', signInUrl = 'https://notelikeus.pages.dev'] =
  process.argv.slice(2);
if (!seed || !outDir) {
  throw new Error('usage: captureReadmeScreens.mjs <seed.nlkbak> <outDir> [baseUrl] [signInUrl]');
}
mkdirSync(outDir, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: 1280, height: 720 },
  colorScheme: 'dark',
  reducedMotion: 'reduce',
});
const page = await context.newPage();

await page.goto(baseUrl);
await page.getByRole('button', { name: 'Continue without an account' }).click();
await page.locator('input[type="file"][accept*="nlkbak"]').setInputFiles(seed);
await page.getByRole('button', { name: 'Import', exact: true }).click();
// Wait for the import to land, then for its toast to go, so the grid is unobstructed.
await page.getByText('Lisbon weekend').first().waitFor();
await page.waitForTimeout(6000);

await page.screenshot({ path: join(outDir, 'web-notes.png') });

// The pinned image card is first in the grid. Text locators also match a hidden copy the grid
// keeps for measuring, under the sticky header, so click the visible card's body directly.
await page.mouse.click(424, 560);
await page.getByRole('textbox', { name: 'Title' }).waitFor();
await page.waitForTimeout(800);
// The editor holds focus in the title; drop it so no caret shows. A click outside would close
// the floating editor, so blur instead.
await page.evaluate(() => (document.activeElement instanceof HTMLElement ? document.activeElement.blur() : undefined));
await page.mouse.move(1270, 710);
await page.waitForTimeout(400);
await page.screenshot({ path: join(outDir, 'web-editor.png') });

const signIn = await browser.newContext({
  viewport: { width: 1280, height: 720 },
  colorScheme: 'dark',
  reducedMotion: 'reduce',
});
const signInPage = await signIn.newPage();
await signInPage.goto(signInUrl);
await signInPage.getByRole('button', { name: 'Send code' }).waitFor();
// The Turnstile frame renders after Cloudflare's script loads.
await signInPage.waitForTimeout(5000);
await signInPage.screenshot({ path: join(outDir, 'web-sign-in.png') });

await browser.close();
