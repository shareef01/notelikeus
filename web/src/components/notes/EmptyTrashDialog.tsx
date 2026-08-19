import { ConfirmDialog } from '@/components/ui/ModalDialog';

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
  return (
    <ConfirmDialog
      open={open}
      title="Empty trash?"
      description={
        noteCount > 0
          ? `${noteCount} note${noteCount === 1 ? '' : 's'} will be deleted permanently. This cannot be undone.`
          : 'Trash is already empty.'
      }
      confirmLabel="Empty trash"
      confirmDisabled={noteCount === 0}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
