import { describe, it, expect } from 'vitest';
import { createEmptyNote, type Note, searchNotes } from '@/types/note';
import { listStoredNotes } from '@/lib/local/notesLocalRepository';
import { sealNoteForStorage } from '@/lib/local/notesSealing';
import { resetNotesDatabaseForTests, withStore } from '@/lib/local/idb';
import { NOTES_STORE } from '@/lib/local/constants';

function generateBenchNotes(count: number): Note[] {
  const notes: Note[] = [];
  const words = ['meeting', 'project', 'grocery', 'travel', 'budget', 'urgent', 'ideas', 'react', 'sync', 'security'];
  for (let i = 0; i < count; i++) {
    const word = words[i % words.length];
    notes.push(
      createEmptyNote({
        id: `bench-note-${i}`,
        localId: i + 1,
        title: `Note ${i} about ${word}`,
        content: `This is benchmark content for note number ${i}. Contains various keywords like ${word} and urgent project alpha-beta-gamma.`,
        timestamp: Date.now() - i * 1000,
        isPinned: i < 5,
        color: (i % 8),
      }),
    );
  }
  return notes;
}

describe('Phase 2 Benchmarks — Performance Baseline', () => {
  it('benchmarks search performance across 100, 1k, 5k, 10k notes', () => {
    const counts = [100, 1000, 5000, 10000];
    const coldResults: Record<number, number> = {};
    const warmResults: Record<number, number> = {};

    for (const count of counts) {
      const dataset = generateBenchNotes(count);
      // Cold query (first time folding 10k note haystacks)
      const coldStart = performance.now();
      const coldResult = searchNotes(dataset, {
        searchQuery: 'urgent project',
        filter: 'active',
        sortOrder: 'manual',
      });
      coldResults[count] = performance.now() - coldStart;
      expect(coldResult.notes.length).toBeGreaterThan(0);

      // Warm query (subsequent keystroke on the same note library, using memoized haystacks)
      const warmStart = performance.now();
      const warmResult = searchNotes(dataset, {
        searchQuery: 'urgent project alpha',
        filter: 'active',
        sortOrder: 'manual',
      });
      warmResults[count] = performance.now() - warmStart;
      expect(warmResult.notes.length).toBeGreaterThan(0);
    }

    console.log('[Benchmark Search Cold (ms)]:', coldResults);
    console.log('[Benchmark Search Warm Keystroke (ms)]:', warmResults);
    // Under V8 code-coverage instrumentation (e.g. `npm run test:coverage` in CI),
    // AST block/branch tracking intercepts every comparison in the 10,000-note relevance loop.
    const isCoverage =
      typeof process !== 'undefined' &&
      (Boolean(process.env.NODE_V8_COVERAGE) ||
        Boolean(process.env.VITEST_COVERAGE) ||
        Boolean(process.env.npm_lifecycle_event?.includes('coverage')));
    const coldLimitMs = isCoverage ? 1000 : 300;
    const warmLimitMs = isCoverage ? 100 : 50;

    expect(coldResults[10000]).toBeLessThan(coldLimitMs);
    expect(warmResults[10000]).toBeLessThan(warmLimitMs); // Target: < 50ms warm search processing on 10k notes!
  });

  it('benchmarks IndexedDB decryption throughput for 100 and 500 notes', async () => {
    await resetNotesDatabaseForTests();
    const ownerId = 'bench-owner';
    const count = 200;
    const testNotes = generateBenchNotes(count);

    // Seed store with sealed records
    for (const note of testNotes) {
      const sealed = await sealNoteForStorage(ownerId, note);
      await withStore(NOTES_STORE, 'readwrite', (store) => {
        store.put({
          ownerId,
          id: note.id,
          note: sealed,
        });
      });
    }

    const start = performance.now();
    const loaded = await listStoredNotes(ownerId);
    const elapsed = performance.now() - start;

    console.log(`[Benchmark listStoredNotes (${count} notes)]: ${elapsed.toFixed(2)} ms`);
    expect(loaded.notes.length).toBe(count);
    expect(loaded.unreadable.length).toBe(0);
  });
});
