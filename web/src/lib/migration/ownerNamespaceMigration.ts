import {
  clearOwner,
  getOwnerMeta,
  listNotes,
  putNotes,
  setOwnerMeta,
} from '@/lib/local/notesLocalRepository';

/**
 * Copies notes + owner meta from a legacy Firebase uid namespace into the Supabase uuid namespace.
 * Idempotent when the target namespace already has notes.
 */
export async function migrateOwnerNamespace(
  fromOwnerId: string,
  toOwnerId: string,
): Promise<boolean> {
  if (fromOwnerId === toOwnerId) return false;

  const existingTarget = await listNotes(toOwnerId);
  if (existingTarget.length > 0) return false;

  const notes = await listNotes(fromOwnerId);
  const sourceMeta = await getOwnerMeta(fromOwnerId);
  if (notes.length === 0 && sourceMeta == null) return false;

  if (notes.length > 0) {
    await putNotes(toOwnerId, notes);
  }

  if (sourceMeta != null) {
    await setOwnerMeta(toOwnerId, {
      firebaseHydrated: sourceMeta.firebaseHydrated,
      hydratedAt: sourceMeta.hydratedAt,
      lastRemoteRevision: sourceMeta.lastRemoteRevision,
      noteRevisions: sourceMeta.noteRevisions,
      firebaseNamespaceMigrated: true,
      migratedFromOwnerId: fromOwnerId,
      migratedAt: Date.now(),
    });
  } else {
    await setOwnerMeta(toOwnerId, {
      firebaseNamespaceMigrated: true,
      migratedFromOwnerId: fromOwnerId,
      migratedAt: Date.now(),
    });
  }

  await clearOwner(fromOwnerId);
  return true;
}
