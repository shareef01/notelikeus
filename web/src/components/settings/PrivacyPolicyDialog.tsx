import { useId } from 'react';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { useFocusTrap } from '@/hooks/useFocusTrap';

const PRIVACY_POLICY_BODY = `Notelikeus is an offline-first notes application across Android, Windows (Desktop), and Web. You can use Notelikeus completely offline without creating an account or signing in. This policy describes how the app handles information locally on your device and when you optionally enable cloud sync.

Summary
• Offline-first by default: On Android, Windows, and Web, notes are stored locally on your device. You can use the full application without creating an account.
• Optional cloud sync: When you sign in, note text, checklists, and metadata sync to Supabase (PostgreSQL) under your user account. Attachment files may be stored in Cloudflare R2.
• Local data isolation: Signing out clears locally cached notes from the active session so a subsequent user cannot inherit your data.
• Encryption: Android local databases are encrypted at rest with SQLCipher backed by Android Keystore. Attachment image files and staged pending bytes on Android are encrypted with AES-GCM under a dedicated Android Keystore key. On Windows Desktop, attachment files are sealed with AES-GCM under a DPAPI-protected key; the Desktop notes database remains plaintext at the app layer. Web attachment blobs rely on browser profile permissions.
• Synced cloud notes are not end-to-end encrypted by the app; they rely on TLS plus Supabase Auth, row-level security, and Worker authorization for attachments.
• Diagnostics stay local: an optional sync-diagnostics report shows counts and version numbers, never note content or credentials, and is never transmitted.
• The app does not include third-party tracking, analytics, or advertising SDKs.

Information stored on your device
• Android & Windows Desktop: Note titles, body text, colors, checklists, labels, and reminder timestamps in a local database (encrypted on Android). Local app preferences (theme, view mode, app lock status).
• Web: Note content, checklists, labels, and preferences stored in local browser storage (IndexedDB / localStorage). Operates fully as a guest / local-first PWA without requiring sign-in.

Cloud sync (optional)
When you choose to sign in and use sync, note content is stored in Supabase under your authenticated identity. Signing out clears locally cached notes on this device so the next account cannot inherit them; cloud data remains until you delete it.

Security
• Android: SQLCipher-encrypted Room database; attachment bytes sealed with AES-GCM under Android Keystore. Optional app-wide lock uses device biometric APIs.
• Windows Desktop: Notes database plaintext at the app layer; attachment bytes sealed with AES-GCM under a DPAPI-protected key. Session token file is also DPAPI-sealed.
• Web: Local storage bound to browser profile permissions; attachment blobs are not app-encrypted at the file layer.
• Cloud security: PostgreSQL row-level security and authorized RPCs restrict read and write operations to the authenticated owner.

Permissions
• Internet: Authentication and cloud sync
• Notifications: Deliver reminders you schedule for notes
• Biometric: Unlock the app when app lock is enabled (Android)

Backups
Backup export and import are manual. Backup files are written to a location you choose. You are responsible for securing copied files.
• JSON: note titles, bodies, checklists, labels, colours and reminder times. No images.
• .nlkbak: a plain ZIP holding the same JSON document plus the attachment image bytes stored on this device. Rename it to .zip and you can open it and see exactly what it contains.
Neither format contains sign-in tokens, session data, encryption keys, sync cursors, or your account identifier. An export never contacts the network — images that exist only in the cloud are left out and reported as skipped.

Diagnostics
Sync diagnostics shows counts and version numbers only: app and database versions, how many notes are active, archived, trashed or pinned, how many are waiting to sync, the last sync time, and a category name for the last error. It contains no note or checklist text, no images, no email address, no sign-in tokens, and not your account identifier — your account appears only as a short scrambled tag that cannot be turned back into an identity. It is shown on screen and never transmitted; copying it is entirely your choice.

Links in notes
Tapping a link opens your default browser. Notelikeus does not track link usage.

Third parties
We do not sell your data. We do not use analytics or advertising SDKs in this app. Cloud providers used when you enable sync are Supabase (auth and note data) and Cloudflare (web hosting and attachment objects).

Contact
For privacy questions, contact the app developer through the store listing or project repository.

Last updated: September 2026`;

interface PrivacyPolicyDialogProps {
  open: boolean;
  onClose: () => void;
}

export function PrivacyPolicyDialog({ open, onClose }: PrivacyPolicyDialogProps) {
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);
  const titleId = useId();
  useBodyScrollLock(open);

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
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <h2 id={titleId} className="text-xl font-bold tracking-tight text-brand-primary">
          Privacy policy
        </h2>
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
