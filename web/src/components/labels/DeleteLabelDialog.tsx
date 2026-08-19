import { ConfirmDialog } from '@/components/ui/ModalDialog';

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
  return (
    <ConfirmDialog
      open={open}
      title="Delete label?"
      description={
        <>
          &ldquo;{labelName}&rdquo; will be removed from all notes. This cannot be undone.
        </>
      }
      confirmLabel="Delete"
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
