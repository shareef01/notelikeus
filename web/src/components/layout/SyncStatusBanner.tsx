import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useSyncQueue } from '@/hooks/useSyncQueue';
import { retryNotesLoad } from '@/lib/notes/retryNotesLoad';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';

function edits(count: number): string {
  return count === 1 ? '1 edit' : `${count} edits`;
}

/**
 * The main screen's caution banner (DESIGN_SYSTEM §5.1): offline, or edits a push left behind.
 *
 * A failed push is otherwise silent — the reconcile around it still succeeds, so the red sync
 * warning never shows — and the edit is the only copy, so the user should know it has not reached
 * their other devices. When that warning is up it already offers Retry, so this stays out of its way.
 */
export function SyncStatusBanner() {
  const online = useOnlineStatus();
  const signedIn = useAuthStore((state) => Boolean(state.user));
  const { pendingIds, flushFailed, isFlushing } = useSyncQueue();
  const syncError = useNotesStore((state) => state.syncError);
  const waiting = pendingIds.length;

  let message: string | null = null;
  let retry = false;
  if (!online) {
    if (!signedIn) message = 'You’re offline. Your notes are on this device.';
    else if (waiting > 0) {
      message = `You’re offline. ${edits(waiting)} saved on this device will sync when you reconnect.`;
    } else message = 'You’re offline. Your notes are still here, and edits will sync when you reconnect.';
  } else if (signedIn && flushFailed && !isFlushing && waiting > 0 && !syncError) {
    message = `${edits(waiting)} couldn’t sync yet. ${waiting === 1 ? 'It’s' : 'They’re'} saved on this device and will be retried.`;
    retry = true;
  }

  if (!message) return null;

  return (
    <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b border-amber-700/30 bg-amber-500/10 px-shell py-2 text-center text-sm text-amber-900 dark:border-amber-900/40 dark:bg-amber-950/30 dark:text-amber-200">
      <p role="status">{message}</p>
      {retry ? (
        <button
          type="button"
          onClick={() => void retryNotesLoad()}
          className="inline-flex min-h-10 items-center rounded-full px-3 font-semibold underline underline-offset-2 hover:opacity-80 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current"
        >
          Retry
        </button>
      ) : null}
    </div>
  );
}
