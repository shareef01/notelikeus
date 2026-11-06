import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import type { Note } from '../src/types/note';

for (const base of ['dark', 'light'] as const) {
  for (const width of [768, 1280, 1920]) {
    test(`neutral editor workspace in ${base} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 960 });
      await page.addInitScript(base => localStorage.setItem('notelikeus-settings', JSON.stringify({ state: { theme: { base, accent: 'neutral', amoled: false } }, version: 1 })), base);
      await page.goto('/');
      await page.getByRole('button', { name: 'Continue without an account' }).click();
      await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible();
      const notes: Note[] = ['A quieter place for your ideas', 'Things to come back to', 'Small moments worth keeping'].map((title, i) => ({
        id: `workspace-${i}`, localId: i + 1, title,
        content: i === 0 ? 'Make room for **clear thinking**.\n\nA comfortable space to write, collect ideas, and find your next thought.\n\n• Keep the important things close\n• Let the rest breathe' : 'A few thoughts, ready when you are.',
        timestamp: 1700000000000, serverUpdatedAt: null, position: i,
        color: i === 0 ? 0xfffff59d | 0 : i === 1 ? 0xffc8e6c9 | 0 : 0,
        isPinned: i === 0, isArchived: false, isTrashed: false, reminderTimestamp: null,
        labels: [], attachments: [], checklist: [],
      }));
      await page.evaluate(notes => {
        (window as unknown as { __NOTELIKEUS_NOTES_STORE__: { getState(): { setNotes(notes: Note[]): void } } }).__NOTELIKEUS_NOTES_STORE__.getState().setNotes(notes);
      }, notes);
      await page.locator('[data-note-open="workspace-0"]').click();
      await page.getByRole('radio', { name: 'Dock note', exact: true }).click();
      const surface = page.locator('[data-editor-surface]');
      await expect(surface).toBeVisible();
      await expect(page.getByRole('textbox', { name: 'Note body' }).locator('strong')).toHaveText('clear thinking');
      const pane = (await surface.boundingBox())!;
      expect(Math.abs(pane.x + pane.width - width)).toBeLessThanOrEqual(1);
      for (const name of ['Set reminder', 'Pin', 'Unpin', 'Archive']) {
        const button = surface.getByRole('button', { name, exact: true });
        if (await button.count()) {
          const box = (await button.boundingBox())!;
          expect(box.x + box.width).toBeLessThanOrEqual(width);
        }
      }
      const toolbar = await surface.getByRole('toolbar', { name: 'Text formatting' }).boundingBox();
      expect(toolbar!.width).toBeLessThan(360);
      const background = await surface.evaluate(element => getComputedStyle(element).backgroundColor);
      expect(background).toBe(base === 'dark' ? 'rgb(27, 27, 27)' : 'rgb(255, 255, 255)');
      await page.addStyleTag({ content: '*,*::before,*::after{animation:none!important;transition:none!important}' });
      const scan = await new AxeBuilder({ page }).include('[data-editor-surface]').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
      expect(scan.violations).toEqual([]);
      if (width === 1920) await page.screenshot({ path: `../scratch/editor-workspace-${base}.png` });
      await page.getByRole('radio', { name: 'Full screen', exact: true }).click();
      await expect(page.locator('[data-editor-surface]')).toHaveCSS('background-color', background);
      await page.getByRole('radio', { name: 'Float note', exact: true }).click();
      await expect(page.locator('[data-editor-surface]')).toHaveCSS('background-color', background);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    });
  }
}
