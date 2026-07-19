import { useFocusTrap } from '@/hooks/useFocusTrap';
import { useEffect } from 'react';

const PRIVACY_POLICY_BODY = `Notelikeus is an offline-first notes app. Sign-in with Google is required. Your notes are stored locally in this browser. Cloud sync uploads note text to your own Firebase account when auto-sync is enabled.

Data stored on device
• Note text, titles, colors, labels, checklists, and reminders
• Optional settings such as theme and auto-sync preferences

Cloud sync
• When enabled, note text (except locked notes) syncs to Firebase Firestore under your Google account
• Locked notes are never uploaded
• Signing out clears local notes on this device so the next account cannot inherit them

Security
• Unlocked notes in this browser are stored in local storage on your device
• Hidden (locked) notes encrypt title, body, and checklist at rest with a device-local key (AES-GCM). The key never leaves this browser profile and is cleared on sign-out. This protects against casual inspection of storage; it is not a substitute for full-disk encryption or a strong account passphrase.
• Per-note hide also keeps content out of the feed, search, reminders, and cloud sync until you show it again. On browsers that support it, revealing a hidden note requires your device's screen lock (fingerprint, face, or PIN).

Permissions
• Notifications: used only for note reminders you set (reminders show a generic message, not note text)
• Storage: used when you export or import backup files

Backups
• JSON backups are created and restored only when you choose. Backup files are saved where you pick and are your responsibility to protect.
• Unlike cloud sync, backups include hidden notes in plain text, since a backup is meant to be a complete copy of your data

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

/**
 * Privacy Policy Overhaul
 * Geometric Discipline: Absolute 20px radius and backdrop-blur synchronization.
 */
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
