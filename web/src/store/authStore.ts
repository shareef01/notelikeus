import { create } from 'zustand';
import type { User } from 'firebase/auth';

interface AuthState {
  user: User | null;
  isReady: boolean;
  setUser: (user: User | null) => void;
  setReady: (ready: boolean) => void;
  reset: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isReady: false,
  setUser: (user) =>
    set((state) => {
      if (state.user?.uid === user?.uid) return state;
      return { user };
    }),
  setReady: (isReady) =>
    set((state) => {
      if (state.isReady === isReady) return state;
      return { isReady };
    }),
  reset: () => set({ user: null, isReady: false }),
}));

export function selectUserId(state: AuthState): string | null {
  return state.user?.uid ?? null;
}
