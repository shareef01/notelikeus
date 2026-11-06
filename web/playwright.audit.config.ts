import { defineConfig } from '@playwright/test';
import base from './playwright.config';

// Guest-only checks: this matrix needs no running Supabase instance. Browser locale does not
// imply translated or RTL application content; the product currently renders its English UI.
export default defineConfig({
  ...base,
  outputDir: './test-results/audit-matrix',
  projects: [
    { name: 'berlin-de', use: { browserName: 'chromium', locale: 'de-DE', timezoneId: 'Europe/Berlin', viewport: { width: 1280, height: 900 } } },
    { name: 'la-en', use: { browserName: 'chromium', locale: 'en-US', timezoneId: 'America/Los_Angeles', viewport: { width: 1280, height: 900 } } },
    { name: 'kathmandu-ne', use: { browserName: 'chromium', locale: 'ne-NP', timezoneId: 'Asia/Kathmandu', viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true } },
    { name: 'cairo-ar', use: { browserName: 'chromium', locale: 'ar-EG', timezoneId: 'Africa/Cairo', viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true } },
  ],
});
