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
    const results: Record<number, number> = {};

    for (const count of counts) {
      const dataset = generateBenchNotes(count);
      const start = performance.now();
      const result = searchNotes(dataset, {
        searchQuery: 'urgent project',
        filter: 'active',
        sortOrder: 'manual',
      });
      const elapsed = performance.now() - start;
      results[count] = elapsed;
      expect(result.notes.length).toBeGreaterThan(0);
    }

    console.log('[Benchmark Search Results (ms)]:', results);
    expect(results[10000]).toBeLessThan(300); // Target: < 300ms baseline in test runner
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
