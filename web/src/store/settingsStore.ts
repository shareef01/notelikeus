import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface SettingsState {
  cloudAutoSyncEnabled: boolean;
  useMonochromeTheme: boolean;
  trueDarkMode: boolean;
  setCloudAutoSyncEnabled: (enabled: boolean) => void;
  setUseMonochromeTheme: (enabled: boolean) => void;
  setTrueDarkMode: (enabled: boolean) => void;
}

export const useSettingsStore = create<SettingsState>()(
  persist(
    (set) => ({
      cloudAutoSyncEnabled: true,
      useMonochromeTheme: true,
      trueDarkMode: true,
      setCloudAutoSyncEnabled: (cloudAutoSyncEnabled) =>
        set((state) =>
          state.cloudAutoSyncEnabled === cloudAutoSyncEnabled ? state : { cloudAutoSyncEnabled },
        ),
      setUseMonochromeTheme: (useMonochromeTheme) =>
        set((state) =>
          state.useMonochromeTheme === useMonochromeTheme ? state : { useMonochromeTheme },
        ),
      setTrueDarkMode: (trueDarkMode) =>
        set((state) => (state.trueDarkMode === trueDarkMode ? state : { trueDarkMode })),
    }),
    { name: 'notelikeus-settings', skipHydration: true },
  ),
);
