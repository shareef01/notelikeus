import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { TOMBSTONE_TTL_MS } from '@/lib/notes/tombstones';

interface TombstoneState {
  /** Note ID → deletedAt millis. Suppresses cloud resurrection until pruned. */
  deletedAtById: Record<string, number>;
  /** Note ID → immutable known server revision at the exact moment of deletion. */
  baselineRevisionById: Record<string, number>;
  /**
   * Notes brought back by an explicit restore. Survives process death so a cloud
   * tombstone is not re-imported, and so a successful server restore can clear
   * leftover local suppression after crash.
   */
  restoredIds: string[];
  /**
   * Attachment ids to GC after a successful server note delete. Survives process
   * death so R2 cleanup can retry without deleting blobs before the note is gone.
   */
  pendingAttachmentGcByNoteId: Record<string, string[]>;
  markDeleted: (noteId: string, baselineRevision?: number | null, deletedAt?: number) => void;
  isDeleted: (noteId: string) => boolean;
  markRestored: (noteId: string) => void;
  isRestored: (noteId: string) => boolean;
  clearRestored: (noteIds: string[]) => void;
  /** Server has a live note: restore is confirmed. */
  acknowledgeRestoredLiveNotes: (liveNoteIds: string[]) => void;
  mergeFromCloud: (entries: Record<string, number>) => void;
  pruneExpired: (now?: number) => string[];
  clearIds: (noteIds: string[]) => void;
  markPendingAttachmentGc: (noteId: string, attachmentIds: string[]) => void;
  clearPendingAttachmentGc: (noteId: string) => void;
  pendingAttachmentGcEntries: () => Array<[string, string[]]>;
  reset: () => void;
}

function dropPendingGc(
  pending: Record<string, string[]>,
  noteIds: string[],
): Record<string, string[]> {
  if (noteIds.length === 0) return pending;
  let changed = false;
  const next = { ...pending };
  for (const id of noteIds) {
    if (id in next) {
      delete next[id];
      changed = true;
    }
  }
  return changed ? next : pending;
}

function normalizePendingGc(raw: unknown): Record<string, string[]> {
  if (!raw || typeof raw !== 'object') return {};
  const result: Record<string, string[]> = {};
  for (const [id, value] of Object.entries(raw as Record<string, unknown>)) {
    if (!Array.isArray(value)) continue;
    const ids = value.filter((item): item is string => typeof item === 'string');
    if (ids.length > 0) result[id] = ids;
  }
  return result;
}

function normalizeDeletedMap(raw: unknown): Record<string, number> {
  if (!raw || typeof raw !== 'object') return {};
  const result: Record<string, number> = {};
  for (const [id, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      result[id] = value;
    } else if (value === true) {
      // Legacy boolean tombstones from before deletedAt timestamps.
      result[id] = Date.now();
    }
  }
  return result;
}

function normalizeNumberMap(raw: unknown): Record<string, number> {
  if (!raw || typeof raw !== 'object') return {};
  const result: Record<string, number> = {};
  for (const [id, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value === 'number' && Number.isInteger(value) && value > 0) {
      result[id] = value;
    }
  }
  return result;
}

export const useTombstoneStore = create<TombstoneState>()(
  persist(
    (set, get) => ({
      deletedAtById: {},
      baselineRevisionById: {},
      restoredIds: [],
      pendingAttachmentGcByNoteId: {},
      markDeleted: (noteId, baselineRevision = null, deletedAt = Date.now()) =>
        set((state) => {
          if (state.deletedAtById[noteId] !== undefined) return state; // keeps first
          const nextRestored = state.restoredIds.filter((id) => id !== noteId);
          const nextDeleted = { ...state.deletedAtById };
          const nextBaselines = { ...state.baselineRevisionById };
          nextDeleted[noteId] = deletedAt;
          if (baselineRevision != null) {
            nextBaselines[noteId] = baselineRevision;
          }
          return {
            deletedAtById: nextDeleted,
            baselineRevisionById: nextBaselines,
            restoredIds: nextRestored,
          };
        }),
      isDeleted: (noteId) => noteId in get().deletedAtById,
      markRestored: (noteId) =>
        set((state) => {
          const pending = dropPendingGc(state.pendingAttachmentGcByNoteId, [noteId]);
          const already = state.restoredIds.includes(noteId);
          if (already && pending === state.pendingAttachmentGcByNoteId) return state;
          return {
            restoredIds: already ? state.restoredIds : [...state.restoredIds, noteId],
            pendingAttachmentGcByNoteId: pending,
          };
        }),
      isRestored: (noteId) => get().restoredIds.includes(noteId),
      clearRestored: (noteIds) =>
        set((state) => {
          const drop = new Set(noteIds);
          const next = state.restoredIds.filter((id) => !drop.has(id));
          return next.length === state.restoredIds.length ? state : { restoredIds: next };
        }),
      acknowledgeRestoredLiveNotes: (liveNoteIds) => {
        const live = new Set(liveNoteIds);
        const confirmed = get().restoredIds.filter((id) => live.has(id));
        if (confirmed.length === 0) return;
        get().clearIds(confirmed);
        get().clearRestored(confirmed);
        set((state) => ({
          pendingAttachmentGcByNoteId: dropPendingGc(
            state.pendingAttachmentGcByNoteId,
            confirmed,
          ),
        }));
      },
      mergeFromCloud: (entries) =>
        set((state) => {
          let changed = false;
          const next = { ...state.deletedAtById };
          const restored = new Set(state.restoredIds);
          for (const [id, deletedAt] of Object.entries(entries)) {
            if (restored.has(id)) continue;
            const existing = next[id];
            if (existing == null) {
              next[id] = deletedAt;
              changed = true;
            } else if (deletedAt < existing) {
              next[id] = deletedAt;
              changed = true;
            }
          }
          return changed ? { deletedAtById: next } : state;
        }),
      pruneExpired: (now = Date.now()) => {
        const expired: string[] = [];
        const next = { ...get().deletedAtById };
        const nextBaselines = { ...get().baselineRevisionById };
        for (const [id, deletedAt] of Object.entries(next)) {
          if (now - deletedAt >= TOMBSTONE_TTL_MS) {
            delete next[id];
            delete nextBaselines[id];
            expired.push(id);
          }
        }
        if (expired.length > 0) {
          set({ deletedAtById: next, baselineRevisionById: nextBaselines });
        }
        return expired;
      },
      clearIds: (noteIds) =>
        set((state) => {
          if (noteIds.length === 0) return state;
          const next = { ...state.deletedAtById };
          const nextBaselines = { ...state.baselineRevisionById };
          let changed = false;
          for (const id of noteIds) {
            if (id in next || id in nextBaselines) {
              delete next[id];
              delete nextBaselines[id];
              changed = true;
            }
          }
          return changed ? { deletedAtById: next, baselineRevisionById: nextBaselines } : state;
        }),
      markPendingAttachmentGc: (noteId, attachmentIds) =>
        set((state) => ({
          pendingAttachmentGcByNoteId: {
            ...state.pendingAttachmentGcByNoteId,
            [noteId]: [...new Set([
              ...(state.pendingAttachmentGcByNoteId[noteId] ?? []),
              ...attachmentIds,
            ])],
          },
        })),
      clearPendingAttachmentGc: (noteId) =>
        set((state) => ({
          pendingAttachmentGcByNoteId: dropPendingGc(
            state.pendingAttachmentGcByNoteId,
            [noteId],
          ),
        })),
      pendingAttachmentGcEntries: () =>
        Object.entries(get().pendingAttachmentGcByNoteId),
      reset: () => set({ deletedAtById: {}, baselineRevisionById: {}, restoredIds: [], pendingAttachmentGcByNoteId: {} }),
    }),
    {
      name: 'notelikeus-deleted-notes',
      skipHydration: true,
      partialize: (state) => ({
        deletedAtById: state.deletedAtById,
        baselineRevisionById: state.baselineRevisionById,
        restoredIds: state.restoredIds,
        pendingAttachmentGcByNoteId: state.pendingAttachmentGcByNoteId,
      }),
      merge: (persisted, current) => {
        const raw = persisted as {
          deletedAtById?: unknown;
          baselineRevisionById?: unknown;
          deletedIds?: unknown;
          restoredIds?: unknown;
          pendingAttachmentGcByNoteId?: unknown;
        } | undefined;
        const fromNew = normalizeDeletedMap(raw?.deletedAtById);
        const baselines = normalizeNumberMap(raw?.baselineRevisionById);
        const fromLegacy = normalizeDeletedMap(raw?.deletedIds);
        const restoredIds = Array.isArray(raw?.restoredIds)
          ? raw.restoredIds.filter((id): id is string => typeof id === 'string')
          : [];
        return {
          ...current,
          deletedAtById: { ...fromLegacy, ...fromNew },
          baselineRevisionById: baselines,
          restoredIds,
          pendingAttachmentGcByNoteId: normalizePendingGc(raw?.pendingAttachmentGcByNoteId),
        };
      },
    },
  ),
);
