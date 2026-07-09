import { useEffect, useState } from 'react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const DISMISS_KEY = 'notelikeus-install-dismissed';

export function InstallPrompt() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (localStorage.getItem(DISMISS_KEY) === '1') return;
    if (window.matchMedia('(display-mode: standalone)').matches) return;

    const handler = (event: Event) => {
      event.preventDefault();
      setDeferred(event as BeforeInstallPromptEvent);
      setVisible(true);
    };

    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  if (!visible || !deferred) return null;

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, '1');
    setVisible(false);
    setDeferred(null);
  };

  const install = async () => {
    await deferred.prompt();
    await deferred.userChoice;
    dismiss();
  };

  return (
    <div className="border-b border-brand-outline/40 bg-true-surface-variant/50 px-4 py-3">
      <div className="mx-auto flex max-w-content items-center justify-between gap-3">
        <p className="text-sm text-brand-secondary">Install Notelikeus for quick access and offline use.</p>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={dismiss}
            className="rounded-note px-3 py-1.5 text-sm text-brand-muted hover:text-brand-primary"
          >
            Not now
          </button>
          <button
            type="button"
            onClick={() => void install()}
            className="rounded-note bg-brand-primary px-3 py-1.5 text-sm font-semibold text-true-black"
          >
            Install
          </button>
        </div>
      </div>
    </div>
  );
}
