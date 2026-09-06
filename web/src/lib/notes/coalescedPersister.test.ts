import { describe, expect, it } from 'vitest';
import { createCoalescedPersister } from '@/lib/notes/coalescedPersister';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('createCoalescedPersister', () => {
  it('runs one persist at a time and skips obsolete intermediate snapshots', async () => {
    const started: string[] = [];
    const finished: string[] = [];
    const gate = deferred<void>();
    let releases = 0;

    const persister = createCoalescedPersister<string>(async (snapshot) => {
      started.push(snapshot);
      if (releases === 0) {
        await gate.promise;
      }
      finished.push(snapshot);
      releases += 1;
    });

    const first = persister.request('a');
    const second = persister.request('b');
    const third = persister.request('c');
    gate.resolve();
    await Promise.all([first, second, third]);

    expect(started[0]).toBe('a');
    expect(finished).toContain('c');
    expect(started).not.toContain('b');
  });

  it('does not let an older completion clobber a newer generation', async () => {
    const firstGate = deferred<void>();
    const seen: Array<{ snapshot: string; generation: number }> = [];
    const applied: string[] = [];

    const persister = createCoalescedPersister<string>(async (snapshot, generation) => {
      seen.push({ snapshot, generation });
      if (snapshot === 'old') {
        await firstGate.promise;
      }
      if (generation === persister.generation) {
        applied.push(snapshot);
      }
    });

    const oldRequest = persister.request('old');
    const newRequest = persister.request('new');
    firstGate.resolve();
    await Promise.all([oldRequest, newRequest]);

    expect(applied[applied.length - 1]).toBe('new');
    expect(applied.filter((value) => value === 'old').length).toBeLessThanOrEqual(1);
  });
});
