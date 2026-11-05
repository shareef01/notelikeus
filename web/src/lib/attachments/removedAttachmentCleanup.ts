import { getOwnerMeta, listNotes, updateOwnerMeta } from '@/lib/local/notesLocalRepository';
import { captureOwnerScope } from '@/lib/local/ownerScope';
import { getAttachmentBlobStore } from './attachmentBlobStoreRegistry';
import { ATTACHMENT_PENDING_PREFIX, isPendingAttachment } from './attachmentPaths';
import { releasePendingAttachment } from './pendingAttachmentStore';

/** Durable removal intent survives an offline save or a failed Worker delete. */
export async function retryRemovedAttachmentCleanup(ownerId: string): Promise<void> {
  const scope = captureOwnerScope();
  if (scope.ownerId !== ownerId) return;
  const meta = await getOwnerMeta(ownerId);
  const notes = await listNotes(ownerId);
  for (const [noteId, removed] of Object.entries(meta?.pendingAttachmentRemovals ?? {})) {
    if (!scope.isActive() || meta?.pendingPushes?.[noteId]) continue;
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
          removals[noteId] = (removals[noteId] ?? []).filter(a => a.id !== attachment.id);
          return { ...current, pendingAttachmentRemovals: removals };
        });
      } catch { /* Keep intent for the next push/pull. */ }
    }
  }
}
