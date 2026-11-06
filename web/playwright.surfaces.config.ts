import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// Isolated signed-in/destructive-state fixtures: callbacks never mutate an account.
export default defineConfig({
  ...base,
  testMatch: 'ui-ux-surfaces.spec.ts',
  testIgnore: [],
  outputDir: './test-results/surfaces',
  use: {...base.use, baseURL: 'http://127.0.0.1:4174'},
  webServer: {command: 'npx vite --mode e2e --host 127.0.0.1 --port 4174',
    url: 'http://127.0.0.1:4174', reuseExistingServer: !process.env.CI},
});
