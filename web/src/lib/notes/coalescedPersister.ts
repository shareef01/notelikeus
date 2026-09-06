/**
 * One persistence pipeline at a time. Edits that arrive mid-save replace the queued
 * snapshot so obsolete intermediates are skipped. The generation counter lets callers
 * ignore completions that belong to an older snapshot.
 */
export function createCoalescedPersister<T>(persist: (snapshot: T, generation: number) => Promise<void>) {
  let inFlight = false;
  let queued: T | undefined;
  let generation = 0;
  let chain: Promise<void> = Promise.resolve();

  const drain = async (): Promise<void> => {
    if (inFlight) return;
    inFlight = true;
    try {
      while (queued !== undefined) {
        const snapshot = queued;
        queued = undefined;
        generation += 1;
        const currentGeneration = generation;
        await persist(snapshot, currentGeneration);
      }
    } finally {
      inFlight = false;
      if (queued !== undefined) {
        await drain();
      }
    }
  };

  return {
    request(snapshot: T): Promise<void> {
      queued = snapshot;
      if (!inFlight) {
        chain = drain();
      }
      return chain;
    },
    get generation() {
      return generation;
    },
    get isBusy() {
      return inFlight || queued !== undefined;
    },
    get hasQueued() {
      return queued !== undefined;
    },
  };
}
