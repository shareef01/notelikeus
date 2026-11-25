import { useEffect, useRef, useState } from 'react';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { dismissFocusTrapExcept } from '@/hooks/useFocusTrap';

const SETTINGS_PATH = '/settings';
const HISTORY_KEY = 'notelikeusSettingsFromNotes';

function isSettingsPath() {
  return window.location.pathname === SETTINGS_PATH;
}

export function useSettingsRoute() {
  const [settingsOpen, setSettingsOpen] = useState(isSettingsPath);
  const settingsWasOpen = useRef(settingsOpen);
  settingsWasOpen.current = settingsOpen;
  const opener = useRef<HTMLElement | null>(null);
  useBodyScrollLock(settingsOpen);

  useEffect(() => {
    const onPop = () => {
      const atSettings = isSettingsPath();
      if (!atSettings && settingsWasOpen.current && dismissFocusTrapExcept()) {
        window.history.forward();
        return;
      }
      setSettingsOpen(atSettings);
    };
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  useEffect(() => {
    if (!settingsOpen) opener.current?.focus({ preventScroll: true });
  }, [settingsOpen]);

  function openSettings() {
    if (isSettingsPath()) return;
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    window.history.pushState({ ...window.history.state, [HISTORY_KEY]: true }, '', SETTINGS_PATH);
    setSettingsOpen(true);
  }

  function closeSettings() {
    if (!isSettingsPath()) return;
    if (window.history.state?.[HISTORY_KEY]) {
      window.history.back();
    } else {
      window.history.replaceState(window.history.state, '', '/');
      setSettingsOpen(false);
    }
  }

  return { settingsOpen, openSettings, closeSettings };
}
