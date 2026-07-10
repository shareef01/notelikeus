import { useEffect, useState } from 'react';

interface LinkDialogProps {
  open: boolean;
  onCancel: () => void;
  onConfirm: (url: string) => void;
}

export function LinkDialog({ open, onCancel, onConfirm }: LinkDialogProps) {
  const [url, setUrl] = useState('');

  useEffect(() => {
    if (open) setUrl('');
  }, [open]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <div className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl">
        <h4 className="text-lg font-semibold">Add link</h4>
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder="https://example.com"
          autoFocus
          className="mt-4 w-full rounded-note border border-brand-outline/50 bg-transparent px-4 py-3 text-sm text-brand-primary outline-none focus:border-brand-primary/50"
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
            type="button"
            onClick={() => onConfirm(url.trim())}
            disabled={!url.trim()}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black disabled:opacity-40"
          >
            OK
          </button>
        </div>
      </div>
    </div>
  );
}
