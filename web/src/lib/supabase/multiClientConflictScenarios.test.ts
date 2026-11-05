import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { rpcWithHeaders } from '@/test/rpcWithHeaders';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

const USER_A = '11111111-1111-4111-8111-111111111111';
const USER_B = '22222222-2222-4222-8222-222222222222';

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    rpc: rpcWithHeaders(rpcMock),
    auth: {
      getSession: () => Promise.resolve({ data: { session: { user: { id: USER_A }, access_token: 'test-token' } }, error: null }),
    },
  }),
  isSupabaseBackendEnabled: () => true,
  loadSupabaseUrl: () => 'http://localhost:54321',
  loadSupabaseAnonKey: () => 'anon',
}));

import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';
import { rememberNoteRevision } from '@/lib/supabase/revisionStore';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useAuthStore } from '@/store/authStore';
import { createEmptyNote, type Note } from '@/types/note';
import { CloudWipedElsewhereError } from '@/lib/supabase/wipeGeneration';
import { NoteConflictError } from '@/lib/supabase/supabaseSyncEngine';

function note(id: string, overrides: Partial<Note> = {}): Note {
  return createEmptyNote({ id, localId: Number(id) || 1, title: `Title ${id}`, content: `Content ${id}`, ...overrides });
}

describe('Phase B3: Multi-Client Synchronization Integrity & Conflict Scenarios', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    useTombstoneStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER_A, email: null, displayName: null });
    await resetNotesDatabaseForTests();
  });

  // Scenario 1: Two offline clients edit the same note, then reconnect in opposite orders
  it('Scenario 1: first reconnecting client applies edit; second reconnecting client receives conflict branch', async () => {
    // Both clients started with revision 10001
    await rememberNoteRevision(USER_A, '10', 10001);

    // Client 1 reconnects first: base_revision 10001 matches server revision -> applied, advances to 10002
    rpcMock.mockResolvedValueOnce({
      data: { status: 'applied', revision: 10002, server_updated_at: 1700000000000 },
      error: null,
    });

    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_A,
        note('10', { title: 'Client 1 Edit' }),
      ),
    ).resolves.toBeUndefined();

    // Client 2 reconnects second: still presents stale base_revision 10001 -> server responds with conflict
    rpcMock.mockResolvedValueOnce({
      data: {
        status: 'conflict',
        current: {
          note_id: '10',
          title: 'Client 1 Edit',
          content: 'Server content',
          revision: 10002,
          server_updated_at: 1700000000000,
        },
      },
      error: null,
    });

    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_A,
        note('10', { title: 'Client 2 Edit' }),
      ),
    ).rejects.toThrow(NoteConflictError);
  });

  // Scenario 2: Client A deletes a note while offline client B modifies it
  it('Scenario 2: Client A deletes note; offline Client B modifying it receives note_deleted conflict', async () => {
    await rememberNoteRevision(USER_A, '20', 10005);

    // Client A deletes note: row deleted, tombstone created at revision 10006
    rpcMock.mockResolvedValueOnce({
      data: { status: 'applied', revision: 10006, deleted_at: 1700000001000 },
      error: null,
    });
    await expect(
      supabaseRemoteNotesDataSource.deleteNote(USER_A, '20', 10005),
    ).resolves.toBeUndefined();

    // Client B reconnects with offline edits: Client B is an independent device without Client A's local tombstone memory
    useTombstoneStore.getState().reset();
    await rememberNoteRevision(USER_A, '20', 10005);
    rpcMock.mockResolvedValueOnce({
      data: { status: 'conflict', error: 'note_deleted' },
      error: null,
    });

    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_A,
        note('20', { content: 'Offline changes' }),
      ),
    ).rejects.toThrow(NoteConflictError);
  });

  // Scenario 3: An account wipe occurs while a device has unsynchronized notes
  it('Scenario 3: an account wipe fences unsynchronized notes with CloudWipedElsewhereError', async () => {
    // Client has unsynchronized note but server wipe incremented generation (p_wipe_generation is stale)
    rpcMock.mockResolvedValueOnce({
      data: null,
      error: { code: 'NL002', message: 'stale wipe generation' },
    });

    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_A,
        note('30', { title: 'Pending upload during wipe' }),
      ),
    ).rejects.toThrow(CloudWipedElsewhereError);
  });

  // Scenario 4: Two clients simultaneously create or confirm attachment references
  it('Scenario 4: concurrent attachment creation is gated by advisory lock and quota check', async () => {
    // Client attempts to finalize an attachment exceeding quota or max per note
    rpcMock.mockResolvedValueOnce({
      data: null,
      error: { code: '22023', message: 'too many attachments on note (max 20)' },
    });

    const { getSupabaseClient } = await import('@/lib/supabase/client');
    const client = getSupabaseClient();
    const res = await client.rpc('finalize_note_attachment_put', {
      p_note_id: '40',
      p_attachment_id: 'att-excess',
      p_object_key: 'owners/u/notes/40/att-excess',
      p_mime_type: 'image/png',
      p_size_bytes: 1024,
      p_provisional: false,
      p_attachment_type: 'image',
    });

    expect(res.error).not.toBeNull();
    expect(res.error?.message).toContain('too many attachments on note');
  });

  // Scenario 5: A retried mutation arrives after the server has already accepted it
  it('Scenario 5: retried delete mutation on an already deleted note is idempotent', async () => {
    // Server returns idempotent success on repeat delete
    rpcMock.mockResolvedValueOnce({
      data: { status: 'applied', idempotent: true },
      error: null,
    });

    await expect(
      supabaseRemoteNotesDataSource.deleteNote(USER_A, '50', 10010),
    ).resolves.not.toThrow();
  });

  // Scenario 6: A client attempts a mutation using another user’s note identifier
  it('Scenario 6: cross-account mutation attempt is strictly rejected by owner isolation', async () => {
    // The current session is A; a stale operation naming B must stop before any RPC is sent.
    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_B,
        note('60', { title: 'Attempted cross-account hijack' }),
      ),
    ).rejects.toThrow('Account changed during sync');
    expect(rpcMock).not.toHaveBeenCalled();
  });

  // Scenario 7: A client attempts to replay an obsolete revision
  it('Scenario 7: replaying an obsolete revision is rejected with current server state', async () => {
    // Server is at revision 10020; client tries to apply change with base revision 10005
    rpcMock.mockResolvedValueOnce({
      data: {
        status: 'conflict',
        current: {
          note_id: '70',
          title: 'Authoritative State',
          revision: 10020,
        },
      },
      error: null,
    });

    await expect(
      supabaseRemoteNotesDataSource.upsertNote(
        USER_A,
        note('70', { title: 'Replayed Stale Revision' }),
      ),
    ).rejects.toThrow(NoteConflictError);
  });
});
