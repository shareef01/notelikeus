import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createEmptyNote } from '../src/types/note';

for (const theme of ['light', 'dark']) {
  for (const width of [320, 390, 768, 1280, 1920]) {
    test(`readable board spacing in ${theme} at ${width}px`, async ({page}, testInfo) => {
      await page.setViewportSize({width, height:960});
      await page.addInitScript(theme => {
        localStorage.setItem('notelikeus-ui', JSON.stringify({state:{viewColumns:3, sidebarCollapsed:true},version:0}));
        localStorage.setItem('notelikeus-settings', JSON.stringify({state:{theme:{base:theme,accent:'neutral',amoled:false},sortOrder:'oldest'},version:1}));
      }, theme);
      await page.goto('/');
      await page.getByRole('button', {name:'Continue without an account'}).click();
      const now = Date.now();
      const notes = ['Ideas for a quieter workspace', 'A screenshot worth keeping', 'Weekend plans', 'Things to come back to', 'Small moments worth keeping', 'Read this later'].map((title, index) => createEmptyNote({
        id:`density-${index}`, localId:index + 1, title,
        content: 'A little room for clear thoughts. Keep the details readable and easy to find.',
        timestamp: now - (index === 1 ? 2 : 1) * 86400000,
        position:index, isPinned:index === 0,
        color:[0xfffff59d | 0,0xffb2dfdb | 0,0,0xffef9a9a | 0,0,0][index],
      }));
      await page.evaluate(notes => {
        (window as any).__NOTELIKEUS_NOTES_STORE__.getState().setNotes(notes);
      }, notes);
      const card = page.locator('[data-note-id="density-0"]');
      await expect(card).toBeVisible();
      // Select Compact explicitly so this test also works if persisted settings change version.
      if (width >= 768) await page.getByRole('radio', {name:'Compact — more columns',exact:true}).click();
      const compact = (await card.boundingBox())!;
      if (width >= 1280) {
        expect(compact.width).toBeGreaterThanOrEqual(260);
        expect(compact.width).toBeLessThanOrEqual(360);
      }
      const geometry = await card.evaluate(el => {
        const heading = el.querySelector('h2')!;
        const body = el.querySelector('p')!;
        const range = document.createRange();
        range.setStart(heading.firstChild!,0); range.setEnd(heading.firstChild!,1);
        const first = range.getBoundingClientRect();
        const text = body.getBoundingClientRect();
        const time = el.querySelector('time')!.getBoundingClientRect();
        const select = el.querySelector('[role="checkbox"]')!.getBoundingClientRect();
        return {firstX:first.x, bodyX:text.x, bodyBottom:text.bottom, timeY:time.y,
          overlap:Math.max(0, Math.min(first.right,select.right)-Math.max(first.left,select.left))*Math.max(0,Math.min(first.bottom,select.bottom)-Math.max(first.top,select.top))};
      });
      expect(Math.abs(geometry.firstX - geometry.bodyX)).toBeLessThanOrEqual(1);
      expect(geometry.overlap).toBe(0);
      expect(geometry.timeY).toBeGreaterThanOrEqual(geometry.bodyBottom);
      expect(await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(0);
      await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
      expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations).toEqual([]);
      await page.screenshot({path:testInfo.outputPath(`board-${theme}-${width}.png`),fullPage:true});
      if (width >= 1280) {
        for (const name of ['Open settings','New note']) {
          const box = (await page.getByRole('button',{name,exact:true}).first().boundingBox())!;
          expect(box.width).toBeGreaterThanOrEqual(40);
          expect(box.height).toBeGreaterThanOrEqual(40);
        }
        await page.getByRole('radio', {name:'Grid — comfortable cards',exact:true}).click();
        expect((await card.boundingBox())!.width).toBeGreaterThan(compact.width);
        await page.getByRole('radio', {name:'List — one column',exact:true}).click();
        const listAlignment = await card.evaluate(el => {
          const firstX = (selector:string) => {
            const range = document.createRange();
            range.setStart(el.querySelector(selector)!.firstChild!,0);
            range.setEnd(el.querySelector(selector)!.firstChild!,1);
            return range.getBoundingClientRect().x;
          };
          return Math.abs(firstX('h2') - firstX('p'));
        });
        expect(listAlignment).toBeLessThanOrEqual(1);
      }
    });
  }
}

test('portrait and landscape previews retain all image edges', async ({page}) => {
  await page.goto('/');
  await page.getByRole('button',{name:'Continue without an account'}).click();
  const notes = [createEmptyNote({id:'wide',localId:1,title:'Landscape screenshot'}),createEmptyNote({id:'tall',localId:2,title:'Portrait screenshot'})];
  await page.evaluate(async notes => {
    const metrics = (window as any).__NOTELIKEUS_THUMBNAIL_METRICS__;
    for (const [index,note] of notes.entries()) {
      const canvas = document.createElement('canvas');
      canvas.width = index === 0 ? 1200 : 400; canvas.height = index === 0 ? 400 : 1200;
      const context = canvas.getContext('2d')!;
      context.fillStyle = '#ffffff'; context.fillRect(0,0,canvas.width,canvas.height);
      context.fillStyle = '#ff0000'; context.fillRect(0,0,canvas.width,30);
      context.fillStyle = '#0000ff'; context.fillRect(0,canvas.height-30,canvas.width,30);
      const blob = await new Promise<Blob>(resolve => canvas.toBlob(value => resolve(value!), 'image/png'));
      await metrics.storePendingAttachment(note.id,blob,'image/png',note.id);
      note.attachments = [{id:note.id,noteId:index+1,storagePath:`pending:${note.id}`,type:'image',mimeType:'image/png'}];
    }
    (window as any).__NOTELIKEUS_NOTES_STORE__.getState().setNotes(notes);
  }, notes);
  for (const [index,id] of ['wide','tall'].entries()) {
    const image = page.locator(`[data-note-id="${id}"] img`);
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((img:HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
    const pixels = await image.evaluate((img:HTMLImageElement) => {
      const canvas = document.createElement('canvas'); canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;
      const context = canvas.getContext('2d')!;context.drawImage(img,0,0);
      return {ratio:img.naturalWidth/img.naturalHeight, top:[...context.getImageData(Math.floor(canvas.width/2),1,1,1).data],bottom:[...context.getImageData(Math.floor(canvas.width/2),canvas.height-2,1,1).data]};
    });
    expect(pixels.ratio).toBeCloseTo(index === 0 ? 3 : 1/3, 2);
    expect(pixels.top[0]).toBeGreaterThan(200); expect(pixels.top[2]).toBeLessThan(60);
    expect(pixels.bottom[2]).toBeGreaterThan(200); expect(pixels.bottom[0]).toBeLessThan(60);
    await expect(image).toHaveCSS('object-fit','contain');
  }
});
