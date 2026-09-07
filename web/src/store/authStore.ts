import { create } from 'zustand';
import type { AuthUser } from '@/lib/auth/authUser';
import { clearLocalUserData } from '@/lib/bootstrap';

interface AuthState {
  user: AuthUser | null;
  isReady: boolean;
  /**
   * Try-out mode: the app runs without an account. Guest notes are not throwaway — they persist
   * in IndexedDB under their own owner namespace, so they survive a reload like any other note.
   */
  guestMode: boolean;
  setUser: (user: AuthUser | null) => void;
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
  enterGuestMode: () => {
    // Guest IndexedDB is a separate owner namespace, but labels and tombstones
    // lived in un-namespaced localStorage — a prior account's labels would otherwise
    // appear in guest filters, and in-memory notes would flash until guest bootstrap.
    clearLocalUserData();
    set({ guestMode: true });
  },
  exitGuestMode: () => set((state) => (state.guestMode ? { guestMode: false } : state)),
  reset: () => set({ user: null, isReady: false, guestMode: false }),
}));

export function selectUserId(state: AuthState): string | null {
  return state.user?.uid ?? null;
}
