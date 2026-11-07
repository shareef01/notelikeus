import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import type { Note } from '../src/types/note';

for (const base of ['dark', 'light'] as const) {
  for (const width of [768, 1280, 1920]) {
    test(`neutral editor workspace in ${base} at ${width}px`, async ({ page }, testInfo) => {
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
      await page.evaluate(async notes => {
        const canvas = document.createElement('canvas');
        canvas.width = 420;
        canvas.height = 145;
        const context = canvas.getContext('2d')!;
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#222';
        context.font = '24px sans-serif';
        context.fillText('A note worth keeping', 24, 80);
        const blob = await new Promise<Blob>(resolve => canvas.toBlob(value => resolve(value!), 'image/png'));
        const metrics = (window as unknown as { __NOTELIKEUS_THUMBNAIL_METRICS__: {
          storePendingAttachment(id: string, blob: Blob, mime: string, noteId: string): Promise<void>;
        } }).__NOTELIKEUS_THUMBNAIL_METRICS__;
        await metrics.storePendingAttachment('workspace-image', blob, 'image/png', notes[0]!.id);
        notes[0]!.attachments = [{ id: 'workspace-image', noteId: 1, storagePath: 'pending:workspace-image', type: 'image', mimeType: 'image/png' }];
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
      const checkAlignment = async (layout: 'dock' | 'fullscreen' | 'float') => {
        const title = (await surface.getByRole('textbox', { name: 'Note title' }).boundingBox())!;
        const back = (await surface.getByRole('button', { name: 'Back', exact: true }).boundingBox())!;
        const footer = (await surface.locator('footer').boundingBox())!;
        const body = (await surface.getByRole('textbox', { name: 'Note body' }).boundingBox())!;
        const image = surface.getByRole('img', { name: 'Note attachment' });
        await expect(image).toBeVisible();
        const picture = (await image.boundingBox())!;
        expect(Math.abs(title.x - back.x)).toBeLessThanOrEqual(16);
        expect(Math.abs(title.x - footer.x)).toBeLessThanOrEqual(16);
        expect(picture.y).toBeGreaterThanOrEqual(body.y + body.height);
        if (width >= 1280) expect(picture.y - body.y).toBeLessThan(320);
        expect(picture.width / picture.height).toBeCloseTo(420 / 145, 1);
        expect(picture.width).toBeLessThanOrEqual(421);
        expect(picture.width).toBeLessThanOrEqual(title.width);
        if (layout === 'dock') expect(title.x - pane.x).toBeLessThanOrEqual(48);
        if (width === 1920) await page.screenshot({ path: testInfo.outputPath(`${base}-${layout}.png`) });
      };
      await checkAlignment('dock');
      await page.getByRole('radio', { name: 'Full screen', exact: true }).click();
      await expect(page.locator('[data-editor-surface]')).toHaveCSS('background-color', background);
      await checkAlignment('fullscreen');
      await page.getByRole('radio', { name: 'Float note', exact: true }).click();
      await expect(page.locator('[data-editor-surface]')).toHaveCSS('background-color', background);
      await checkAlignment('float');
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
    });
  }
}
