import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type AppTheme = 'auto' | 'light' | 'dark' | 'true_dark' | 'midnight' | 'forest';

interface SettingsState {
  appTheme: AppTheme;
  setAppTheme: (theme: AppTheme) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      appTheme: 'true_dark',
      setAppTheme: (appTheme) =>
        set((state) => (state.appTheme === appTheme ? state : { appTheme })),
    }),
    { name: 'notelikeus-settings', skipHydration: true },
  ),
);
