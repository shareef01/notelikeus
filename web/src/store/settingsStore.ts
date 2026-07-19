import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type AppTheme = 'auto' | 'light' | 'dark' | 'true_dark' | 'midnight' | 'forest';

interface SettingsState {
  cloudAutoSyncEnabled: boolean;
  appTheme: AppTheme;
  setCloudAutoSyncEnabled: (enabled: boolean) => void;
  setAppTheme: (theme: AppTheme) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      cloudAutoSyncEnabled: true,
      appTheme: 'true_dark',
      setCloudAutoSyncEnabled: (cloudAutoSyncEnabled) =>
        set((state) =>
          state.cloudAutoSyncEnabled === cloudAutoSyncEnabled ? state : { cloudAutoSyncEnabled },
        ),
      setAppTheme: (appTheme) =>
        set((state) => (state.appTheme === appTheme ? state : { appTheme })),
    }),
    { name: 'notelikeus-settings', skipHydration: true },
  ),
);
