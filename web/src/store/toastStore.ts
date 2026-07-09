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
  show: (
    text: string,
    tone?: ToastMessage['tone'],
    actionLabel?: string,
    onAction?: () => void,
  ) => void;
  dismiss: () => void;
}

let toastId = 0;

export const useToastStore = create<ToastState>((set) => ({
  message: null,
  show: (text, tone = 'default', actionLabel, onAction) => {
    toastId += 1;
    set({
      message: {
        id: toastId,
        text,
        tone,
        actionLabel,
        onAction,
      },
    });
  },
  dismiss: () => set({ message: null }),
}));
