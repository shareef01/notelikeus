import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  getRemoteNotesDataSource,
  resetRemoteNotesDataSourceForTests,
  setRemoteNotesDataSourceForTests,
} from '@/lib/remote/remoteNotesDataSourceRegistry';
import type { RemoteNotesDataSource } from '@/lib/remote/remoteNotesDataSource';

describe('remoteNotesDataSourceRegistry', () => {
  afterEach(() => {
    resetRemoteNotesDataSourceForTests();
  });

  it('returns a remote notes source with the sync contract', () => {
    const source = getRemoteNotesDataSource();
    expect(typeof source.fetchAllNotes).toBe('function');
    expect(typeof source.subscribeToNotes).toBe('function');
    expect(typeof source.upsertNote).toBe('function');
    expect(typeof source.deleteNote).toBe('function');
    expect(typeof source.uploadAllNotes).toBe('function');
    expect(typeof source.syncNotesWithCloud).toBe('function');
  });

  it('honours test overrides', () => {
    const stub = {
      fetchAllNotes: vi.fn(),
    } as unknown as RemoteNotesDataSource;
    setRemoteNotesDataSourceForTests(stub);
    expect(getRemoteNotesDataSource()).toBe(stub);
  });
});
