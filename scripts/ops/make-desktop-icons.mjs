// Builds the desktop app's icons from the brand mark in web/public/favicon.svg, the same five
// stripes Android's adaptive icon and the title bar draw:
//
//   composeApp/src/commonMain/composeResources/drawable/ic_launcher.png  window, taskbar and tray (1024 px)
//   composeApp/icons/notelikeus.ico                                     .exe, MSI, Start menu and desktop shortcut
//
// The .ico holds PNG-encoded entries at 16, 20, 24, 32, 40, 48, 64, 128 and 256 px, which every
// supported Windows version reads. Chromium does the rendering, so run `npm ci` in web/ first.
//
//   node scripts/ops/make-desktop-icons.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const { chromium } = createRequire(resolve(ROOT, 'web/package.json'))('@playwright/test');

const ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256];
const WINDOW_ICON_SIZE = 1024;

const svg = readFileSync(resolve(ROOT, 'web/public/favicon.svg'), 'utf8');
const browser = await chromium.launch();
const page = await browser.newPage();

async function render(size) {
  await page.setViewportSize({ width: size, height: size });
  await page.setContent(
    `<html><body style="margin:0;background:transparent">${svg.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`,
  );
  return page.locator('svg').screenshot({ omitBackground: true });
}

const windowIcon = await render(WINDOW_ICON_SIZE);
const entries = [];
for (const size of ICO_SIZES) entries.push({ size, png: await render(size) });
await browser.close();

// ICONDIR, then one ICONDIRENTRY per image, then the PNGs. A width or height of 0 means 256.
const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(entries.length, 4);
const directory = Buffer.alloc(16 * entries.length);
let offset = header.length + directory.length;
entries.forEach(({ size, png }, i) => {
  const at = i * 16;
  directory.writeUInt8(size === 256 ? 0 : size, at);
  directory.writeUInt8(size === 256 ? 0 : size, at + 1);
  directory.writeUInt16LE(1, at + 4);
  directory.writeUInt16LE(32, at + 6);
  directory.writeUInt32LE(png.length, at + 8);
  directory.writeUInt32LE(offset, at + 12);
  offset += png.length;
});

writeFileSync(resolve(ROOT, 'composeApp/src/commonMain/composeResources/drawable/ic_launcher.png'), windowIcon);
writeFileSync(
  resolve(ROOT, 'composeApp/icons/notelikeus.ico'),
  Buffer.concat([header, directory, ...entries.map(({ png }) => png)]),
);
console.log(`Wrote ic_launcher.png (${WINDOW_ICON_SIZE} px) and notelikeus.ico (${ICO_SIZES.join(', ')} px).`);
