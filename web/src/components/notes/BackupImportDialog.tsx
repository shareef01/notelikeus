import type { BackupImportResult } from '@/lib/backup/importBackup';
import { ModalScrim, modalPanelProps } from '@/components/layout/ModalScrim';

interface BackupImportDialogProps {
  open: boolean;
  fileName: string;
  result: BackupImportResult;
  onCancel: () => void;
  onConfirm: () => void;
}

export function BackupImportDialog({
  open,
  fileName,
  result,
  onCancel,
  onConfirm,
}: BackupImportDialogProps) {
  if (!open) return null;

  const hasChanges = result.notesImported > 0 || result.labelsCreated > 0;
  const parts: string[] = [];
  if (result.notesImported > 0) {
    parts.push(`${result.notesImported} note${result.notesImported === 1 ? '' : 's'}`);
  }
  if (result.labelsCreated > 0) {
    parts.push(`${result.labelsCreated} label${result.labelsCreated === 1 ? '' : 's'}`);
  }

  return (
    <ModalScrim onScrimClick={onCancel}>
      <div {...modalPanelProps('w-full max-w-md rounded-note bg-true-surface p-5 shadow-xl')}>
        <h4 className="text-lg font-semibold">Import backup?</h4>
        <p className="mt-2 text-sm text-brand-muted">
          {hasChanges
            ? `${parts.join(' and ')} from "${fileName}" will be merged with your existing notes.`
            : `No new notes or labels were found in "${fileName}".`}
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
            disabled={!hasChanges}
            className="rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-black disabled:opacity-40"
          >
            Import
          </button>
        </div>
      </div>
    </ModalScrim>
  );
}
