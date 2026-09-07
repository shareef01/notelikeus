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

/** Raised when the notes were wiped but some attachment bytes are still in R2. */
export class CloudWipeIncompleteError extends Error {
  readonly remainingObjects: number;

  constructor(remainingObjects: number) {
    super(
      `Cloud notes were deleted, but ${remainingObjects} attachment ` +
        `${remainingObjects === 1 ? 'file is' : 'files are'} still stored. ` +
        'Try deleting cloud data again.',
    );
    this.name = 'CloudWipeIncompleteError';
    this.remainingObjects = remainingObjects;
  }
}

/**
 * Deletes every R2 object the wipe listed, and reports how many did not go.
 *
 * Every response is checked. This used to fire the requests and ignore both their status and
 * their rejections, which meant a wipe that deleted nothing at all still looked like a success.
 */
async function deleteR2Objects(objectKeys: string[], token: string, workerUrl: string) {
  const results = await Promise.all(
    objectKeys.map(async (objectKey) => {
      const parsed = parseAttachmentObjectKey(objectKey);
      // An unparseable key cannot be turned into a request, so it is a failure to delete it,
      // not something to skip quietly.
      if (!parsed) return false;
      try {
        const response = await fetch(
          `${workerUrl}${attachmentWorkerPath(parsed.noteId, parsed.attachmentId)}`,
          { method: 'DELETE', headers: { Authorization: `Bearer ${token}` } },
        );
        // 404 means the row is already gone from the server's point of view — there is nothing
        // left for this client to delete, and re-running would not change that.
        return response.ok || response.status === 404;
      } catch {
        return false;
      }
    }),
  );
  return results.filter((deleted) => !deleted).length;
}

/**
 * Wipes the signed-in user's Supabase notes, tombstones, and attachment metadata, then the
 * attachment bytes behind them.
 *
 * Two phases on purpose. The RPC soft-deletes attachment metadata and marks it for purge rather
 * than destroying it, because the Worker authorizes each DELETE against that row — destroying it
 * first is what previously made every object deletion fail. Once the objects are gone the second
 * RPC drops the rows. A client that dies in between leaves marked rows that the orphan sweeper
 * finishes, and re-running the wipe simply re-reads whatever is still marked.
 *
 * Throws if the RPC fails, and throws [CloudWipeIncompleteError] if any object survived — callers
 * must not report success, and must not sign out, after a wipe that left bytes behind.
 */
export async function deleteAllSupabaseCloudData(): Promise<number> {
  await ensureSupabaseAuthenticated();
  const { data, error } = await getSupabaseClient().rpc('delete_all_user_cloud_data');
  if (error) throw error;
  const result = (data ?? {}) as DeleteAllUserCloudDataResult;
  const objectKeys = result.attachment_object_keys ?? [];
  const notesDeleted = result.notes_deleted ?? 0;

  if (objectKeys.length === 0) {
    await purgeWipedAttachmentMetadata();
    return notesDeleted;
  }

  const workerUrl = loadAttachmentsWorkerUrl().replace(/\/$/, '');
  const { data: sessionData } = await getSupabaseClient().auth.getSession();
  const token = sessionData.session?.access_token?.trim();
  // Without a Worker or a session there is no way to reach the objects at all. Reporting success
  // here is exactly the lie this function exists to avoid.
  if (!workerUrl || !token) throw new CloudWipeIncompleteError(objectKeys.length);

  const failed = await deleteR2Objects(objectKeys, token, workerUrl);
  if (failed > 0) throw new CloudWipeIncompleteError(failed);

  await purgeWipedAttachmentMetadata();
  return notesDeleted;
}

/**
 * Drops the metadata rows the wipe marked, now that their objects are gone.
 *
 * Best effort by design: the rows are already invisible to the user, and the orphan sweeper
 * removes them if this never lands. Failing the whole wipe over it would tell the user their
 * data survived when it did not.
 */
async function purgeWipedAttachmentMetadata(): Promise<void> {
  try {
    await getSupabaseClient().rpc('finalize_cloud_wipe');
  } catch {
    // Left for the sweeper.
  }
}
