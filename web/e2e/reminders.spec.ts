import { expect, test, type BrowserContext, type Page } from '@playwright/test';

/**
 * Journey J — reminders: set, change, clear, custom time, reopen, reload.
 *
 * ## Why this file stubs the Notification API
 *
 * The first version failed at the preset click, which looked like a defect. Two causes, measured rather
 * than guessed, and neither is the product:
 *
 * 1. `setReminderTimestamp` refuses to save a reminder without notification permission — with a comment
 *    saying why ("a reminder saved without notification permission would silently never fire"). That is
 *    correct behaviour.
 * 2. **Headless Chromium cannot report a granted notification permission.** Measured directly:
 *    `context.grantPermissions(['notifications'])` succeeds and `navigator.permissions.query` reports
 *    `granted`, but `Notification.permission` still reads `denied` — there is no notification backend —
 *    and no service worker is registered either. The app reads `Notification.permission`, so it refuses.
 *
 * So the permission is stubbed here, deliberately and visibly, because the browser cannot provide it. The
 * stub replaces *only* the API the environment lacks; nothing else about the flow is simulated. The
 * unstubbed refusal path is asserted in its own test below, so the stub cannot hide a real regression in
 * the permission check.
 *
 * ## What this file does and does not claim
 *
 * - **Configuration and persistence** are verified here, both browser projects.
 * - **Scheduling** is not: no test can show a service worker firing later. Note also that the worker is
 *   not registered in this build at all (measured), so scheduling is doubly out of reach.
 * - **Delivery** is never claimed. It needs an OS notification surface and a browser that is closed or
 *   asleep. The app tells users as much: "Web reminders are best-effort. They can be delayed or missed if
 *   the browser is fully closed or inactive."
 */
const GRANT_NOTIFICATIONS = () => {
  // Replaces the API headless Chromium cannot supply, and nothing else.
  Object.defineProperty(Notification, 'permission', { configurable: true, get: () => 'granted' });
  Object.defineProperty(Notification, 'requestPermission', { configurable: true, value: async () => 'granted' });
};

async function editorWithNote(context: BrowserContext, stub: boolean): Promise<Page> {
  if (stub) await context.addInitScript(GRANT_NOTIFICATIONS);
  const page = await context.newPage();
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'New note', exact: true }).first().click();
  await page.getByRole('textbox', { name: 'Note title' }).first().fill('REMINDER note');
  await page.waitForTimeout(1_200);
  return page;
}

async function openOptions(page: Page): Promise<void> {
  await page.getByRole('button', { name: /more options/i }).first().click();
  await page.waitForTimeout(1_100);
}

const closeOptions = async (page: Page) => {
  await page.keyboard.press('Escape');
  await page.waitForTimeout(1_000);
};

/** The reminder section's own state: the line the user reads, and the exact value behind it. */
const reminderState = (page: Page) =>
  page.evaluate(() => {
    const input = document.querySelector('input[aria-label="Reminder date and time"]') as HTMLInputElement | null;
    const sheet = [...document.querySelectorAll('[role="dialog"]')].pop();
    const text = (sheet?.textContent ?? '').replace(/\s+/g, ' ');
    return {
      noReminder: /No reminder set/.test(text),
      inputValue: input?.value ?? null,
      clearOffered: [...document.querySelectorAll('button')].some((el) => /clear reminder/i.test(el.textContent ?? '')),
    };
  });

const setReminder = async (page: Page, label: string) => {
  await page.getByRole('button', { name: label, exact: true }).first().click();
  await page.waitForTimeout(1_600);
};

const touch = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

const desktop = (browser: import('@playwright/test').Browser) =>
  browser.newContext({ viewport: { width: 1440, height: 900 } });

test('a preset sets a reminder, another changes it, and clearing removes it', async ({ browser }) => {
  const context = await touch(browser);
  const page = await editorWithNote(context, true);
  await openOptions(page);

  expect((await reminderState(page)).noReminder, 'a fresh note has no reminder').toBe(true);

  await setReminder(page, 'In 1 hour');
  const set = await reminderState(page);
  expect(set.noReminder, 'the preset should set a reminder').toBe(false);
  expect(set.inputValue, 'and a concrete value behind it').toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/);
  expect(set.clearOffered, 'and offer to clear it').toBe(true);

  await setReminder(page, 'Next week');
  const changed = await reminderState(page);
  expect(changed.inputValue, 'a different preset moves the time').not.toBe(set.inputValue);

  await page.getByRole('button', { name: /clear reminder/i }).first().click();
  await page.waitForTimeout(1_600);
  const cleared = await reminderState(page);
  expect(cleared.noReminder, 'clearing returns to the empty state').toBe(true);
  expect(cleared.clearOffered, 'and the clear control goes away').toBe(false);
  expect(cleared.inputValue, 'and the value is empty').toBe('');
  await context.close();
});

test('a custom date and time can be entered, and survives reopening the note', async ({ browser }) => {
  const context = await desktop(browser);
  const page = await editorWithNote(context, true);
  await openOptions(page);

  const when = '2030-06-15T09:30';
  await page.getByLabel('Reminder date and time').fill(when);
  await page.waitForTimeout(1_700);
  const set = await reminderState(page);
  expect(set.noReminder, 'the entered time should be accepted').toBe(false);
  expect(set.inputValue, 'and round-trip into the field').toBe(when);

  await closeOptions(page);
  await openOptions(page);
  expect((await reminderState(page)).inputValue, 'leaving and returning should not lose it').toBe(when);

  // Reopen the note itself, not just the sheet.
  await closeOptions(page);
  const back = page.getByRole('button', { name: /\bback\b/i }).first();
  if (await back.count()) await back.click();
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(1_600);
  await expect(page.getByText('REMINDER note').first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'REMINDER note' }).first().click();
  await page.waitForTimeout(1_800);
  await openOptions(page);
  expect((await reminderState(page)).inputValue, 'reopening the note should not lose it').toBe(when);
  await context.close();
});

test('a reminder survives a reload the way guest notes do', async ({ browser }) => {
  const context = await touch(browser);
  const page = await editorWithNote(context, true);
  await openOptions(page);
  await setReminder(page, 'In 1 hour');
  const before = await reminderState(page);
  expect(before.noReminder).toBe(false);

  await closeOptions(page);
  const back = page.getByRole('button', { name: /\bback\b/i }).first();
  if (await back.count()) await back.click();
  else await page.keyboard.press('Escape');
  await page.waitForTimeout(1_600);

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForTimeout(2_500);
  const signIn = page.getByRole('button', { name: 'Continue without an account' });
  await expect(signIn).toBeVisible({ timeout: 20_000 });
  await signIn.click();
  await page.waitForTimeout(1_800);

  // Guest notes persist even though the session does not (F10); the reminder should come with it.
  await expect(page.getByText('REMINDER note').first()).toBeVisible({ timeout: 20_000 });
  await page.getByRole('button', { name: 'REMINDER note' }).first().click();
  await page.waitForTimeout(1_800);
  await openOptions(page);
  expect((await reminderState(page)).inputValue, 'the reminder should come back with the note').toBe(before.inputValue);
  await context.close();
});

test('unstubbed, the app refuses without permission and says why', async ({ browser }) => {
  // No stub here: this is the real browser state, and the check the stub must not be hiding.
  const context = await touch(browser);
  const page = await editorWithNote(context, false);
  await openOptions(page);

  await setReminder(page, 'In 1 hour');
  expect((await reminderState(page)).noReminder, 'nothing is saved without permission').toBe(true);
  const explained = await page.evaluate(() => /notifications/i.test(document.body.innerText));
  expect(explained, 'and the refusal is explained rather than silent').toBe(true);
  await context.close();
});
