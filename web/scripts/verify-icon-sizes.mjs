import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(__dirname, '../public');

function readPngDimensions(filePath) {
  const buf = fs.readFileSync(filePath);
  // PNG signature: 89 50 4E 47 0D 0A 1A 0A
  if (buf.readUInt32BE(0) !== 0x89504e47 || buf.readUInt32BE(4) !== 0x0d0a1a0a) {
    throw new Error(`Not a valid PNG file: ${filePath}`);
  }
  const width = buf.readUInt32BE(16);
  const height = buf.readUInt32BE(20);
  return { width, height };
}

const icon192 = readPngDimensions(path.join(publicDir, 'icons/icon-192.png'));
if (icon192.width !== 192 || icon192.height !== 192) {
  throw new Error(`icon-192.png has unexpected dimensions: ${icon192.width}x${icon192.height}`);
}

const icon512 = readPngDimensions(path.join(publicDir, 'icons/icon-512.png'));
if (icon512.width !== 512 || icon512.height !== 512) {
  throw new Error(`icon-512.png has unexpected dimensions: ${icon512.width}x${icon512.height}`);
}

const manifestPath = path.join(publicDir, 'manifest.webmanifest');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));

for (const icon of manifest.icons || []) {
  if (icon.src.includes('192') && icon.sizes !== '192x192') {
    throw new Error(`Manifest declares size ${icon.sizes} for ${icon.src}, expected 192x192`);
  }
  if (icon.src.includes('512') && icon.sizes !== '512x512') {
    throw new Error(`Manifest declares size ${icon.sizes} for ${icon.src}, expected 512x512`);
  }
}

console.log('✓ PWA icon dimensions and manifest sizes verified truthfully: 192x192 and 512x512');
