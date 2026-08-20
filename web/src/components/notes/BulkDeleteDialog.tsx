import { ConfirmDialog } from '@/components/ui/ModalDialog';

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
  return (
    <ConfirmDialog
      open={open}
      title="Delete permanently?"
      description={
        noteCount > 0
          ? `${noteCount} note${noteCount === 1 ? '' : 's'} will be deleted permanently. This cannot be undone.`
          : 'No notes selected.'
      }
      confirmLabel="Delete"
      confirmDisabled={noteCount === 0}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
