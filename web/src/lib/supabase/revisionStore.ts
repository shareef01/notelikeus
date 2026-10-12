import {
  getOwnerMeta,
  mergeNoteRevisions,
  updateOwnerMeta,
} from '@/lib/local/notesLocalRepository';

export interface OwnerRevisionState {
  lastRemoteRevision: number;
  noteRevisions: Record<string, number>;
  knownCloudIds: string[];
}

const EMPTY: OwnerRevisionState = { lastRemoteRevision: 0, noteRevisions: {}, knownCloudIds: [] };

function normalizeKnownCloudIds(raw: unknown): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((id): id is string => typeof id === 'string' && id.length > 0);
}

export async function loadRevisionState(ownerId: string): Promise<OwnerRevisionState> {
  const meta = await getOwnerMeta(ownerId);
  return {
    lastRemoteRevision: meta?.lastRemoteRevision ?? 0,
    noteRevisions: meta?.noteRevisions ?? {},
    knownCloudIds: normalizeKnownCloudIds(meta?.knownCloudIds),
  };
}

/**
 * Records what a full snapshot said, as one transaction against the stored state.
 *
 * [patch.noteRevisions] is the snapshot's complete map and [patch.lastRemoteRevision] the revision
 * it was read at: revisions recorded after that point — a push that landed while the snapshot was
 * in flight — survive it (F-03). The cursor never moves backwards.
 */
export async function saveRevisionState(
  ownerId: string,
  patch: Partial<OwnerRevisionState>,
): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) => {
    const cursor = meta.lastRemoteRevision ?? 0;
    const horizon = patch.lastRemoteRevision ?? cursor;
    return {
      ...meta,
      lastRemoteRevision: Math.max(cursor, horizon),
      noteRevisions: patch.noteRevisions
        ? mergeNoteRevisions(meta.noteRevisions ?? {}, patch.noteRevisions, { replace: true, horizon })
        : meta.noteRevisions,
      knownCloudIds: patch.knownCloudIds ?? meta.knownCloudIds,
    };
  });
}

/**
 * Records the revision the cloud gave [noteId] — never lowering one already known.
 *
 * Deliberately leaves the pull cursor alone (F-04). Revisions come from one global sequence, so
 * another device can have committed a change below this one that has not been pulled yet; moving
 * the cursor here would skip it.
 */
export async function rememberNoteRevision(
  ownerId: string,
  noteId: string,
  revision: number,
): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) => {
    const known = meta.noteRevisions?.[noteId];
    if (known != null && known >= revision) return null;
    return { ...meta, noteRevisions: { ...meta.noteRevisions, [noteId]: revision } };
  });
}

export async function forgetNoteRevision(ownerId: string, noteId: string): Promise<void> {
  await updateOwnerMeta(ownerId, (meta) => {
    if (!meta.noteRevisions || !(noteId in meta.noteRevisions)) return null;
    const { [noteId]: _removed, ...noteRevisions } = meta.noteRevisions;
    return { ...meta, noteRevisions };
  });
}

export function getNoteBaseRevision(state: OwnerRevisionState, noteId: string): number | null {
  return state.noteRevisions[noteId] ?? null;
}

export { EMPTY as emptyRevisionState };
