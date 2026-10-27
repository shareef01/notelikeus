import { runNoteAction } from '@/lib/notes/runNoteAction';
import { useToastStore } from '@/store/toastStore';

export interface UndoAction {
  message: string;
  revert: () => void | Promise<void>;
  /** `error` when part of the action failed and the message says so. */
  tone?: 'default' | 'error';
}

export function showUndoToast({ message, revert, tone = 'default' }: UndoAction): void {
  useToastStore.getState().show(message, tone, 'Undo', () => {
    void runNoteAction('Undo', async () => {
      await revert();
    });
  });
}
