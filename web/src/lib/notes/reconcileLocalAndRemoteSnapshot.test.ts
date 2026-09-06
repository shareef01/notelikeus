import { noteSyncPayloadEqual } from '@/lib/notes/noteEquality';
import { SuspiciousEmptySnapshotError } from '@/lib/remote/remoteErrors';
import { reconcileLocalAndRemoteSnapshot } from '@/lib/notes/reconcileLocalAndRemoteSnapshot';
import { createEmptyNote, type Note } from '@/types/note';
import { describe, expect, it } from 'vitest';

function note(
  id: string,
  partial: Partial<Note> = {},
): Note {
  return createEmptyNote({
    id,
    localId: Number(id) || 1,
    title: `Note ${id}`,
    ...partial,
  });
}

describe('reconcileLocalAndRemoteSnapshot', () => {
  it('keeps local-only unsynced A when remote has B, and marks A for upload', () => {
    const localA = note('1', { title: 'Local A', serverUpdatedAt: null });
    const remoteB = note('2', { title: 'Remote B', serverUpdatedAt: 100 });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [localA],
      remoteNotes: [remoteB],
      remoteTombstones: {},
      knownRemoteIds: new Set(['2']),
      isDeleted: () => false,
    });

    expect(result.merged.map((entry) => entry.id).sort()).toEqual(['1', '2']);
    expect(result.toUpload.map((entry) => entry.id)).toEqual(['1']);
    expect(result.newlyDeletedIds).toEqual([]);
  });

  it('deletes a note that the cloud tombstoned and does not keep it in the merged set', () => {
    const localA = note('1', { title: 'A', serverUpdatedAt: 50 });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [localA],
      remoteNotes: [],
      remoteTombstones: { '1': 99 },
      knownRemoteIds: new Set(['1']),
      isDeleted: () => false,
    });

    expect(result.merged).toEqual([]);
    expect(result.newlyDeletedIds).toContain('1');
  });

  it('treats a known-remote id missing from a non-empty snapshot as a remote delete', () => {
    const localA = note('1', { title: 'A', serverUpdatedAt: 50 });
    const remoteB = note('2', { title: 'B', serverUpdatedAt: 80 });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [localA],
      remoteNotes: [remoteB],
      remoteTombstones: {},
      knownRemoteIds: new Set(['1', '2']),
      isDeleted: () => false,
    });

    expect(result.merged.map((entry) => entry.id)).toEqual(['2']);
    expect(result.newlyDeletedIds).toContain('1');
  });

  it('never resurrects a tombstoned id even if a stale remote copy is present', () => {
    const stale = note('1', { title: 'Stale cloud A', serverUpdatedAt: 10 });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [],
      remoteNotes: [stale],
      remoteTombstones: { '1': 1 },
      knownRemoteIds: new Set(),
      isDeleted: (id) => id === '1',
    });

    expect(result.merged).toEqual([]);
  });

  it('throws on a suspicious empty fetch when live previously-remote notes remain', () => {
    const localA = note('1', { title: 'Keep me', serverUpdatedAt: 50 });

    expect(() =>
      reconcileLocalAndRemoteSnapshot({
        localNotes: [localA],
        remoteNotes: [],
        remoteTombstones: {},
        knownRemoteIds: new Set(['1']),
        isDeleted: () => false,
      }),
    ).toThrow(SuspiciousEmptySnapshotError);
  });

  it('allows a legitimate empty library after the last note is tombstoned', () => {
    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [note('1', { serverUpdatedAt: 50 })],
      remoteNotes: [],
      remoteTombstones: { '1': 9 },
      knownRemoteIds: new Set(['1']),
      isDeleted: (id) => id === '1',
    });

    expect(result.merged).toEqual([]);
  });

  it('keeps migration/import local notes when the first snapshot is empty and unknown', () => {
    const imported = note('9', { title: 'Imported', serverUpdatedAt: null });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [imported],
      remoteNotes: [],
      remoteTombstones: {},
      knownRemoteIds: new Set(),
      isDeleted: () => false,
    });

    expect(result.merged).toHaveLength(1);
    expect(result.merged[0]?.id).toBe('9');
    expect(result.toUpload.map((entry) => entry.id)).toEqual(['9']);
  });

  it('does not mix owners: only the provided local/remote arrays participate', () => {
    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [note('1', { title: 'Owner A' })],
      remoteNotes: [note('2', { title: 'Owner A remote', serverUpdatedAt: 1 })],
      remoteTombstones: {},
      knownRemoteIds: new Set(['2']),
      isDeleted: () => false,
    });
    expect(result.merged.map((entry) => entry.title).sort()).toEqual([
      'Owner A',
      'Owner A remote',
    ]);
  });

  it('uploads a local mutation based on the same revision even when the client clock is behind', () => {
    const local = note('1', {
      title: 'Clock-skewed edit',
      timestamp: 10,
      serverUpdatedAt: 500,
    });
    const remote = note('1', {
      title: 'Previous',
      timestamp: 999_999,
      serverUpdatedAt: 500,
    });

    const result = reconcileLocalAndRemoteSnapshot({
      localNotes: [local],
      remoteNotes: [remote],
      remoteTombstones: {},
      knownRemoteIds: new Set(['1']),
      isDeleted: () => false,
    });

    expect(result.merged[0]?.title).toBe('Clock-skewed edit');
    expect(result.toUpload).toHaveLength(1);
    expect(noteSyncPayloadEqual(local, remote)).toBe(false);
  });
});
