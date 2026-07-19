import { useToastStore } from '@/store/toastStore';
import { useEffect } from 'react';

export function ToastHost() {
  const message = useToastStore((s) => s.message);
  const dismiss = useToastStore((s) => s.dismiss);

  useEffect(() => {
    if (!message) return;
    const duration = message.onAction ? 5000 : 3500;
    const timer = setTimeout(dismiss, duration);
    return () => clearTimeout(timer);
  }, [message, dismiss]);

  if (!message) return null;

  return (
    <div
      className={`fixed bottom-24 left-1/2 z-[70] flex max-w-[min(24rem,calc(100vw-2rem))] -translate-x-1/2 items-center gap-3 rounded-note px-4 py-3 text-sm font-medium shadow-lg pb-safe animate-in fade-in slide-in-from-bottom-4 duration-200 md:bottom-8 ${
        message.tone === 'error'
          ? 'bg-red-950 text-red-100'
          : 'bg-true-surface text-brand-primary border border-brand-outline/40'
      }`}
      role="status"
      aria-live="polite"
    >
      <span className="min-w-0 flex-1">{message.text}</span>
      {message.actionLabel && message.onAction ? (
        <button
          type="button"
          onClick={() => {
            message.onAction?.();
            dismiss();
          }}
          className="shrink-0 rounded-lg px-2 py-1 text-sm font-bold text-brand-primary hover:bg-white/10"
        >
          {message.actionLabel}
        </button>
      ) : null}
    </div>
  );
}
