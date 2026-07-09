import { syncNotesWithCloud } from '@/lib/firestore/notesRepository';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';

let mergedForUserId: string | null = null;
let mergeInFlight: Promise<void> | null = null;

function applyNotes(incoming: ReturnType<typeof useNotesStore.getState>['notes']) {
  const current = useNotesStore.getState().notes;
  if (notesContentEqual(current, incoming)) {
    if (useNotesStore.getState().status !== 'ready') {
      useNotesStore.getState().setStatus('ready');
    }
    return;
  }
  useNotesStore.getState().setNotes(incoming);
}

/** One-time cloud merge when the user signs in. No live listener (avoids render storms). */
export function mergeCloudNotesOnce(userId: string): Promise<void> {
  if (mergedForUserId === userId && !mergeInFlight) {
    return Promise.resolve();
  }

  if (mergeInFlight) {
    return mergeInFlight;
  }

  mergeInFlight = (async () => {
    if (mergedForUserId !== userId) {
      useNotesStore.getState().setStatus('loading');
    }

    try {
      const localNotes = useNotesStore.getState().notes;
      const { merged } = await syncNotesWithCloud(userId, localNotes);
      applyNotes(merged);
      mergedForUserId = userId;
    } catch (error) {
      useNotesStore.getState().setError(
        error instanceof Error ? error.message : 'Cloud merge failed',
      );
    } finally {
      mergeInFlight = null;
    }
  })();

  return mergeInFlight;
}

export function resetCloudMergeState() {
  mergedForUserId = null;
  mergeInFlight = null;
}
