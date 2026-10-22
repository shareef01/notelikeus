import { expect, test, type Page } from '@playwright/test';

/**
 * Regression suite: Progressive grid scroll stability under updates.
 *
 * Verifies that:
 * 1. An ordinary update (content edit, checkbox toggle, pinning, or background sync)
 *    never collapses progressive rendering from >300 cards back to 80 cards.
 * 2. Scroll position and the note currently in viewport remain rock-solid.
 * 3. Benchmarks DOM size after traversing a massive 10,000-note library.
 */

async function enterGuestWithNotes(page: Page, count: number): Promise<void> {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({
    timeout: 20_000,
  });

  // Populate synthetic notes in store via test hook
  await page.evaluate((total) => {
    const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
    if (!store) throw new Error('Store hook not available in e2e mode');
    const notes = Array.from({ length: total }, (_, i) => ({
      id: `perf-note-${i}`,
      localId: i + 1,
      title: `Performance Note ${i}`,
      content: `Body text for note ${i} with #tag and details`,
      color: 0,
      timestamp: 1_700_000_000_000 + i * 1000,
      isPinned: false,
      isArchived: false,
      isTrashed: false,
      position: i,
      reminderTimestamp: null,
      serverUpdatedAt: null,
      labels: [],
      attachments: [],
      checklist:
        i % 5 === 0
          ? [
              { id: `c-${i}-1`, text: 'Checklist task item 1', isChecked: false, position: 0 },
              { id: `c-${i}-2`, text: 'Checklist task item 2', isChecked: true, position: 1 },
            ]
          : [],
    }));
    store.getState().setNotes(notes);
  }, count);

  // Wait for initial render of cards
  await expect(page.locator('[data-note-open]').first()).toBeVisible({ timeout: 15_000 });
}

test.describe('Progressive Grid Scroll Stability', () => {
  test('scroll position and rendered card count remain stable across sync, edits, pinning, and checkboxes (>1,000 notes)', async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await enterGuestWithNotes(page, 1000);

    // Initial render should be bounded to 80 cards
    const initialCount = await page.locator('[data-note-open]').count();
    expect(initialCount).toBeLessThanOrEqual(80);

    // Scroll down until more than 300 cards are mounted in the DOM
    let mountedCards = initialCount;
    let scrollAttempts = 0;
    while (mountedCards <= 300 && scrollAttempts < 25) {
      scrollAttempts += 1;
      await page.evaluate(() => window.scrollBy(0, 1500));
      await page.waitForTimeout(150);
      mountedCards = await page.locator('[data-note-open]').count();
    }

    expect(mountedCards).toBeGreaterThan(300);

    // Capture initial scroll position and anchor note near the middle of viewport
    const beforeScrollY = await page.evaluate(() => window.scrollY);
    expect(beforeScrollY).toBeGreaterThan(500);

    // Identify a visible note card currently near viewport center
    const anchor = await page.evaluate(() => {
      const centerY = window.innerHeight / 2;
      const cards = Array.from(document.querySelectorAll<HTMLElement>('[data-note-open]'));
      const found = cards.find((card) => {
        const rect = card.getBoundingClientRect();
        return rect.top <= centerY && rect.bottom >= centerY;
      });
      return found ? { id: found.dataset.noteOpen, top: found.getBoundingClientRect().top } : null;
    });

    expect(anchor).not.toBeNull();
    const anchorId = anchor!.id;

    // 1. Simulate an incoming background synchronization update (modifying a note offscreen)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 10 ? { ...n, title: 'Sync Updated Note 10' } : n,
      );
      store.getState().setNotes(updated);
    });

    await page.waitForTimeout(200);

    // Verify card count DID NOT collapse to 80
    const countAfterSync = await page.locator('[data-note-open]').count();
    expect(countAfterSync).toBeGreaterThan(300);
    expect(Math.abs(countAfterSync - mountedCards)).toBeLessThanOrEqual(5);

    // Verify scroll position is stable
    const scrollAfterSync = await page.evaluate(() => window.scrollY);
    expect(Math.abs(scrollAfterSync - beforeScrollY)).toBeLessThanOrEqual(5);

    // Verify anchor note is still visible at stable viewport offset
    const anchorAfterSync = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      return el ? el.getBoundingClientRect().top : null;
    }, anchorId);
    expect(anchorAfterSync).not.toBeNull();
    expect(Math.abs(anchorAfterSync! - anchor!.top)).toBeLessThanOrEqual(5);

    // 2. Repeat for note content edit
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 25 ? { ...n, content: 'User edited content directly' } : n,
      );
      store.getState().setNotes(updated);
    });

    await page.waitForTimeout(200);
    const countAfterEdit = await page.locator('[data-note-open]').count();
    expect(countAfterEdit).toBeGreaterThan(300);
    expect(Math.abs(countAfterEdit - mountedCards)).toBeLessThanOrEqual(5);
    const scrollAfterEdit = await page.evaluate(() => window.scrollY);
    expect(Math.abs(scrollAfterEdit - beforeScrollY)).toBeLessThanOrEqual(5);

    // 3. Repeat for checklist checkbox update
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 50 && n.checklist.length > 0
          ? {
              ...n,
              checklist: n.checklist.map((c: any, cIdx: number) =>
                cIdx === 0 ? { ...c, isChecked: true } : c,
              ),
            }
          : n,
      );
      store.getState().setNotes(updated);
    });

    await page.waitForTimeout(200);
    const countAfterChecklist = await page.locator('[data-note-open]').count();
    expect(countAfterChecklist).toBeGreaterThan(300);
    expect(Math.abs(countAfterChecklist - mountedCards)).toBeLessThanOrEqual(5);
    const scrollAfterChecklist = await page.evaluate(() => window.scrollY);
    expect(Math.abs(scrollAfterChecklist - beforeScrollY)).toBeLessThanOrEqual(5);

    // 4. Repeat for note pinning
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 1 ? { ...n, isPinned: true } : n,
      );
      store.getState().setNotes(updated);
    });

    await page.waitForTimeout(200);
    const countAfterPin = await page.locator('[data-note-open]').count();
    expect(countAfterPin).toBeGreaterThan(300);
    expect(Math.abs(countAfterPin - mountedCards)).toBeLessThanOrEqual(5);
    const scrollAfterPin = await page.evaluate(() => window.scrollY);
    expect(Math.abs(scrollAfterPin - beforeScrollY)).toBeLessThanOrEqual(15);
  });

  test('benchmark: DOM size and traversal footprint with 10,000 notes', async ({ page }) => {
    test.setTimeout(120_000);
    await enterGuestWithNotes(page, 10_000);

    const initialDomNodes = await page.evaluate(() => document.querySelectorAll('*').length);
    const initialCards = await page.locator('[data-note-open]').count();

    expect(initialCards).toBeLessThanOrEqual(80);
    expect(initialDomNodes).toBeLessThan(2_500);

    // Traverse down through 20 scroll bursts
    for (let burst = 0; burst < 20; burst += 1) {
      await page.evaluate(() => window.scrollBy(0, 2000));
      await page.waitForTimeout(100);
    }

    const traversedCards = await page.locator('[data-note-open]').count();
    const traversedDomNodes = await page.evaluate(() => document.querySelectorAll('*').length);

    // Progressive rendering incrementally expands chunks while content-visibility: auto
    // avoids layout/paint overhead for offscreen cards.
    expect(traversedCards).toBeGreaterThan(500);
    expect(traversedDomNodes).toBeGreaterThan(initialDomNodes);

    console.log(
      `[Benchmark 10,000 Notes] Initial Cards: ${initialCards}, Initial DOM Nodes: ${initialDomNodes} | Traversed Cards: ${traversedCards}, Traversed DOM Nodes: ${traversedDomNodes}`,
    );
  });
});
