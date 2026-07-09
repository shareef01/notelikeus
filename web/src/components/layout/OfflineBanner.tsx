import { useOnlineStatus } from '@/hooks/useOnlineStatus';

export function OfflineBanner() {
  const online = useOnlineStatus();
  if (online) return null;

  return (
    <div
      className="border-b border-amber-900/40 bg-amber-950/30 px-4 py-2 text-center text-sm text-amber-200"
      role="status"
    >
      You&apos;re offline — notes stay on this device. Cloud sync resumes when you reconnect.
    </div>
  );
}
