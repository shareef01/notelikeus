import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useEffect } from 'react';

const PRIVACY_POLICY_BODY = `Sign-in with Google is required to use Notelikeus on the web. Your notes are stored in Firestore under your Google account. This browser may keep a Firestore-managed offline cache for continuity without a connection, and some browsers may fall back to temporary in-memory storage only.

Data stored on device
• App preferences: theme and layout
• Firestore may keep an offline cache in the browser for continuity while signed in
• Exported backup files are stored only where you choose to save them

Cloud sync
• Note text, titles, labels, checklists, colors, and reminders sync to Firebase Firestore under your Google account
• There is no separate offline-only mode on the web
• Signing out clears locally cached app data tied to this account so the next account cannot inherit it

Security
• Web notes are protected by your Google account, browser profile, and Firestore access rules
• The web app does not provide end-to-end encryption for synced notes
• If someone can access your signed-in browser profile or exported backup files, they may be able to read your notes

Permissions
• Notifications: used only for note reminders you set (reminders show a generic message, not note text)
• Storage: used when you export or import backup files
• Web reminders are best-effort and may be delayed or missed if the browser is fully closed or inactive

Backups
• JSON backups are created and restored only when you choose. Backup files are saved where you pick and are your responsibility to protect
• Backups contain your notes in plain text so they can be restored on Android or web

Links
• Tapping a link in a note opens it in your browser. Notelikeus does not track link visits

Third parties
• We do not sell your data. We do not use analytics or advertising SDKs in this app

Contact
• For privacy questions, contact the app developer through the store listing or project repository

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
