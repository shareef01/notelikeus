import { ModalScrim, modalPanelProps } from '@/components/layout/ModalScrim';

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
  if (!open) return null;

  return (
    <ModalScrim onScrimClick={onCancel}>
      <div {...modalPanelProps('w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl')}>
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
            Delete
          </button>
        </div>
      </div>
    </ModalScrim>
  );
}
