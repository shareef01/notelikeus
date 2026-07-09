interface EmptyTrashDialogProps {
  open: boolean;
  noteCount: number;
  onCancel: () => void;
  onConfirm: () => void;
}

export function EmptyTrashDialog({
  open,
  noteCount,
  onCancel,
  onConfirm,
}: EmptyTrashDialogProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <div className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl">
        <h4 className="text-lg font-semibold">Empty trash?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          {noteCount > 0
            ? `${noteCount} note${noteCount === 1 ? '' : 's'} will be deleted permanently. This cannot be undone.`
            : 'Trash is already empty.'}
        </p>
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
            onClick={onConfirm}
            disabled={noteCount === 0}
            className="rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-40"
          >
            Empty trash
          </button>
        </div>
      </div>
    </div>
  );
}
