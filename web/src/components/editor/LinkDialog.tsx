import { useEffect, useRef, useState } from 'react';

interface LinkDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (url: string) => void;
}

export function LinkDialog({ open, onCancel, onConfirm }: LinkDialogProps) {
  const [url, setUrl] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) {
      setUrl('');
      return;
    }
    inputRef.current?.focus();
  }, [open]);

  if (!open) return null;

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = url.trim();
    if (!trimmed) return;
    onConfirm(trimmed);
    setUrl('');
  };

  return (
    <div className="fixed inset-0 z-[70] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl"
      >
        <h4 className="text-lg font-semibold">Add link</h4>
        <p className="mt-1 text-sm text-brand-muted">Enter the URL for the selected text.</p>
        <input
          ref={inputRef}
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com"
          className="mt-4 w-full rounded-note border border-brand-outline/50 bg-true-black px-4 py-3 text-sm text-brand-primary outline-none focus:border-brand-primary/50"
          aria-label="Link URL"
        />
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-note px-4 py-2 text-sm text-brand-muted"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!url.trim()}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black disabled:opacity-40"
          >
            Add link
          </button>
        </div>
      </form>
    </div>
  );
}
