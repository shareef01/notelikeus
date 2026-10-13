import { reconcileNowAfterUserAction } from '@/lib/notes/notesSyncService';
import { reuploadLocalNotesAfterWipe } from '@/lib/supabase/wipeGeneration';
import { useNotesStore } from '@/store/notesStore';
import { useState } from 'react';

interface CloudWipeNoticeProps {
  userId: string;
}

/**
 * "Your cloud notes were deleted from another device."
 *
 * This is the only place a wipe on one device reaches the user on another. It deliberately offers
 * two choices and no silent dismissal-to-success: until the user picks one, this browser pushes
 * nothing, because uploading here would be indistinguishable, server-side, from a stale device
 * undoing the wipe — which is the whole failure the wipe generation exists to stop.
 */
export function CloudWipeNotice({ userId }: CloudWipeNoticeProps) {
  const notice = useNotesStore((state) => state.cloudWipeNotice);
  const clearNotice = useNotesStore((state) => state.clearCloudWipeNotice);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  if (!notice) return null;

  const notes = notice.localNoteCount;
  const upload = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await reuploadLocalNotesAfterWipe(userId, notice.generation);
      clearNotice();
      // The block is what stopped the upload; with it lifted, run the sync the user just asked for
      // rather than waiting for the next visibility change.
      await reconcileNowAfterUserAction(userId);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="border-b border-amber-700/40 bg-amber-950/25 px-shell py-3">
      <div className="mx-auto flex max-w-content items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-amber-100">
            Your cloud notes were deleted from another device
          </p>
          <p className="mt-1 text-sm text-amber-100/80">
            {notes === 1
              ? 'Nothing was uploaded. 1 note is still on this browser.'
              : `Nothing was uploaded. ${notes} notes are still on this browser.`}{' '}
            It is not in the cloud any more, and it will stay here until you choose.
          </p>
          {failed && (
            <p role="alert" className="mt-1 text-sm text-red-300">
              Could not start the upload. Your notes are still here — try again.
            </p>
          )}
        </div>
        <div className="flex shrink-0 gap-2">
          <button
            type="button"
            onClick={() => clearNotice()}
            className="min-h-11 rounded-note px-3 py-2 text-sm font-semibold text-amber-200 hover:bg-amber-950/50"
          >
            Not now
          </button>
          <button
            type="button"
            onClick={() => void upload()}
            disabled={busy}
            className="min-h-11 rounded-note px-3 py-2 text-sm font-semibold text-amber-100 hover:bg-amber-950/50 disabled:opacity-60"
          >
            {busy ? 'Uploading…' : 'Upload my notes again'}
          </button>
        </div>
      </div>
    </div>
  );
}
