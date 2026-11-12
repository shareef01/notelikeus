import { ConfirmDialog } from '@/components/ui/ModalDialog';

interface DeleteLabelDialogProps {
  open: boolean;
  labelName: string;
  /** Active and archived notes carrying the label. */
  noteCount?: number;
  onCancel: () => void;
  onConfirm: () => void;
}

export function DeleteLabelDialog({
  open,
  labelName,
  noteCount = 0,
  onCancel,
  onConfirm,
}: DeleteLabelDialogProps) {
  return (
    <ConfirmDialog
      open={open}
      title="Delete label?"
      description={
        <>
          &ldquo;{labelName}&rdquo; will be removed from{' '}
          {noteCount === 0 ? 'all notes' : noteCount === 1 ? '1 note' : `${noteCount} notes`}.{' '}
          {noteCount === 1 ? 'The note itself is kept.' : 'The notes themselves are kept.'} This cannot be undone.
        </>
      }
      confirmLabel="Delete"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
