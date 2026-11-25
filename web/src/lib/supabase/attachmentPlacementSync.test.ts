import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { rpcWithHeaders } from '@/test/rpcWithHeaders';

const rpcCalls: Array<{ name: string; args: Record<string, unknown> }> = [];
let rpcImpl: (name: string, args: Record<string, unknown>) => Promise<{ data: unknown; error: unknown }> =
  async () => ({ data: { status: 'applied', revision: 3, server_updated_at: 2000 }, error: null });

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({
    rpc: rpcWithHeaders(async (name: string, args: Record<string, unknown> = {}) => {
      rpcCalls.push({ name, args });
      return rpcImpl(name, args);
    }),
    auth: {
      getSession: async () => ({
        data: { session: { user: { id: USER }, access_token: 'test-token' } },
        error: null,
      }),
    },
  }),
  isSupabaseBackendEnabled: () => true,
}));

import { bodyDigest } from '@/lib/attachments/attachmentPlacement';
import { mergeAttachmentsIntoNotes } from '@/lib/attachments/attachmentSyncService';
import { listUserAttachments } from '@/lib/attachments/supabaseAttachmentMetadata';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { applyNoteChange } from '@/lib/supabase/supabaseSyncEngine';
import { useAuthStore } from '@/store/authStore';
import { createEmptyNote, type Note } from '@/types/note';

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';

function noteWithImage(overrides: Partial<Note> = {}): Note {
  const content = 'Before 🧭\n\nAfter';
  const digest = bodyDigest(content);
  return {
    ...createEmptyNote({ id: '42', localId: 42, title: 'Image note' }),
    content,
    attachments: [
      {
        id: 'att-1',
        noteId: 42,
        storagePath: `r2:owners/${USER}/notes/42/att-1`,
        type: 'image',
        mimeType: 'image/png',
        sizeBytes: 3,
        placement: { bodySha256: digest, offset: 10, ordinal: 0 },
      },
    ],
    ...overrides,
  };
}

describe('signed-in attachment placement sync', () => {
  beforeEach(async () => {
    rpcCalls.length = 0;
    rpcImpl = async () => ({ data: { status: 'applied', revision: 3, server_updated_at: 2000 }, error: null });
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
    await resetNotesDatabaseForTests();
  });

  it('commits valid remote image placements via apply_note_change_with_placements and omits stale/pending ones', async () => {
    const base = noteWithImage();
    const note: Note = {
      ...base,
      attachments: [
        ...base.attachments,
        {
          id: 'att-stale',
          noteId: 42,
          storagePath: `r2:owners/${USER}/notes/42/att-stale`,
          type: 'image',
          mimeType: 'image/png',
          sizeBytes: 3,
          placement: { bodySha256: '0'.repeat(64), offset: 10, ordinal: 1 },
        },
        {
          id: 'att-pending',
          noteId: 42,
          storagePath: 'pending:att-pending',
          type: 'image',
          mimeType: 'image/png',
          sizeBytes: 3,
          placement: { bodySha256: bodyDigest(base.content), offset: 10, ordinal: 2 },
        },
      ],
    };

    await applyNoteChange(USER, note, 2);

    expect(rpcCalls).toHaveLength(1);
    expect(rpcCalls[0]?.name).toBe('apply_note_change_with_placements');
    expect(rpcCalls[0]?.args.p_attachment_ids).toEqual(['att-1', 'att-stale']);
    expect(rpcCalls[0]?.args.p_attachment_placements).toEqual([
      { attachmentId: 'att-1', bodySha256: bodyDigest(base.content), offset: 10, ordinal: 0 },
    ]);
  });

  it('sends an empty placement array when all remote images are unplaced', async () => {
    const base = noteWithImage();
    const unplaced: Note = {
      ...base,
      attachments: [{ ...base.attachments[0]!, placement: undefined }],
    };

    await applyNoteChange(USER, unplaced, 2);

    expect(rpcCalls).toHaveLength(1);
    expect(rpcCalls[0]?.name).toBe('apply_note_change_with_placements');
    expect(rpcCalls[0]?.args.p_attachment_placements).toEqual([]);
  });

  it('falls back to apply_note_change when an older server returns PGRST202', async () => {
    rpcImpl = async (name) => {
      if (name === 'apply_note_change_with_placements') {
        return { data: null, error: { code: 'PGRST202', message: 'Could not find the function' } };
      }
      return { data: { status: 'applied', revision: 4, server_updated_at: 2100 }, error: null };
    };

    await applyNoteChange(USER, noteWithImage(), 2);

    expect(rpcCalls.map((call) => call.name)).toEqual([
      'apply_note_change_with_placements',
      'apply_note_change',
    ]);
    expect('p_attachment_placements' in (rpcCalls[1]?.args ?? {})).toBe(false);
  });

  it('hydrates remote placements, clears removed ones when synced, and preserves unpushed local edits', async () => {
    const content = 'Before 🧭\n\nAfter';
    const digest = bodyDigest(content);
    const remotePlacement = { bodySha256: digest, offset: 10, ordinal: 0 };

    rpcImpl = async (name) => {
      if (name === 'list_user_attachments') {
        return {
          data: [
            {
              attachment_id: 'att-1',
              note_id: '42',
              object_key: `owners/${USER}/notes/42/att-1`,
              mime_type: 'image/png',
              size_bytes: 3,
              attachment_type: 'image',
              created_at: 1000,
              placement: remotePlacement,
            },
            {
              attachment_id: 'att-2',
              note_id: '43',
              object_key: `owners/${USER}/notes/43/att-2`,
              mime_type: 'image/png',
              size_bytes: 3,
              attachment_type: 'image',
              created_at: 1001,
            },
          ],
          error: null,
        };
      }
      return { data: null, error: null };
    };

    const rows = await listUserAttachments();
    expect(rows[0]?.placement).toEqual(remotePlacement);
    expect(rows[1]?.placement).toBeUndefined();

    const syncedNote = noteWithImage({
      id: '43',
      localId: 43,
      attachments: [
        {
          id: 'att-2',
          noteId: 43,
          storagePath: `r2:owners/${USER}/notes/43/att-2`,
          type: 'image',
          placement: remotePlacement,
        },
      ],
    });
    const pendingNote = noteWithImage({
      id: '42',
      localId: 42,
      attachments: [
        {
          id: 'att-1',
          noteId: 42,
          storagePath: `r2:owners/${USER}/notes/42/att-1`,
          type: 'image',
          placement: undefined,
        },
      ],
    });

    const merged = mergeAttachmentsIntoNotes([pendingNote, syncedNote], rows, new Set(['42']));
    expect(merged[0]?.attachments[0]?.placement).toBeUndefined();
    expect(merged[1]?.attachments[0]?.placement).toBeUndefined();

    const syncedHydrated = mergeAttachmentsIntoNotes([pendingNote], rows, new Set());
    expect(syncedHydrated[0]?.attachments[0]?.placement).toEqual(remotePlacement);
  });
});
