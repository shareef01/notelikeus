import { runNoteAction } from '@/lib/notes/runNoteAction';
import { useToastStore } from '@/store/toastStore';
import { captureOwnerScope } from '@/lib/local/ownerScope';

export interface UndoAction {
  message: string;
  revert: () => void | Promise<void>;
  /** Runs once if the toast goes away without Undo being pressed. */
  onExpire?: () => void;
  /** `error` when part of the action failed and the message says so. */
  tone?: 'default' | 'error';
}

export function showUndoToast({ message, revert, onExpire, tone = 'default' }: UndoAction): void {
  const scope = captureOwnerScope();
  useToastStore.getState().show(
    message,
    tone,
    'Undo',
    () => {
      void runNoteAction('Undo', async () => {
        scope.assertActive();
        await revert();
      });
    },
    onExpire ? () => { if (scope.isActive()) onExpire(); } : undefined,
  );
}
