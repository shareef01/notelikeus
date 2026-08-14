import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end suite. Runs the real app in a real browser against the Firebase emulators.
 *
 * This exists to answer the one question no other suite can: whether the Firebase SDK actually
 * works in a browser. The unit suite is pure functions, and the emulator sync suite runs under
 * Node — neither loads the production bundle, so neither would catch a bundling or browser-runtime
 * regression from an SDK upgrade.
 *
 * `npm run test:e2e` builds the app, starts the emulators, and serves the build. The dev server is
 * deliberately not used: bundling differences are part of what this is checking.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list']] : [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run preview -- --port 4173 --host 127.0.0.1',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
