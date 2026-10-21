import { CloseIcon } from '@/components/icons/Icons';
import { useShortcuts } from '@/hooks/useShortcuts';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { useToastStore, type ToastMessage } from '@/store/toastStore';
import { useEffect, useRef, useState } from 'react';

/**
 * How long a toast stays up, unpaused. An Undo is the only way back from a permanent delete or an
 * emptied trash, so it gets the longest; an error has to be read, not glimpsed.
 */
export function toastDurationMs(message: Pick<ToastMessage, 'tone' | 'onAction'>): number {
  if (message.onAction) return 8000;
  if (message.tone === 'error') return 7000;
  return 4000;
}

/**
 * One toast at a time (DESIGN_SYSTEM §5).
 *
 * - The timer pauses while the pointer is over the toast or focus is inside it (WCAG 2.2.1): a
 *   fixed five seconds was not long enough to reach Undo by keyboard, and it ran out under a
 *   hovering mouse.
 * - Ctrl/Cmd+Z runs a toast's Undo from anywhere outside a text field, so undoing never depends
 *   on reaching the toast at all.
 * - Both live regions are always in the document, and only their content changes: a region that
 *   mounts together with its text is not reliably announced.
 */
export function ToastHost() {
  const message = useToastStore((s) => s.message);
  const dismiss = useToastStore((s) => s.dismiss);
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const paused = hovered || focused;
  const timing = useRef<{ id: number; remaining: number } | null>(null);

  useEffect(() => {
    if (!message) {
      timing.current = null;
      return;
    }
    if (timing.current?.id !== message.id) {
      timing.current = { id: message.id, remaining: toastDurationMs(message) };
    }
    if (paused) return;
    const started = Date.now();
    const current = timing.current;
    const timer = setTimeout(dismiss, current.remaining);
    return () => {
      clearTimeout(timer);
      current.remaining = Math.max(0, current.remaining - (Date.now() - started));
    };
  }, [message, paused, dismiss]);

  // A dismissed toast takes its hover and focus with it; the next one starts unpaused.
  useEffect(() => {
    setHovered(false);
    setFocused(false);
  }, [message?.id]);

  const runAction = () => {
    message?.onAction?.();
    dismiss();
  };

  useShortcuts(
    [
      {
        key: 'z',
        ctrlOrMeta: true,
        action: () => {
          if (message?.actionLabel !== 'Undo' || !message.onAction) return false;
          runAction();
        },
      },
    ],
    { ignoreWhileModalOpen: true },
  );

  const toast = message ? (
    <div
      data-testid="toast"
      className={`fixed bottom-24 left-1/2 z-[70] flex max-w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 items-center gap-2 rounded-note py-2 pl-4 pr-2 text-sm font-medium shadow-lg pb-safe animate-in fade-in slide-in-from-bottom-4 duration-200 motion-reduce:animate-none md:bottom-8 ${
        message.tone === 'error'
          ? 'bg-red-950 text-red-100'
          : 'bg-true-surface text-brand-primary border border-brand-outline/40'
      }`}
      onPointerEnter={() => setHovered(true)}
      onPointerLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setFocused(false);
      }}
    >
      <span className="min-w-0 flex-1 py-1">{message.text}</span>
      {message.actionLabel && message.onAction ? (
        <button
          type="button"
          onClick={runAction}
          aria-keyshortcuts={message.actionLabel === 'Undo' ? 'Control+Z' : undefined}
          title={message.actionLabel === 'Undo' ? 'Undo (Ctrl+Z)' : undefined}
          className={`min-h-10 shrink-0 rounded-lg px-3 text-sm font-bold hover:bg-[color-mix(in_srgb,currentColor_10%,transparent)] ${
            message.tone === 'error' ? 'text-red-100' : 'text-brand-primary'
          } ${CHROME_FOCUS}`}
        >
          {message.actionLabel}
        </button>
      ) : null}
      <button
        type="button"
        onClick={dismiss}
        aria-label="Dismiss"
        className={`flex size-10 shrink-0 items-center justify-center rounded-full opacity-70 hover:opacity-100 ${CHROME_FOCUS}`}
      >
        <CloseIcon size={18} />
      </button>
    </div>
  ) : null;

  return (
    <>
      <div role="status" aria-live="polite">
        {message && message.tone !== 'error' ? toast : null}
      </div>
      <div role="alert">{message?.tone === 'error' ? toast : null}</div>
    </>
  );
}
