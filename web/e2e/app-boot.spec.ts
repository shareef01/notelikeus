import { expect, test, type ConsoleMessage } from '@playwright/test';

/**
 * Boots the production bundle in a real browser against the Firebase emulators.
 *
 * The gap this fills: every other suite either tests pure functions or runs the sync layer under
 * Node. None of them load the built bundle in a browser, so none would catch a Firebase SDK
 * upgrade that breaks module resolution, bundling, or browser-only initialisation — which is
 * exactly the risk profile of the firebase 11 -> 12 major bump.
 */

/** Errors that say nothing about the app's health. */
function isIgnorableError(message: string): boolean {
  return (
    // No reCAPTCHA key is configured for e2e, and App Check is optional by design.
    message.includes('App Check') ||
    // The emulator has no credentials for a real Google account.
    message.includes('auth/') ||
    // Service worker registration is not served over https on 127.0.0.1 in every browser build.
    message.includes('ServiceWorker') ||
    message.includes('service worker')
  );
}

function collectConsoleErrors(page: import('@playwright/test').Page): string[] {
  const errors: string[] = [];
  page.on('console', (message: ConsoleMessage) => {
    if (message.type() === 'error' && !isIgnorableError(message.text())) {
      errors.push(message.text());
    }
  });
  page.on('pageerror', (error) => {
    if (!isIgnorableError(error.message)) errors.push(`pageerror: ${error.message}`);
  });
  return errors;
}

test('the app boots and replaces the loading splash', async ({ page }) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/');

  // index.html ships a #boot-splash placeholder, so replacing it means the bundle parsed and React
  // mounted. Deliberately not a Firebase assertion: the app renders a config-error screen when
  // initialisation fails, which also replaces the splash — verified by breaking the config, where
  // this test still passed and the two below failed. Those are the ones with teeth about Firebase;
  // this one catches a white screen or a module-resolution failure in the built bundle.
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('#root')).not.toBeEmpty();

  expect(errors, `unexpected console errors:\n${errors.join('\n')}`).toEqual([]);
});

test('Firebase initialises against the emulator rather than production', async ({ page }) => {
  const messages: string[] = [];
  page.on('console', (message) => messages.push(message.text()));

  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  // firebase.ts logs this only after connectFirestoreEmulator/connectAuthEmulator both succeed.
  // If the SDK's emulator API changed shape, this is where it surfaces.
  await expect
    .poll(() => messages.some((m) => m.includes('Using emulators at')), { timeout: 15_000 })
    .toBe(true);
});

test('the signed-out app reaches an interactive state', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });

  // No emulator user is signed in, so the app should offer a way in rather than hanging on a
  // spinner — which is what an unresolved Firebase auth promise would look like.
  const signIn = page.getByRole('button', { name: /sign in/i }).first();
  await expect(signIn).toBeVisible({ timeout: 20_000 });
});
