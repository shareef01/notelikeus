import { useOnlineStatus } from '@/hooks/useOnlineStatus';

export function OfflineBanner() {
  const online = useOnlineStatus();
  if (online) return null;

  return (
    <div
      className="flex items-center justify-center gap-2 border-b border-amber-900/30 bg-amber-950/20 px-4 py-2 text-center text-[12px] font-medium leading-tight text-amber-300/80"
      role="status"
    >
      <span className="inline-block size-2 rounded-full bg-amber-400/60" />
      You&apos;re offline — changes sync when you reconnect.
    </div>
  );
}
