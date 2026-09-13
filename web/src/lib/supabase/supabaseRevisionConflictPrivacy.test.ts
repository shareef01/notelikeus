import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { rpcMock } = vi.hoisted(() => ({ rpcMock: vi.fn() }));

vi.mock('@/lib/supabase/client', () => ({
  getSupabaseClient: () => ({ rpc: rpcMock }),
  isSupabaseBackendEnabled: () => true,
}));

import { applyNoteChange } from '@/lib/supabase/supabaseSyncEngine';
import { createEmptyNote } from '@/types/note';

const USER = '11111111-1111-4111-8111-111111111111';

describe('F07: Revision Conflict Privacy (No Note Title Leak)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('revisionConflictErrorDoesNotContainNoteTitle', async () => {
    const SENSITIVE_TITLE = 'Very Private Medical Note';
    const SENSITIVE_CONTENT = 'Confidential Diagnostic Results';

    const localNote = createEmptyNote({
      id: 'confidential-note-1',
      localId: 9001,
      title: 'Local Draft Title',
      content: 'Local Content',
    });

    // Mock apply_note_change returning revision conflict with sensitive remote note
    rpcMock.mockResolvedValueOnce({
      data: {
        status: 'conflict',
        current: {
          note_id: 'confidential-note-1',
          title: SENSITIVE_TITLE,
          content: SENSITIVE_CONTENT,
          revision: 5,
        },
      },
      error: null,
    });

    let thrownError: Error | null = null;
    try {
      await applyNoteChange(USER, localNote, 4);
    } catch (err) {
      thrownError = err as Error;
    }

    expect(thrownError).not.toBeNull();
    const message = thrownError!.message;

    // INVARIANT: Error message MUST NOT contain sensitive user content
    expect(message).not.toContain(SENSITIVE_TITLE);
    expect(message).not.toContain(SENSITIVE_CONTENT);

    // INVARIANT: Useful diagnostics are retained
    expect(message).toContain('Revision conflict for note confidential-note-1');
    expect(message).toContain('local revision 4');
    expect(message).toContain('remote revision 5');
  });
});
