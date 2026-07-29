import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useEffect, useState } from 'react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

const DISMISS_KEY = 'notelikeus-install-dismissed-until';
/** Re-offer install after 14 days if the user said "Not now". */
const DISMISS_TTL_MS = 14 * 24 * 60 * 60 * 1000;

function isDismissed(): boolean {
  const raw = localStorage.getItem(DISMISS_KEY);
  if (!raw) return false;
  // Legacy permanent dismiss flag from older builds.
  if (raw === '1') return true;
  const until = Number(raw);
  return Number.isFinite(until) && Date.now() < until;
}

function isIosSafari(): boolean {
  const ua = navigator.userAgent;
  const iOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const webkit = /WebKit/.test(ua);
  const chromeOrCriOS = /CriOS|Chrome|FxiOS|EdgiOS/.test(ua);
  return iOS && webkit && !chromeOrCriOS;
}

export function InstallPrompt() {
  const online = useOnlineStatus();
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [visible, setVisible] = useState(false);
  const [iosHint, setIosHint] = useState(false);

  useEffect(() => {
    if (isDismissed()) return;
    if (window.matchMedia('(display-mode: standalone)').matches) return;
    if ((navigator as Navigator & { standalone?: boolean }).standalone) return;

    if (isIosSafari()) {
      setIosHint(true);
      setVisible(true);
      return;
    }

    const handler = (event: Event) => {
      event.preventDefault();
      setDeferred(event as BeforeInstallPromptEvent);
      setVisible(true);
    };

    window.addEventListener('beforeinstallprompt', handler);
    return () => window.removeEventListener('beforeinstallprompt', handler);
  }, []);

  // Don't stack with the offline banner — install is irrelevant while offline.
  if (!online || !visible) return null;
  if (!iosHint && !deferred) return null;

  const dismiss = () => {
    localStorage.setItem(DISMISS_KEY, String(Date.now() + DISMISS_TTL_MS));
    setVisible(false);
    setDeferred(null);
    setIosHint(false);
  };

  const install = async () => {
    if (!deferred) return;
    await deferred.prompt();
    await deferred.userChoice;
    dismiss();
  };

  return (
    <div className="border-b border-brand-outline/40 bg-true-surface-variant/50 px-4 py-3">
      <div className="mx-auto flex max-w-content items-center justify-between gap-3">
        <p className="min-w-0 text-sm text-brand-secondary">
          {iosHint
            ? 'Install Notelikeus: tap Share, then Add to Home Screen.'
            : 'Install Notelikeus for quick access and offline use.'}
        </p>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={dismiss}
            className="min-h-11 rounded-note px-3 py-2 text-sm text-brand-muted hover:text-brand-primary"
          >
            Not now
          </button>
          {deferred ? (
            <button
              type="button"
              onClick={() => void install()}
              className="min-h-11 rounded-note bg-brand-primary px-3 py-2 text-sm font-semibold text-true-surface"
            >
              Install
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
