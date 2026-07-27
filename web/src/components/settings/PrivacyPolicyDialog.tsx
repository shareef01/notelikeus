import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useEffect } from 'react';

const PRIVACY_POLICY_BODY = `Sign-in with Google is required to use Notelikeus on the web. Your notes are stored in Firestore under your Google account — this browser keeps only a temporary in-memory copy while the app is open, plus a Firestore-managed offline cache for continuity without a connection.

Data stored on device
• App preferences: theme and layout
• Note text, titles, colors, labels, checklists, and reminders live in your Firebase account, not in this browser's storage

Cloud sync
• Note text always syncs to Firebase Firestore under your Google account — there's no separate offline-only mode on the web
• Signing out clears any locally cached labels and preferences tied to this account so the next account cannot inherit them

Security
• Notes are not stored on this device — they live in Firestore, protected by your Google account and Firestore's access rules, not by anything in this browser.

Permissions
• Notifications: used only for note reminders you set (reminders show a generic message, not note text)
• Storage: used when you export or import backup files

Backups
• JSON backups are created and restored only when you choose. Backup files are saved where you pick and are your responsibility to protect.
• Backups contain all your notes in plain text, since a backup is meant to be a complete copy of your data

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
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);

  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[70] flex items-end justify-center bg-black/80 p-4 backdrop-blur-sm sm:items-center"
      onClick={onClose}
    >
      <div
        ref={panelRef}
        className="max-h-[85vh] w-full max-w-lg overflow-y-auto rounded-[20px] border border-brand-outline bg-true-surface p-6 shadow-2xl"
        role="dialog"
        aria-modal="true"
        aria-label="Privacy policy"
        onClick={(event) => event.stopPropagation()}
      >
        <h4 className="text-xl font-bold tracking-tight text-brand-primary">Privacy policy</h4>
        <p className="mt-4 whitespace-pre-line text-[14px] leading-[1.4em] text-brand-muted">
          {PRIVACY_POLICY_BODY}
        </p>
        <div className="mt-6 flex justify-end">
          <button
            type="button"
            onClick={onClose}
            className="min-h-11 rounded-note bg-brand-primary px-6 py-2 text-sm font-bold text-true-surface transition-transform active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
