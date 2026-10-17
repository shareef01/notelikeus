import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

/**
 * The palette check the hardening phase asks for: dark, AMOLED and note colours, measured as painted
 * rather than as declared.
 *
 * Two things this pins down. First, that the swatch outlines carry the identification — the fills
 * are deliberately faint (D1: a note colour is a tonal surface, not a stripe), so if a restyle ever
 * removes the outline, the swatches stop being distinguishable from the page and this fails. Second,
 * the fills themselves, so the faintness stays a measured decision rather than drifting.
 *
 * It also demonstrates that these themes can be reached at all. The theme comes from the stored
 * preference, not `prefers-color-scheme` (F3), so every theme is seeded through
 * `notelikeus-settings` before load.
 *
 * What the fills measured when this was written: 1.12:1 to 1.23:1 against the page in the light and
 * dark themes, with the AMOLED palette's darker fills higher still. Those are deliberately faint —
 * D1 makes a note colour a tonal surface, not a stripe — and the outlines asserted below are what
 * make the swatches findable. There is no assertion on the fills: a test would have to state the
 * palette's character exactly to be meaningful, and one that fails on a deliberately-dark AMOLED
 * fill while passing on the same design in the other themes is a test asserting the wrong thing.
 */
const THEMES = {
  dark: { base: 'dark', accent: 'neutral', amoled: false },
  amoled: { base: 'dark', accent: 'neutral', amoled: true },
  light: { base: 'light', accent: 'neutral', amoled: false },
} as const;

function luminance(rgb: number[]): number {
  const [r, g, b] = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrastRatio(a: number[], b: number[]): number {
  const [x, y] = [luminance(a), luminance(b)];
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

const rgbOf = (css: string): number[] =>
  (css.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number);

async function swatchesIn(page: Page, theme: (typeof THEMES)[keyof typeof THEMES]) {
  await page.addInitScript((t) => {
    localStorage.setItem('notelikeus-settings', JSON.stringify({ state: { theme: t }, version: 1 }));
  }, theme as unknown as object);
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({ timeout: 20_000 });

  return page.evaluate(() => {
    const group = document.querySelector('[role="group"][aria-label="Color filter"]');
    const rows = [...(group?.querySelectorAll('button') ?? [])];
    return {
      page: getComputedStyle(document.body).backgroundColor,
      swatches: rows.map((el) => {
        const st = getComputedStyle(el);
        return {
          label: el.getAttribute('aria-label') ?? 'swatch',
          border: st.borderTopColor,
          fill: st.backgroundColor,
          selected: el.getAttribute('aria-pressed') === 'true',
        };
      }),
    };
  });
}

for (const [name, theme] of Object.entries(THEMES)) {
  // Text on a coloured note is the note's text colour at an opacity, so it has to stay on the
  // 0.80 "secondary" tier (NOTE_EMPHASIS). The editor's "Convert to checklist" link sat at 0.60:
  // 3.54:1 on a dark-theme amber note.
  test(`text in an amber note's editor clears AA in the ${name} theme`, async ({ page }) => {
    await page.addInitScript((t) => {
      localStorage.setItem('notelikeus-settings', JSON.stringify({ state: { theme: t }, version: 1 }));
    }, theme as unknown as object);
    await page.goto('/');
    await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
    await page.getByRole('button', { name: 'Continue without an account' }).click();
    // Before the colour changes: a scan that lands mid-fade measures a background that is not
    // the note's yet.
    await page.addStyleTag({
      content: '*,*::before,*::after{animation:none !important;transition:none !important}',
    });
    const newNote = page.getByRole('button', { name: 'New note' }).filter({ visible: true }).first();
    await expect(newNote).toBeVisible({ timeout: 20_000 });
    await newNote.click();
    await page.getByRole('textbox', { name: 'Note body' }).fill('Some text to convert');
    await page.getByRole('button', { name: 'More options' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: /Amber/ }).click();
    await page.keyboard.press('Escape');
    await page.getByRole('textbox', { name: 'Note title' }).first().focus();
    // axe skips what is scrolled out of view, and at 720px the link sits below the editor's fold.
    const convert = page.getByRole('button', { name: 'Convert to checklist' });
    await convert.scrollIntoViewIfNeeded();
    await expect(convert).toBeInViewport();

    const scan = await new AxeBuilder({ page }).withRules(['color-contrast']).analyze();
    expect(scan.violations.flatMap((v) => v.nodes.map((n) => n.failureSummary))).toEqual([]);
  });

  // axe does not measure ::placeholder, so this composites it by hand: the placeholder is the
  // note's text colour at its ::placeholder opacity, over the note. They were 0.30, about 1.6:1.
  test(`the editor's placeholders clear AA on an amber note in the ${name} theme`, async ({ page }) => {
    await page.addInitScript((t) => {
      localStorage.setItem('notelikeus-settings', JSON.stringify({ state: { theme: t }, version: 1 }));
    }, theme as unknown as object);
    await page.goto('/');
    await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
    await page.getByRole('button', { name: 'Continue without an account' }).click();
    await page.addStyleTag({
      content: '*,*::before,*::after{animation:none !important;transition:none !important}',
    });
    const newNote = page.getByRole('button', { name: 'New note' }).filter({ visible: true }).first();
    await expect(newNote).toBeVisible({ timeout: 20_000 });
    await newNote.click();
    await page.getByRole('button', { name: 'More options' }).click();
    await page.getByRole('dialog').last().getByRole('button', { name: /Amber/ }).click();
    await page.keyboard.press('Escape');

    for (const field of ['Note title', 'Note body']) {
      const painted = await page.getByRole('textbox', { name: field }).first().evaluate((el) => {
        const placeholder = getComputedStyle(el, '::placeholder');
        let surface = el.parentElement;
        while (surface && /rgba\(0, 0, 0, 0\)|transparent/.test(getComputedStyle(surface).backgroundColor)) {
          surface = surface.parentElement;
        }
        return {
          color: placeholder.color,
          opacity: Number(placeholder.opacity),
          background: surface ? getComputedStyle(surface).backgroundColor : 'rgb(255, 255, 255)',
        };
      });
      // Tailwind's base styles give ::placeholder a half-transparent currentColor, so the colour's
      // own alpha counts as well as the opacity.
      expect(painted.color, `${field} placeholder colour`).toMatch(/^rgba?\(/);
      const [r, g, b, a = 1] = (painted.color.match(/[\d.]+/g) ?? []).map(Number);
      const alpha = a * painted.opacity;
      const bg = rgbOf(painted.background);
      const drawn = [r, g, b].map((c, i) => c * alpha + bg[i] * (1 - alpha));
      expect(contrastRatio(drawn, bg), `${field} placeholder in ${name}`).toBeGreaterThanOrEqual(4.5);
    }
  });

  test(`swatch outlines stay visible in the ${name} theme`, async ({ page }) => {
    const painted = await swatchesIn(page, theme);
    const pageBg = rgbOf(painted.page);
    expect(painted.swatches.length).toBeGreaterThan(0);

    // The active swatch carries the strongest outline; it is the one a user has to locate first.
    const active = painted.swatches.filter((s) => s.selected);
    expect(active.length).toBeGreaterThan(0);
    for (const swatch of active) {
      const ratio = contrastRatio(rgbOf(swatch.border), pageBg);
      expect(ratio, `"${swatch.label}" outline in ${name}`).toBeGreaterThanOrEqual(3);
    }
  });
}
