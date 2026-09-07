import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getNotesDatabase, resetNotesDatabaseForTests } from '@/lib/local/idb';

/**
 * The database connection has to be recoverable.
 *
 * A rejected open used to be cached like a successful one, so a single transient failure — a
 * blocked upgrade from another tab, storage briefly unavailable — left every later call
 * rejecting with the same error until the page was reloaded. In a local-first app that is
 * indefinite data loss: every save fails while the editor holds the only copy.
 */
describe('IndexedDB open recovery', () => {
  const realOpen = indexedDB.open.bind(indexedDB);

  beforeEach(async () => {
    await resetNotesDatabaseForTests();
  });

  afterEach(async () => {
    vi.restoreAllMocks();
    indexedDB.open = realOpen;
    await resetNotesDatabaseForTests();
  });

  /** Makes the next `open` fail, the way a blocked or erroring open does. */
  function failNextOpen() {
    indexedDB.open = ((..._args: unknown[]) => {
      const request: Record<string, unknown> = {
        result: undefined,
        error: new Error('injected open failure'),
        onsuccess: null,
        onerror: null,
        onblocked: null,
        onupgradeneeded: null,
      };
      queueMicrotask(() => {
        indexedDB.open = realOpen;
        (request.onerror as (() => void) | null)?.();
      });
      return request as unknown as IDBOpenDBRequest;
    }) as typeof indexedDB.open;
  }

  it('retries after a failed open instead of caching the rejection forever', async () => {
    failNextOpen();
    await expect(getNotesDatabase()).rejects.toThrow();

    // The regression: this used to reject with the cached failure rather than opening.
    const db = await getNotesDatabase();
    expect(db.name).toBeDefined();
  });

  it('still opens only once for concurrent callers', async () => {
    const spy = vi.spyOn(indexedDB, 'open');

    const [a, b, c] = await Promise.all([
      getNotesDatabase(),
      getNotesDatabase(),
      getNotesDatabase(),
    ]);

    // Deduplication must survive the retry handling, or every caller races its own upgrade.
    expect(spy).toHaveBeenCalledTimes(1);
    expect(a).toBe(b);
    expect(b).toBe(c);
  });

  it('reopens after the connection is closed underneath it', async () => {
    const db = await getNotesDatabase();
    // Stands in for another tab triggering a version change, which forces this one closed.
    db.onversionchange?.(new Event('versionchange') as IDBVersionChangeEvent);

    const reopened = await getNotesDatabase();
    expect(reopened).not.toBe(db);
  });

  it('recovers a second time, so the retry path is not one-shot', async () => {
    failNextOpen();
    await expect(getNotesDatabase()).rejects.toThrow();
    await expect(getNotesDatabase()).resolves.toBeDefined();

    await resetNotesDatabaseForTests();
    failNextOpen();
    await expect(getNotesDatabase()).rejects.toThrow();
    await expect(getNotesDatabase()).resolves.toBeDefined();
  });
});
