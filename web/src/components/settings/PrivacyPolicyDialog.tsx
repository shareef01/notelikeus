import { useId } from 'react';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { CloseIcon } from '@/components/icons/Icons';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';

const PRIVACY_POLICY_BODY = `Notelikeus is an offline-first notes application across Android, Windows (Desktop), and Web. You can use Notelikeus completely offline without creating an account or signing in. This policy describes how the app handles information locally on your device and when you optionally enable cloud sync.

Summary
• Offline-first by default: On Android, Windows, and Web, notes are stored locally on your device. You can use the full application without creating an account.
• Optional cloud sync: When you sign in, note text, checklists, and metadata sync to Supabase (PostgreSQL) under your user account. Attachment files may be stored in Cloudflare R2.
• Local data isolation: Signing out clears locally cached notes from the active session so a subsequent user cannot inherit your data.
• Encryption: Android local databases are encrypted at rest with SQLCipher backed by Android Keystore. Attachment image files and staged pending bytes on Android are encrypted with AES-GCM under a dedicated Android Keystore key. On Windows Desktop, the notes database is encrypted at rest with SQLCipher under a DPAPI-sealed passphrase; attachment files are sealed with AES-GCM under a separate DPAPI-protected key. On Web, note titles, bodies, and checklists in IndexedDB, and staged pending attachment blobs, are sealed with AES-GCM under non-extractable WebCrypto keys (profile-at-rest only; not an XSS mitigation).
• Synced cloud notes are not end-to-end encrypted by the app; they rely on TLS plus Supabase Auth, row-level security, and Worker authorization for attachments.
• Diagnostics stay local: an optional sync-diagnostics report shows counts and version numbers, never note content or credentials, and is never transmitted.
• The app does not include third-party tracking, analytics, or advertising SDKs.

Information stored on your device
• Android & Windows Desktop: Note titles, body text, colors, checklists, labels, and reminder timestamps in a local database (encrypted at rest with SQLCipher on Android and Windows Desktop). Local app preferences (theme, view mode, app lock status).
• Web: Note content, checklists, labels, and preferences stored in local browser storage (IndexedDB / localStorage). Operates fully as a guest / local-first PWA without requiring sign-in.

Cloud sync (optional)
When you choose to sign in and use sync, note content is stored in Supabase under your authenticated identity. Signing out clears locally cached notes on this device so the next account cannot inherit them; cloud data remains until you delete it.

Security
• Android: SQLCipher-encrypted Room database; attachment bytes sealed with AES-GCM under Android Keystore. Optional app-wide lock uses device biometric APIs.
• Windows Desktop: Notes database encrypted at rest with SQLCipher under a DPAPI-sealed passphrase; attachment bytes sealed with AES-GCM under a separate DPAPI-protected key. Session token file is also DPAPI-sealed. Key loss without a cloud account makes the local database unrecoverable — signed-in users re-sync; guests should keep a JSON / .nlkbak export.
• Web: Note titles, bodies, and checklists in IndexedDB, and staged pending attachment blobs, are sealed with AES-GCM under non-extractable WebCrypto keys (profile-at-rest only; not an XSS control). Other IndexedDB metadata remains readable at the profile boundary.
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
We do not sell your data. We do not use analytics or advertising SDKs in this app. Cloud providers used when you enable sync are Supabase (auth and note data; sign-in emails you a one-time code at the address you enter) and Cloudflare (web hosting and attachment objects).

Contact
For privacy questions, contact the app developer through the store listing or project repository.

Last updated: September 2026`;

const [introduction, ...policySections] = PRIVACY_POLICY_BODY.split('\n\n');
const lastUpdated = policySections.pop();

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
        className="flex max-h-[min(85dvh,52rem)] w-full max-w-2xl flex-col overflow-hidden rounded-[20px] border border-brand-outline/50 bg-true-surface shadow-2xl"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="flex shrink-0 items-start justify-between gap-3 border-b border-brand-outline/35 px-5 py-4 sm:px-7">
          <div>
            <h2 id={titleId} className="text-2xl font-bold tracking-tight text-brand-primary">Privacy policy</h2>
            <p className="mt-1 text-xs text-brand-muted">{lastUpdated}</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className={`grid size-11 shrink-0 place-items-center rounded-full bg-brand-primary/8 text-brand-primary ${CHROME_FOCUS}`}
          >
            <CloseIcon size={20} />
          </button>
        </header>
        <div tabIndex={0} aria-label="Privacy policy text" className={`min-h-0 overflow-y-auto overscroll-contain px-5 py-5 text-sm leading-relaxed text-brand-secondary sm:px-7 ${CHROME_FOCUS}`}>
          <p>{introduction}</p>
          {policySections.map((section) => {
            const [heading, ...lines] = section.split('\n');
            const firstBullet = lines.findIndex(line => line.startsWith('• '));
            // ES2022 target: no Array#findLastIndex.
            const lastBullet = lines.reduce((last, line, i) => (line.startsWith('• ') ? i : last), -1);
            return (
              <section key={heading} className="mt-5 space-y-2 border-t border-brand-outline/30 pt-4">
                <h3 className="text-base font-semibold text-brand-primary">{heading}</h3>
                {firstBullet < 0 ? <p>{lines.join(' ')}</p> : <>
                  {firstBullet > 0 && <p>{lines.slice(0, firstBullet).join(' ')}</p>}
                  <ul className="list-disc space-y-2 pl-5">
                    {lines.slice(firstBullet, lastBullet + 1).map(line => <li key={line}>{line.slice(2)}</li>)}
                  </ul>
                  {lastBullet < lines.length - 1 && <p>{lines.slice(lastBullet + 1).join(' ')}</p>}
                </>}
              </section>
            );
          })}
        </div>
      </div>
    </div>
  );
}
