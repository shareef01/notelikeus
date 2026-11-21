import type { Page } from '@playwright/test';

/**
 * Waits until the service worker is active and controls the page.
 *
 * A worker installed by the first load activates a moment later and claims the page
 * (`clients.claim()` in sw.ts). A reload that starts in that moment is aborted by the claim:
 * Playwright reports `net::ERR_ABORTED; maybe frame was detached?` even though the page then loads
 * normally. Waiting for control first removes the race. Fails rather than hangs if control never comes.
 */
export async function waitForServiceWorkerControl(page: Page, timeoutMs = 15_000): Promise<void> {
  await page.evaluate(async (timeout) => {
    if (!('serviceWorker' in navigator)) return;
    const control = (async () => {
      await navigator.serviceWorker.ready;
      if (navigator.serviceWorker.controller) return;
      await new Promise<void>((resolve) => {
        navigator.serviceWorker.addEventListener('controllerchange', () => resolve(), { once: true });
      });
    })();
    const timedOut = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error(`the service worker did not take control within ${timeout} ms`)), timeout);
    });
    await Promise.race([control, timedOut]);
  }, timeoutMs);
}

/** `page.reload()`, after the service worker controls the page (see above). */
export async function reloadWhenServiceWorkerControls(
  page: Page,
  options?: Parameters<Page['reload']>[0],
): Promise<void> {
  await waitForServiceWorkerControl(page);
  await page.reload(options);
}
