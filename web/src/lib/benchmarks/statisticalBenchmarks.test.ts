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

function stats(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b);
  const min = sorted[0];
  const max = sorted[sorted.length - 1];
  const sum = sorted.reduce((acc, v) => acc + v, 0);
  const mean = sum / sorted.length;
  const mid = Math.floor(sorted.length / 2);
  const median = sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  const variance = sorted.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / sorted.length;
  const stdDev = Math.sqrt(variance);
  return { min, max, mean, median, stdDev };
}

describe('Statistical Performance Benchmarking (5 Repeat Runs)', () => {
  it('measures search performance statistics across 100, 1k, 5k, 10k notes', () => {
    const RUNS = 5;
    const counts = [100, 1000, 5000, 10000];
    const coldRuns: Record<number, number[]> = { 100: [], 1000: [], 5000: [], 10000: [] };
    const warmRuns: Record<number, number[]> = { 100: [], 1000: [], 5000: [], 10000: [] };

    for (let r = 1; r <= RUNS; r++) {
      for (const count of counts) {
        const dataset = generateBenchNotes(count);

        // Cold query
        const coldStart = performance.now();
        searchNotes(dataset, {
          searchQuery: 'urgent project',
          filter: 'active',
          sortOrder: 'manual',
        });
        const coldElapsed = performance.now() - coldStart;
        coldRuns[count].push(coldElapsed);

        // Warm query
        const warmStart = performance.now();
        searchNotes(dataset, {
          searchQuery: 'urgent project alpha',
          filter: 'active',
          sortOrder: 'manual',
        });
        const warmElapsed = performance.now() - warmStart;
        warmRuns[count].push(warmElapsed);
      }
    }

    console.log('\n================ SEARCH BENCHMARK (5 RUNS) ================');
    for (const count of counts) {
      const c = stats(coldRuns[count]);
      const w = stats(warmRuns[count]);
      console.log(`[${count} notes] Cold: median=${c.median.toFixed(2)}ms (min=${c.min.toFixed(2)}, max=${c.max.toFixed(2)}, stdDev=±${c.stdDev.toFixed(2)}) | runs: [${coldRuns[count].map(v => v.toFixed(2)).join(', ')}]`);
      console.log(`[${count} notes] Warm: median=${w.median.toFixed(2)}ms (min=${w.min.toFixed(2)}, max=${w.max.toFixed(2)}, stdDev=±${w.stdDev.toFixed(2)}) | runs: [${warmRuns[count].map(v => v.toFixed(2)).join(', ')}]`);
    }
  });

  it('measures IndexedDB decryption throughput statistics (5 Repeat Runs)', async () => {
    const RUNS = 5;
    const count = 200;
    const ownerId = 'stat-bench-owner';
    const runs: number[] = [];

    for (let r = 1; r <= RUNS; r++) {
      await resetNotesDatabaseForTests();
      const testNotes = generateBenchNotes(count);

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
      runs.push(elapsed);
      expect(loaded.notes.length).toBe(count);
    }

    const s = stats(runs);
    console.log('\n================ DECRYPTION THROUGHPUT (5 RUNS) ================');
    console.log(`[${count} notes] Decryption: median=${s.median.toFixed(2)}ms (min=${s.min.toFixed(2)}, max=${s.max.toFixed(2)}, stdDev=±${s.stdDev.toFixed(2)}) | runs: [${runs.map(v => v.toFixed(2)).join(', ')}]`);
    console.log(`Equivalent throughput: ${(count / (s.median / 1000)).toFixed(0)} notes/second`);
  });
});
