import { getOwnerMeta, setOwnerMeta } from '@/lib/local/notesLocalRepository';

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

export async function saveRevisionState(
  ownerId: string,
  patch: Partial<OwnerRevisionState>,
): Promise<void> {
  const current = await loadRevisionState(ownerId);
  const nextCursor = Math.max(
    current.lastRemoteRevision,
    patch.lastRemoteRevision ?? current.lastRemoteRevision,
  );
  await setOwnerMeta(ownerId, {
    lastRemoteRevision: nextCursor,
    noteRevisions: patch.noteRevisions ?? current.noteRevisions,
    knownCloudIds: patch.knownCloudIds ?? current.knownCloudIds,
  });
}

export async function rememberNoteRevision(
  ownerId: string,
  noteId: string,
  revision: number,
  lastRemoteRevision?: number,
): Promise<void> {
  const current = await loadRevisionState(ownerId);
  const noteRevisions = { ...current.noteRevisions, [noteId]: revision };
  await saveRevisionState(ownerId, {
    noteRevisions,
    lastRemoteRevision: Math.max(
      current.lastRemoteRevision,
      lastRemoteRevision ?? revision,
      revision,
    ),
  });
}

export async function forgetNoteRevision(ownerId: string, noteId: string): Promise<void> {
  const current = await loadRevisionState(ownerId);
  if (!(noteId in current.noteRevisions)) return;
  const { [noteId]: _removed, ...noteRevisions } = current.noteRevisions;
  await saveRevisionState(ownerId, { noteRevisions });
}

export function getNoteBaseRevision(state: OwnerRevisionState, noteId: string): number | null {
  return state.noteRevisions[noteId] ?? null;
}

export { EMPTY as emptyRevisionState };
