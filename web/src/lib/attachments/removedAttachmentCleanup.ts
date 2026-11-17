import { getOwnerMeta, listStoredNotes, updateOwnerMeta } from '@/lib/local/notesLocalRepository';
import { captureOwnerScope } from '@/lib/local/ownerScope';
import { getAttachmentBlobStore } from './attachmentBlobStoreRegistry';
import { ATTACHMENT_PENDING_PREFIX, isPendingAttachment } from './attachmentPaths';
import { releasePendingAttachment } from './pendingAttachmentStore';

/**
 * Durable removal intent survives an offline save or a failed Worker delete.
 *
 * An intent only exists for an edit the cloud accepted or has yet to answer: a refused edit's
 * intent is dropped with its pending marker (`resolveConflictedPush`, AUD-G01).
 */
export async function retryRemovedAttachmentCleanup(ownerId: string): Promise<void> {
  const scope = captureOwnerScope();
  if (scope.ownerId !== ownerId) return;
  const meta = await getOwnerMeta(ownerId);
  const ready = Object.entries(meta?.pendingAttachmentRemovals ?? {}).filter(
    ([noteId, removed]) => removed.length > 0 && !meta?.pendingPushes?.[noteId],
  );
  // Every save calls this, and nearly every save has nothing to clean up. Reading the library
  // means decrypting all of it, so that only happens when something is waiting (AUD-G03).
  if (ready.length === 0) return;
  // Not listNotes: it throws on the first note that cannot be decrypted, and one unreadable note
  // must not stop cleanup for the rest of the account.
  const { notes } = await listStoredNotes(ownerId);
  for (const [noteId, removed] of ready) {
    if (!scope.isActive()) return;
    const note = notes.find(n => n.id === noteId);
    if (!note) continue; // Permanent-note deletion owns its own cleanup.
    for (const attachment of removed) {
      if (!scope.isActive()) return;
      if (note.attachments.some(a => a.id === attachment.id)) continue;
      try {
        if (isPendingAttachment(attachment.storagePath)) {
          await releasePendingAttachment(attachment.storagePath.slice(ATTACHMENT_PENDING_PREFIX.length), noteId, ownerId);
        } else {
          await getAttachmentBlobStore().delete(noteId, attachment.id, ownerId);
        }
        await updateOwnerMeta(ownerId, current => {
          const removals = { ...current.pendingAttachmentRemovals };
          const left = (removals[noteId] ?? []).filter(a => a.id !== attachment.id);
          if (left.length > 0) removals[noteId] = left;
          else delete removals[noteId];
          return { ...current, pendingAttachmentRemovals: removals };
        });
      } catch { /* Keep intent for the next push/pull. */ }
    }
  }
}
