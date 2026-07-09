import { useToastStore } from '@/store/toastStore';

export interface UndoAction {
  message: string;
  revert: () => void | Promise<void>;
}

export function showUndoToast({ message, revert }: UndoAction): void {
  useToastStore.getState().show(message, 'default', 'Undo', () => {
    void revert();
  });
}
