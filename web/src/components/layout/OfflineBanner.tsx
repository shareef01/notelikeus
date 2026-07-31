import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { isFirestoreMemoryCache } from '@/lib/firebase';

export function OfflineBanner() {
  const online = useOnlineStatus();
  const memoryOnly = isFirestoreMemoryCache();

  if (online && !memoryOnly) return null;

  const message = !online
    ? memoryOnly
      ? "You're offline and this browser is using temporary storage only. Edits may be lost if you close the tab before reconnecting."
      : "You're offline — your notes are still here, and any edits sync automatically once you reconnect."
    : "This browser is using temporary storage only. Offline changes may not persist after you close the tab.";

  return (
    <div
      className="border-b border-amber-900/40 bg-amber-950/30 px-4 py-2 text-center text-sm text-amber-200"
      role="status"
    >
      {message}
    </div>
  );
}
