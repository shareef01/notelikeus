import { PRIVACY_POLICY_URL } from '@/lib/constants';
import { ModalScrim, modalPanelProps } from '@/components/layout/ModalScrim';

const PRIVACY_POLICY_BODY = `Notelikeus is an offline-first notes app. Your notes are stored locally in this browser. Optional cloud sync uploads note text to your own Firebase account when you sign in and choose to sync.

Data stored on device
• Note text, titles, colors, labels, checklists, and reminders
• Optional settings such as theme and auto-sync preferences

Cloud sync
• When enabled, note text (except locked notes) syncs to Firebase Firestore under your Google account
• Locked notes are never uploaded

Security
• Notes in this browser are stored in local storage on your device
• Per-note lock hides content until you unlock it in the app

Permissions
• Notifications: used only for note reminders you set
• Storage: used when you export or import backup files

Backups
• JSON backups are created and restored only when you choose. Backup files are saved where you pick and are your responsibility to protect.

Links
• Tapping a link in a note opens it in your browser. Notelikeus does not track link visits.

Third parties
• We do not sell your data. We do not use analytics or advertising SDKs in this app.

Contact
• For privacy questions, contact the app developer through the store listing or project repository.

Last updated: July 2026`;

interface PrivacyPolicyDialogProps {
  open: boolean;
  onClose: () => void;
}

export function PrivacyPolicyDialog({ open, onClose }: PrivacyPolicyDialogProps) {
  if (!open) return null;

  return (
    <ModalScrim zIndexClass="z-[70]" align="center" onScrimClick={onClose}>
      <div
        {...modalPanelProps(
          'max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-[20px] border border-brand-outline bg-true-surface p-6 shadow-2xl',
        )}
        role="dialog"
        aria-modal="true"
        aria-label="Privacy policy"
      >
        <h4 className="text-xl font-bold tracking-tight text-brand-primary">Privacy policy</h4>
        <p className="mt-4 whitespace-pre-line text-[14px] leading-[1.4em] text-brand-muted">
          {PRIVACY_POLICY_BODY}
        </p>
        <p className="mt-4 text-sm">
          <a
            href={PRIVACY_POLICY_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="font-semibold text-brand-primary underline underline-offset-2"
          >
            View full policy online
          </a>
        </p>
        <div className="mt-6 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="rounded-note bg-brand-primary px-6 py-2 text-sm font-bold text-true-black transition-transform active:scale-95"
          >
            Close
          </button>
        </div>
      </div>
    </ModalScrim>
  );
}
