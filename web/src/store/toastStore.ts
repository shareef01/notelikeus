import { create } from 'zustand';

export interface ToastMessage {
  id: number;
  text: string;
  tone?: 'default' | 'error';
}

interface ToastState {
  message: ToastMessage | null;
  show: (text: string, tone?: ToastMessage['tone']) => void;
  dismiss: () => void;
}

let toastId = 0;

export const useToastStore = create<ToastState>((set) => ({
  message: null,
  show: (text, tone = 'default') => {
    toastId += 1;
    set({ message: { id: toastId, text, tone } });
  },
  dismiss: () => set({ message: null }),
}));
