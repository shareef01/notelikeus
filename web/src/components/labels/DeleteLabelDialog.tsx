interface DeleteLabelDialogProps {
  open: boolean;
  labelName: string;
  onCancel: () => void;
  onConfirm: () => void;
}

export function DeleteLabelDialog({
  open,
  labelName,
  onCancel,
  onConfirm,
}: DeleteLabelDialogProps) {
  if (!open) return null;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 sm:items-center sm:p-6">
      <div className="w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl">
        <h4 className="text-lg font-semibold">Delete label?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          &ldquo;{labelName}&rdquo; will be removed from all notes. This cannot be undone.
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
            className="rounded-note bg-red-600 px-4 py-2 text-sm font-semibold text-white"
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}
