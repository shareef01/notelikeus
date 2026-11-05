import { create } from 'zustand';

export interface ToastMessage {
  id: number;
  text: string;
  tone?: 'default' | 'error';
  actionLabel?: string;
  onAction?: () => void;
}

interface ToastState {
  message: ToastMessage | null;
  /**
   * `onExpire` runs once when the toast goes away without its action being taken: timed out,
   * closed, or replaced by the next toast. Whatever an Undo protects is released there.
   */
  show: (
    text: string,
    tone?: ToastMessage['tone'],
    actionLabel?: string,
    onAction?: () => void,
    onExpire?: () => void,
  ) => void;
  dismiss: () => void;
}

let toastId = 0;
/** The current toast's expiry, until it is taken, expires, or is replaced. */
let pendingExpiry: (() => void) | null = null;

function expireCurrent(): void {
  const expire = pendingExpiry;
  pendingExpiry = null;
  expire?.();
}

export const useToastStore = create<ToastState>((set) => ({
  message: null,
  show: (text, tone = 'default', actionLabel, onAction, onExpire) => {
    expireCurrent();
    toastId += 1;
    pendingExpiry = onExpire ?? null;
    set({
      message: {
        id: toastId,
        text,
        tone,
        actionLabel,
        onAction: onAction
          ? () => {
              // Taken, so it never expires.
              pendingExpiry = null;
              onAction();
            }
          : undefined,
      },
    });
  },
  dismiss: () => {
    expireCurrent();
    set({ message: null });
  },
}));
