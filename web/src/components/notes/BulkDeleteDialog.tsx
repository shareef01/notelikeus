import { useFocusTrap } from '@/hooks/useFocusTrap';

interface BulkDeleteDialogProps {
  open: boolean;
  noteCount: number;
  onCancel: () => void;
  onConfirm: () => void;
}

export function BulkDeleteDialog({
  open,
  noteCount,
  onCancel,
  onConfirm,
}: BulkDeleteDialogProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onCancel);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label="Delete permanently?"
        className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        <h4 className="text-lg font-semibold">Delete permanently?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          {noteCount > 0
            ? `${noteCount} note${noteCount === 1 ? '' : 's'} will be deleted permanently. This cannot be undone.`
            : 'No notes selected.'}
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={noteCount === 0}
            className="rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-red-700 disabled:opacity-40 disabled:hover:bg-red-600"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}
