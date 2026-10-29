import { expect, test, type Page } from '@playwright/test';

// In scalability & stress suites with thousands of DOM nodes, tracing captures millions
// of DOM mutation records that exhaust Node/CDP memory buffers and lock up teardown serialization.
test.use({ trace: 'off' });

/**
 * Scalability & Regression Suite:
 * 1. Progressive grid scroll stability across ordinary updates (>1,000 notes).
 * 2. Structural scroll regression tests above viewport: insertion, deletion, archiving, pinning, reordering.
 * 3. Offscreen thumbnail retention and stabilization across 1,000 image notes.
 * 4. Full 10,000-note traversal to final note, measuring DOM, heap, latency, and return-to-top memory.
 */

async function enterGuestWithNotes(
  page: Page,
  count: number,
  options: { withImages?: boolean } = {},
): Promise<void> {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  await expect(page.locator('#boot-splash')).toHaveCount(0, { timeout: 30_000 });
  await page.getByRole('button', { name: 'Continue without an account' }).click();
  await expect(page.getByRole('button', { name: 'New note' }).first()).toBeVisible({
    timeout: 20_000,
  });

  // Populate synthetic notes in store via test hook
  await page.evaluate(
    async ({ total, withImages }) => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const metrics = (window as any).__NOTELIKEUS_THUMBNAIL_METRICS__;
      if (!store) throw new Error('Store hook not available in e2e mode');

      // Create a 1x1 transparent PNG blob for synthetic image attachments
      let sampleBlob: Blob | null = null;
      if (withImages) {
        const bin = atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==');
        const bytes = new Uint8Array(bin.length);
        for (let j = 0; j < bin.length; j += 1) bytes[j] = bin.charCodeAt(j);
        sampleBlob = new Blob([bytes], { type: 'image/png' });
      }

      const notes = [];
      for (let i = 0; i < total; i += 1) {
        const hasImage = withImages;
        const attId = `img-att-${i}`;
        if (hasImage && sampleBlob && metrics?.storePendingAttachment) {
          await metrics.storePendingAttachment(attId, sampleBlob, 'image/png', `perf-note-${i}`);
        }

        notes.push({
          id: `perf-note-${i}`,
          localId: i + 1,
          title: `Performance Note ${i}`,
          content: `Body text for note ${i} with #tag and details`,
          color: 0,
          timestamp: 1_700_000_000_000 - i * 1000,
          isPinned: false,
          isArchived: false,
          isTrashed: false,
          position: i,
          reminderTimestamp: null,
          serverUpdatedAt: null,
          labels: [],
          attachments: hasImage
            ? [
                {
                  id: attId,
                  noteId: i + 1,
                  storagePath: `pending:${attId}`,
                  type: 'image' as const,
                  mimeType: 'image/png',
                },
              ]
            : [],
          checklist:
            i % 5 === 0
              ? [
                  { id: `c-${i}-1`, text: 'Checklist task item 1', isChecked: false, position: 0 },
                  { id: `c-${i}-2`, text: 'Checklist task item 2', isChecked: true, position: 1 },
                ]
              : [],
        });
      }

      store.getState().setNotes(notes);
    },
    { total: count, withImages: Boolean(options.withImages) },
  );

  // Wait for initial render of cards
  await expect(page.locator('[data-note-open]').first()).toBeVisible({ timeout: 15_000 });
}

test.describe('Scalability & Progressive Grid Validation', () => {
  test('scroll position and card count stability across sync, edits, pinning, and checkboxes (>1,000 notes)', async ({
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

    const countAfterSync = await page.locator('[data-note-open]').count();
    expect(countAfterSync).toBeGreaterThan(300);
    expect(Math.abs(countAfterSync - mountedCards)).toBeLessThanOrEqual(5);

    const scrollAfterSync = await page.evaluate(() => window.scrollY);
    expect(Math.abs(scrollAfterSync - beforeScrollY)).toBeLessThanOrEqual(5);

    const anchorAfterSync = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      return el ? el.getBoundingClientRect().top : null;
    }, anchorId);
    expect(anchorAfterSync).not.toBeNull();
    expect(Math.abs(anchorAfterSync! - anchor!.top)).toBeLessThanOrEqual(5);

    // 2. Note content edit
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

    // 3. Checklist checkbox toggle
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

    // 4. Pinning a note
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
    // Pinning adds a "Pinned" group above the reader. What must not move is the card they are
    // looking at; scrollY may change so that it doesn't. (Measured: with the sidebar static,
    // scrollY held still and the card jumped 32 px; with the sticky sidebar, the browser's scroll
    // anchoring keeps the card in place and moves scrollY by the group's height.)
    const anchorAfterPin = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      return el ? el.getBoundingClientRect().top : null;
    }, anchorId);
    expect(anchorAfterPin).not.toBeNull();
    expect(Math.abs(anchorAfterPin! - anchor!.top)).toBeLessThanOrEqual(5);
  });

  test('structural scroll regression tests: insertion, deletion, archiving, pinning, and reordering above viewport', async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await enterGuestWithNotes(page, 1000);

    // Scroll down until 300+ cards are mounted
    let mountedCards = await page.locator('[data-note-open]').count();
    let scrollAttempts = 0;
    while (mountedCards <= 300 && scrollAttempts < 25) {
      scrollAttempts += 1;
      await page.evaluate(() => window.scrollBy(0, 1500));
      await page.waitForTimeout(150);
      mountedCards = await page.locator('[data-note-open]').count();
    }
    expect(mountedCards).toBeGreaterThan(300);

    // Pick an anchor note near middle of viewport (e.g. index ~150)
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

    // A. Insertion ABOVE current viewport (at index 0)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const insertedNote = {
        id: 'perf-note-inserted-top',
        localId: 99999,
        title: 'Inserted Note At Top',
        content: 'Inserted content above viewport',
        color: 0,
        timestamp: Date.now(),
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        position: -1,
        reminderTimestamp: null,
        serverUpdatedAt: null,
        labels: [],
        attachments: [],
        checklist: [],
      };
      store.getState().setNotes([insertedNote, ...current]);
    });
    await page.waitForTimeout(200);

    // Verify anchor note is still rendered and visible
    const anchorAfterInsert = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { top: rect.top, visible: rect.bottom > 0 && rect.top < window.innerHeight };
    }, anchorId);
    expect(anchorAfterInsert).not.toBeNull();
    expect(anchorAfterInsert!.visible).toBe(true);

    // B. Deletion ABOVE current viewport (delete note at index 5)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const filtered = current.filter((_: any, idx: number) => idx !== 5);
      store.getState().setNotes(filtered);
    });
    await page.waitForTimeout(200);

    const anchorAfterDelete = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { top: rect.top, visible: rect.bottom > 0 && rect.top < window.innerHeight };
    }, anchorId);
    expect(anchorAfterDelete).not.toBeNull();
    expect(anchorAfterDelete!.visible).toBe(true);

    // C. Archiving ABOVE current viewport (archive note at index 10)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 10 ? { ...n, isArchived: true } : n,
      );
      store.getState().setNotes(updated);
    });
    await page.waitForTimeout(200);

    const anchorAfterArchive = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { top: rect.top, visible: rect.bottom > 0 && rect.top < window.innerHeight };
    }, anchorId);
    expect(anchorAfterArchive).not.toBeNull();
    expect(anchorAfterArchive!.visible).toBe(true);

    // D. Pinning note ABOVE viewport (pin note at index 8)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = store.getState().notes;
      const updated = current.map((n: any, idx: number) =>
        idx === 8 ? { ...n, isPinned: true } : n,
      );
      store.getState().setNotes(updated);
    });
    await page.waitForTimeout(200);

    const anchorAfterPin = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { top: rect.top, visible: rect.bottom > 0 && rect.top < window.innerHeight };
    }, anchorId);
    expect(anchorAfterPin).not.toBeNull();
    expect(anchorAfterPin!.visible).toBe(true);

    // E. Reordering notes ABOVE viewport (swap index 2 and index 3)
    await page.evaluate(() => {
      const store = (window as any).__NOTELIKEUS_NOTES_STORE__;
      const current = [...store.getState().notes];
      const temp = current[2];
      current[2] = current[3];
      current[3] = temp;
      store.getState().setNotes(current);
    });
    await page.waitForTimeout(200);

    const anchorAfterReorder = await page.evaluate((id) => {
      const el = document.querySelector<HTMLElement>(`[data-note-open="${id}"]`);
      if (!el) return null;
      const rect = el.getBoundingClientRect();
      return { top: rect.top, visible: rect.bottom > 0 && rect.top < window.innerHeight };
    }, anchorId);
    expect(anchorAfterReorder).not.toBeNull();
    expect(anchorAfterReorder!.visible).toBe(true);
  });

  test('offscreen thumbnail retention: resource usage stabilizes across 1,000 image notes', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await enterGuestWithNotes(page, 1000, { withImages: true });

    // Wait for initial image cards to settle
    await page.waitForTimeout(500);

    const initialMetrics = await page.evaluate(() => {
      const m = (window as any).__NOTELIKEUS_THUMBNAIL_METRICS__;
      return {
        cachedUrls: m?.getCachedThumbnailCount() ?? 0,
        activeRefs: m?.getTotalActiveThumbnailRefCount() ?? 0,
        domNodes: document.querySelectorAll('*').length,
      };
    });

    console.log(
      `[Thumbnail Init] Cached URLs: ${initialMetrics.cachedUrls}, Active Refs: ${initialMetrics.activeRefs}, DOM Nodes: ${initialMetrics.domNodes}`,
    );

    // Progressively scroll through 500+ cards
    let mountedCards = await page.locator('[data-note-open]').count();
    let scrollStep = 0;
    while (mountedCards < 500 && scrollStep < 35) {
      scrollStep += 1;
      await page.evaluate(() => window.scrollBy(0, 1800));
      await page.waitForTimeout(100);
      mountedCards = await page.locator('[data-note-open]').count();
    }

    expect(mountedCards).toBeGreaterThanOrEqual(500);

    // Measure metrics at depth of 500 image cards
    const deepMetrics = await page.evaluate(() => {
      const m = (window as any).__NOTELIKEUS_THUMBNAIL_METRICS__;
      const perfMemory = (performance as any).memory;
      return {
        cachedUrls: m?.getCachedThumbnailCount() ?? 0,
        activeRefs: m?.getTotalActiveThumbnailRefCount() ?? 0,
        domNodes: document.querySelectorAll('*').length,
        usedJsHeapMb: perfMemory ? Math.round(perfMemory.usedJSHeapSize / 1024 / 1024) : null,
      };
    });

    console.log(
      `[Thumbnail At 500 Cards] Cached URLs: ${deepMetrics.cachedUrls}, Active Refs: ${deepMetrics.activeRefs}, DOM Nodes: ${deepMetrics.domNodes}, Heap: ${deepMetrics.usedJsHeapMb}MB`,
    );

    // CRITICAL: Even though 500+ cards are mounted, active references MUST be bounded
    // to only cards near the viewport (via hysteresis observer), NOT all 500!
    expect(deepMetrics.activeRefs).toBeLessThanOrEqual(120);

    // CRITICAL: Total cached Object URLs in memory MUST NOT grow unbounded
    // Capped by LRU cache at MAX_CACHED_THUMBNAILS (128)
    expect(deepMetrics.cachedUrls).toBeLessThanOrEqual(128);

    // Continue scrolling down another 200 cards to verify stabilization
    for (let extra = 0; extra < 15; extra += 1) {
      await page.evaluate(() => window.scrollBy(0, 2000));
      await page.waitForTimeout(80);
    }

    const stabilizedMetrics = await page.evaluate(() => {
      const m = (window as any).__NOTELIKEUS_THUMBNAIL_METRICS__;
      return {
        cachedUrls: m?.getCachedThumbnailCount() ?? 0,
        activeRefs: m?.getTotalActiveThumbnailRefCount() ?? 0,
      };
    });

    console.log(
      `[Thumbnail Stabilized After 700+ Cards] Cached URLs: ${stabilizedMetrics.cachedUrls}, Active Refs: ${stabilizedMetrics.activeRefs}`,
    );

    // Resource usage stays flat and stabilized
    expect(stabilizedMetrics.cachedUrls).toBeLessThanOrEqual(128);
    expect(stabilizedMetrics.activeRefs).toBeLessThanOrEqual(120);
  });

  test('complete 10,000-note traversal to final note (perf-note-9999)', async ({ page }) => {
      test.setTimeout(420_000);
      await enterGuestWithNotes(page, 10_000);

      const initialStats = await page.evaluate(() => {
        const perfMemory = (performance as any).memory;
        return {
          cards: document.querySelectorAll('[data-note-open]').length,
          domNodes: document.querySelectorAll('*').length,
          heapMb: perfMemory ? Math.round(perfMemory.usedJSHeapSize / 1024 / 1024) : null,
        };
      });

      expect(initialStats.cards).toBeLessThanOrEqual(80);

      // Execute full 10k traversal and measure peak & return-to-top metrics in a single in-page session
      // to avoid CDP IPC overhead and headless rAF throttling over a 10,000-node DOM.
      const benchmarkResult = await page.evaluate(async () => {
        const t0 = performance.now();
        await new Promise<void>((resolve, reject) => {
          const timeout = setTimeout(() => {
            const count = document.querySelectorAll('[data-note-open]').length;
            reject(new Error(`10k traversal timed out after 300s with ${count} cards mounted`));
          }, 300_000);

          function tick() {
            // Fast O(1) check for the final card
            const finalCard = document.querySelector('[data-note-open="perf-note-9999"]');
            if (finalCard) {
              clearTimeout(timeout);
              resolve();
              return;
            }

            const sentinel = document.querySelector('[data-sentinel="notes-grid"]');
            if (sentinel) {
              sentinel.scrollIntoView({ block: 'end' });
            } else {
              window.scrollTo(0, document.documentElement.scrollHeight);
            }

            requestAnimationFrame(tick);
          }

          tick();
        });

        const traversalDurationMs = Math.round(performance.now() - t0);

        // 1. Verify final card is rendered in the DOM and active in layout
        const finalCard = document.querySelector<HTMLElement>('[data-note-open="perf-note-9999"]');
        let finalNoteRendered = false;
        if (finalCard) {
          finalCard.scrollIntoView({ block: 'nearest' });
          const rect = finalCard.getBoundingClientRect();
          const style = window.getComputedStyle(finalCard);
          finalNoteRendered = rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
        }

        // 2. Measure peak stats at 10,000 notes
        const perfMemory = (performance as any).memory;
        const peakStats = {
          cards: document.querySelectorAll('[data-note-open]').length,
          domNodes: document.querySelectorAll('*').length,
          heapMb: perfMemory ? Math.round(perfMemory.usedJSHeapSize / 1024 / 1024) : null,
        };

        // 3. Measure synchronous interaction latency / focus responsiveness at 10,000 cards
        const tFocus = performance.now();
        finalCard?.focus();
        const interactionLatencyMs = Math.round(performance.now() - tFocus);

        // 4. Return to top and measure reset stats
        window.scrollTo(0, 0);
        const returnTopStats = {
          scrollY: window.scrollY,
          cards: document.querySelectorAll('[data-note-open]').length,
          domNodes: document.querySelectorAll('*').length,
          heapMb: perfMemory ? Math.round(perfMemory.usedJSHeapSize / 1024 / 1024) : null,
        };

        return {
          traversalDurationMs,
          finalNoteRendered,
          peakStats,
          interactionLatencyMs,
          returnTopStats,
        };
      });

      expect(benchmarkResult.finalNoteRendered).toBe(true);
      expect(benchmarkResult.peakStats.cards).toBe(10_000);
      expect(benchmarkResult.returnTopStats.scrollY).toBe(0);

      console.log(
        `[10,000 Traversal Peak] Cards: ${benchmarkResult.peakStats.cards}, DOM Nodes: ${benchmarkResult.peakStats.domNodes}, Heap: ${benchmarkResult.peakStats.heapMb}MB, Traversal Time: ${benchmarkResult.traversalDurationMs}ms, Focus Latency: ${benchmarkResult.interactionLatencyMs}ms`,
      );
      console.log(
        `[10,000 Return to Top] ScrollY: ${benchmarkResult.returnTopStats.scrollY}, Cards: ${benchmarkResult.returnTopStats.cards}, DOM Nodes: ${benchmarkResult.returnTopStats.domNodes}, Heap: ${benchmarkResult.returnTopStats.heapMb}MB`,
      );

      // Clean up DOM before page fixture teardown so CDP does not serialize 228k DOM nodes
      await page.goto('about:blank');
    });
});
