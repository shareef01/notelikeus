import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { isFirestoreMemoryCache } from '@/lib/firebase';

export function OfflineBanner() {
  const online = useOnlineStatus();
  if (online) return null;

  const memoryOnly = isFirestoreMemoryCache();

  return (
    <div
      className="border-b border-amber-900/40 bg-amber-950/30 px-4 py-2 text-center text-sm text-amber-200"
      role="status"
    >
      {memoryOnly
        ? "You're offline — edits stay in this tab only until you reconnect (this browser blocked lasting offline storage)."
        : "You're offline — your notes are still here, and any edits sync automatically once you reconnect."}
    </div>
  );
}
