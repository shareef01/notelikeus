import { useToastStore } from '@/store/toastStore';
import { useEffect } from 'react';

export function ToastHost() {
  const message = useToastStore((s) => s.message);
  const dismiss = useToastStore((s) => s.dismiss);

  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(dismiss, 3500);
    return () => clearTimeout(timer);
  }, [message, dismiss]);

  if (!message) return null;

  return (
    <div
      className={`fixed bottom-6 left-1/2 z-[70] max-w-[min(24rem,calc(100vw-2rem))] -translate-x-1/2 rounded-note px-4 py-3 text-sm font-medium shadow-lg pb-safe lg:bottom-8 ${
        message.tone === 'error'
          ? 'bg-red-950 text-red-100'
          : 'bg-true-surface text-brand-primary border border-brand-outline/40'
      }`}
      role="status"
    >
      {message.text}
    </div>
  );
}
