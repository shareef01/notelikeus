import { useEffect, useRef, useState } from 'react';
import { ModalScrim } from '@/components/layout/ModalScrim';

interface LinkDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (url: string) => void;
}

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function LinkDialog({ open, onCancel, onConfirm }: LinkDialogProps) {
  const [url, setUrl] = useState('');
  const panelRef = useRef<HTMLFormElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) {
      setUrl('');
      return;
    }

    previousFocusRef.current = document.activeElement as HTMLElement | null;
    inputRef.current?.focus();

    const panel = panelRef.current;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCancel();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;

      const items = panel.querySelectorAll<HTMLElement>(FOCUSABLE);
      if (items.length === 0) return;

      const first = items[0];
      const last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocusRef.current?.focus?.();
    };
  }, [open, onCancel]);

  if (!open) return null;

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = url.trim();
    if (!trimmed) return;
    onConfirm(trimmed);
    setUrl('');
  };

  return (
    <ModalScrim zIndexClass="z-[70]" onScrimClick={onCancel}>
      <form
        ref={panelRef}
        onSubmit={handleSubmit}
        role="dialog"
        aria-modal="true"
        aria-labelledby="link-dialog-title"
        className="relative w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl"
        onClick={(event) => event.stopPropagation()}
      >
        <h4 id="link-dialog-title" className="text-lg font-semibold">
          Add link
        </h4>
        <p className="mt-1 text-sm text-brand-muted">Enter the URL for the selected text.</p>
        <input
          ref={inputRef}
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com"
          className="mt-4 w-full rounded-note border border-brand-outline/50 bg-true-surface-variant px-4 py-3 text-sm text-brand-primary outline-none focus-visible:border-brand-primary/50"
          aria-label="Link URL"
        />
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="min-h-11 rounded-note px-4 py-2 text-sm text-brand-muted"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!url.trim()}
            className="min-h-11 rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black disabled:opacity-40"
          >
            Add link
          </button>
        </div>
      </form>
    </ModalScrim>
  );
}
