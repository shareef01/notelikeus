import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { TOMBSTONE_TTL_MS } from '@/lib/firestore/tombstones';

interface TombstoneState {
  /** Note ID → deletedAt millis. Suppresses cloud resurrection until pruned. */
  deletedAtById: Record<string, number>;
  markDeleted: (noteId: string, deletedAt?: number) => void;
  isDeleted: (noteId: string) => boolean;
  mergeFromCloud: (entries: Record<string, number>) => void;
  pruneExpired: (now?: number) => string[];
  clearIds: (noteIds: string[]) => void;
  reset: () => void;
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

export const useTombstoneStore = create<TombstoneState>()(
  persist(
    (set, get) => ({
      deletedAtById: {},
      markDeleted: (noteId, deletedAt = Date.now()) =>
        set((state) => {
          if (state.deletedAtById[noteId] != null) return state;
          return { deletedAtById: { ...state.deletedAtById, [noteId]: deletedAt } };
        }),
      isDeleted: (noteId) => noteId in get().deletedAtById,
      mergeFromCloud: (entries) =>
        set((state) => {
          let changed = false;
          const next = { ...state.deletedAtById };
          for (const [id, deletedAt] of Object.entries(entries)) {
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
        for (const [id, deletedAt] of Object.entries(next)) {
          if (now - deletedAt >= TOMBSTONE_TTL_MS) {
            delete next[id];
            expired.push(id);
          }
        }
        if (expired.length > 0) set({ deletedAtById: next });
        return expired;
      },
      clearIds: (noteIds) =>
        set((state) => {
          if (noteIds.length === 0) return state;
          const next = { ...state.deletedAtById };
          let changed = false;
          for (const id of noteIds) {
            if (id in next) {
              delete next[id];
              changed = true;
            }
          }
          return changed ? { deletedAtById: next } : state;
        }),
      reset: () => set({ deletedAtById: {} }),
    }),
    {
      name: 'notelikeus-deleted-notes',
      skipHydration: true,
      partialize: (state) => ({ deletedAtById: state.deletedAtById }),
      merge: (persisted, current) => {
        const raw = persisted as { deletedAtById?: unknown; deletedIds?: unknown } | undefined;
        const fromNew = normalizeDeletedMap(raw?.deletedAtById);
        const fromLegacy = normalizeDeletedMap(raw?.deletedIds);
        return {
          ...current,
          deletedAtById: { ...fromLegacy, ...fromNew },
        };
      },
    },
  ),
);
