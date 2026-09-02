import { attachmentWorkerPath, parseAttachmentObjectKey } from '@/lib/attachments/attachmentObjectKey';
import { loadAttachmentsWorkerUrl } from '@/lib/attachments/attachmentConfig';
import { getSupabaseClient } from '@/lib/supabase/client';
import { ensureSupabaseAuthenticated } from '@/lib/supabase/supabaseSyncEngine';

interface DeleteAllUserCloudDataResult {
  status?: string;
  notes_deleted?: number;
  tombstones_deleted?: number;
  attachments_deleted?: number;
  attachment_object_keys?: string[];
}

async function deleteR2BlobsBestEffort(objectKeys: string[]): Promise<void> {
  if (objectKeys.length === 0) return;
  const workerUrl = loadAttachmentsWorkerUrl().replace(/\/$/, '');
  if (!workerUrl) return;

  const { data } = await getSupabaseClient().auth.getSession();
  const token = data.session?.access_token?.trim();
  if (!token) return;

  await Promise.all(
    objectKeys.map(async (objectKey) => {
      const parsed = parseAttachmentObjectKey(objectKey);
      if (!parsed) return;
      try {
        await fetch(`${workerUrl}${attachmentWorkerPath(parsed.noteId, parsed.attachmentId)}`, {
          method: 'DELETE',
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        // Orphaned R2 objects are acceptable after a successful Postgres wipe.
      }
    }),
  );
}

/**
 * Wipes the signed-in user's Supabase notes, tombstones, and attachment metadata.
 * Best-effort deletes matching R2 blobs while the session is still valid.
 * Throws if the RPC fails — callers must not sign out after a failed wipe.
 */
export async function deleteAllSupabaseCloudData(): Promise<number> {
  await ensureSupabaseAuthenticated();
  const { data, error } = await getSupabaseClient().rpc('delete_all_user_cloud_data');
  if (error) throw error;
  const result = (data ?? {}) as DeleteAllUserCloudDataResult;
  await deleteR2BlobsBestEffort(result.attachment_object_keys ?? []);
  return result.notes_deleted ?? 0;
}
