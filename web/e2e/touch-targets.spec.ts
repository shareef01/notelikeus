import { expect, test, type Page } from '@playwright/test';

/**
 * Touch-target contract, measured rather than declared.
 *
 * A survey of the three main screens at 390px found nothing below WCAG 2.5.8's 24px minimum except
 * one control — the editor's "+ Add checklist", at 103x20 — plus a long tail under the 44px this
 * design system treats as comfortable: 15 controls on the notes screen, 23 in the editor, and the
 * 42px primary buttons on the entry screen.
 *
 * The shared `.tap-target` interaction state expands a control's hit area to 44px without changing
 * how it looks. The first test below is the one that matters over time: it fails if anything drops
 * back under the AA minimum, wherever it is added.
 */
async function enterNotes(page: Page): Promise<void> {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });
}

/**
 * Every visible interactive control whose *effective touch target* is smaller than the given size.
 *
 * Target, not box: `.tap-target` expands the hit area with a pseudo-element, and
 * `getBoundingClientRect()` cannot see pseudo-elements, so measuring the box alone would report a
 * control as failing when a tap on it demonstrably works. This is the measurement the guideline is
 * about — "Add checklist" is 20px tall and 44px tappable.
 */
async function controlsSmallerThan(page: Page, size: number) {
  return page.evaluate((min) => {
    const out: string[] = [];
    const candidates = document.querySelectorAll(
      'button, a[href], [role="button"], input[type="checkbox"], input[type="radio"], select',
    );
    for (const el of candidates) {
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      if (rect.width === 0 || rect.height === 0) continue;
      if (style.visibility === 'hidden' || style.display === 'none') continue;
      if (el.closest('[aria-hidden="true"]')) continue;

      let w = rect.width;
      let h = rect.height;
      if (el.classList.contains('tap-target')) {
        const after = getComputedStyle(el, '::after');
        w = Math.max(w, Number.parseFloat(after.width) || 0);
        h = Math.max(h, Number.parseFloat(after.height) || 0);
      }
      if (w < min || h < min) {
        const name = (el.getAttribute('aria-label') || el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 40);
        out.push(`${Math.round(w)}x${Math.round(h)} "${name}" (visual ${Math.round(rect.width)}x${Math.round(rect.height)})`);
      }
    }
    return out;
  }, size);
}

test('nothing on the notes screen falls below the 24px minimum', async ({ page }) => {
  await enterNotes(page);
  expect(await controlsSmallerThan(page, 24)).toEqual([]);
});

test('nothing in the editor falls below the 24px minimum', async ({ page }) => {
  await enterNotes(page);
  await page.getByRole('button', { name: 'Add note' }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });
  // "+ Add checklist" was 103x20 before .tap-target existed.
  expect(await controlsSmallerThan(page, 24)).toEqual([]);
});

test('checklist mode in the editor has no undersized targets either', async ({ page }) => {
  await enterNotes(page);
  await page.getByRole('button', { name: 'Add note' }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });

  // Journey C. The checkboxes here were 20x20, and this suite did not look at this mode at all —
  // the gap that let a sub-minimum target survive the first pass.
  await page.getByRole('button', { name: '+ Add checklist' }).click();
  await page.waitForTimeout(800);
  await page.keyboard.type('milk');
  await page.keyboard.press('Enter');
  await page.keyboard.type('eggs');
  await page.waitForTimeout(800);

  expect(await controlsSmallerThan(page, 24)).toEqual([]);
});

test('the editor options sheet is measured too — it holds the reminder flow', async ({ page }) => {
  await enterNotes(page);
  await page.getByRole('button', { name: 'Add note' }).first().click();
  await expect(page.getByRole('textbox', { name: 'Note title' }).first()).toBeVisible({ timeout: 15_000 });

  // The sheet is where reminders, labels, colour and note actions live, and none of it was covered
  // until F19 found 26px preset buttons in here.
  await page.getByRole('button', { name: /more options/i }).first().click();
  await page.waitForTimeout(1_200);

  // Measured against the effective target, as everywhere in this file: the reminders carry
  // .tap-target-y, so their boxes stay small while their targets are 44px.
  expect(await controlsSmallerThan(page, 24)).toEqual([]);

  const targets = await page.evaluate(() => {
    const want = ['In 1 hour', 'Tomorrow 9:00', 'Next week'];
    const out: { name: string; target: number }[] = [];
    for (const el of document.querySelectorAll('button, input')) {
      const name = (el.textContent || el.getAttribute('aria-label') || '').trim();
      if (!want.includes(name) && el.getAttribute('aria-label') !== 'Reminder date and time') continue;
      const after = getComputedStyle(el, '::after');
      out.push({ name, target: Number.parseFloat(after.height) || 0 });
    }
    return out;
  });
  expect(targets.length, 'the reminder controls should be present').toBeGreaterThan(0);
  for (const t of targets) {
    expect(t.target, `"${t.name}" should have a 44px target`).toBeGreaterThanOrEqual(44);
  }
});

test('the controls that need it carry a 44px hit area', async ({ page }) => {
  await enterNotes(page);
  const sizes = await page.evaluate(() =>
    [...document.querySelectorAll('.tap-target')]
      // Only what is on screen: the shell renders md-only controls that are display:none at 390px,
      // where a pseudo-element has no computed height at all.
      .filter((el) => {
        const rect = el.getBoundingClientRect();
        const style = getComputedStyle(el);
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
      })
      .map((el) => {
        const after = getComputedStyle(el, '::after');
        return {
          name: (el.getAttribute('aria-label') || el.textContent || '').trim().slice(0, 30),
          h: Math.round(Number.parseFloat(after.height) || 0),
        };
      }),
  );
  expect(sizes.length).toBeGreaterThan(0);
  for (const s of sizes) {
    expect(s.h, `"${s.name}" should have a 44px hit area`).toBeGreaterThanOrEqual(44);
  }
});

test('the expanded hit area really receives the click', async ({ page }) => {
  await enterNotes(page);
  await page.getByRole('button', { name: 'Add note' }).first().click();
  const checklist = page.getByRole('button', { name: '+ Add checklist' });
  await expect(checklist).toBeVisible({ timeout: 15_000 });

  // The visual box is 20px tall; 8px above it is only reachable through the expanded target.
  const box = (await checklist.boundingBox())!;
  await page.mouse.click(box.x + box.width / 2, box.y - 8);
  await page.waitForTimeout(900);
  await expect(checklist, 'a click inside the hit area should activate the control').toHaveCount(0);
});
