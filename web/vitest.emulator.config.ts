import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Emulator-backed suite for the Firestore sync layer.
 *
 * Separate from the default config because these need a live Firestore emulator — `npm test` stays
 * fast and dependency-free, while `npm run test:sync` starts the emulator and runs these.
 *
 * Node environment rather than happy-dom: the sync layer under test talks to Firestore over the
 * network and does not touch the DOM, and the Firestore SDK picks a more predictable transport
 * under Node than under a simulated browser.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.emulator.test.ts'],
    // A cold emulator plus SDK handshake is slower than the 5s default.
    testTimeout: 30_000,
    hookTimeout: 30_000,
    // These share one emulator and one project id; parallel files would see each other's writes.
    fileParallelism: false,
  },
});
