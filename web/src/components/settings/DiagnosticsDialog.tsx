import { useEffect, useId, useState } from 'react';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { collectDiagnostics } from '@/lib/diagnostics/collectDiagnostics';
import { formatDiagnosticsReport } from '@/lib/diagnostics/diagnosticsReport';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
import { useToastStore } from '@/store/toastStore';

interface DiagnosticsDialogProps {
  open: boolean;
  appVersion: string;
  onClose: () => void;
}

/**
 * Shows the sync diagnostics report and offers to copy it.
 *
 * The whole report is on screen before it can be copied — deliberately. Asking someone to hand a
 * file to a maintainer without letting them read it first is the wrong shape for a local-first
 * app whose selling point is that nothing leaves the device unasked.
 */
export function DiagnosticsDialog({ open, appVersion, onClose }: DiagnosticsDialogProps) {
  const [text, setText] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const titleId = useId();
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setText(null);
    setFailed(false);
    void collectDiagnostics(appVersion)
      .then((report) => {
        if (cancelled) return;
        setText(formatDiagnosticsReport(report));
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [open, appVersion]);

  if (!open) return null;

  const copy = async () => {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      useToastStore.getState().show('Diagnostics copied');
    } catch {
      useToastStore.getState().show('Could not copy — select the text instead', 'error');
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 p-4 animate-in fade-in duration-200 sm:items-center sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex max-h-[80vh] w-full max-w-lg flex-col rounded-note bg-true-surface p-5 shadow-xl animate-in zoom-in-95 duration-200"
      >
        <h2 id={titleId} className="text-lg font-semibold">
          Sync diagnostics
        </h2>
        <p className="mt-2 text-xs text-brand-muted">
          Counts and version numbers only — no note text, no images, no account details. Nothing
          is sent anywhere; copying is up to you.
        </p>

        <pre
          className="mt-4 min-h-32 flex-1 overflow-auto rounded-note bg-black/30 p-3 text-xs leading-relaxed text-brand-primary"
          aria-live="polite"
          aria-busy={text == null && !failed}
        >
          {failed ? 'Diagnostics could not be collected.' : (text ?? 'Collecting…')}
        </pre>

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className={`rounded-note px-4 py-2 text-sm text-brand-muted transition-colors hover:text-brand-primary ${CHROME_FOCUS}`}
          >
            Close
          </button>
          <button
            type="button"
            onClick={() => void copy()}
            disabled={!text}
            className={`rounded-note bg-brand-primary px-4 py-2 text-sm font-semibold text-true-surface transition-transform disabled:opacity-40 active:scale-95 ${CHROME_FOCUS}`}
          >
            Copy
          </button>
        </div>
      </div>
    </div>
  );
}
