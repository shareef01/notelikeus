import { create } from 'zustand';
import type { User } from 'firebase/auth';

interface AuthState {
  user: User | null;
  isReady: boolean;
  /** Try-out mode: app shell renders without an account, notes live in memory only. */
  guestMode: boolean;
  setUser: (user: User | null) => void;
  setReady: (ready: boolean) => void;
  enterGuestMode: () => void;
  exitGuestMode: () => void;
  reset: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isReady: false,
  guestMode: false,
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
  enterGuestMode: () => set({ guestMode: true }),
  exitGuestMode: () => set((state) => (state.guestMode ? { guestMode: false } : state)),
  reset: () => set({ user: null, isReady: false, guestMode: false }),
}));

export function selectUserId(state: AuthState): string | null {
  return state.user?.uid ?? null;
}
