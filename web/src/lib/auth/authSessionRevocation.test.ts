import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthUser } from '@/lib/auth/authUser';

let authCallback: ((user: AuthUser | null) => void) | null = null;

vi.mock('@/lib/auth/supabaseAuth', () => ({
  completeSupabaseOAuthRedirect: vi.fn().mockResolvedValue(undefined),
  initSupabaseAuthListener: vi.fn((cb: (user: AuthUser | null) => void) => {
    authCallback = cb;
    return vi.fn();
  }),
}));

import { ensureSupabaseAuthStarted } from '@/hooks/useAuth';
import { hadSessionLastLoad } from '@/lib/auth/sessionHint';
import { storePendingAttachment, peekPendingAttachment } from '@/lib/attachments/pendingAttachmentStore';
import { useAuthStore } from '@/store/authStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

describe('External session revocation (Finding NEW-05)', () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    authCallback = null;
    localStorage.clear();
    useAuthStore.getState().reset();
    useNotesStore.getState().reset();
    useLabelRegistryStore.getState().reset();
    await ensureSupabaseAuthStarted();
  });

  it('purges in-memory notes, labels, attachments, and session hints when session is externally revoked', async () => {
    expect(authCallback).not.toBeNull();

    // 1. Simulate active authenticated user session
    const activeUser: AuthUser = {
      uid: 'user-revoked-123',
      email: 'user@example.com',
      displayName: 'Active User',
    };
    authCallback!(activeUser);

    expect(useAuthStore.getState().user?.uid).toBe('user-revoked-123');
    expect(hadSessionLastLoad()).toBe(true);

    // 2. Populate sensitive in-memory state
    useNotesStore.getState().setNotes([
      createEmptyNote({
        id: 'secret-note-1',
        localId: 1,
        title: 'Confidential',
        content: 'Sensitive data',
        timestamp: Date.now(),
      }),
    ]);
    useLabelRegistryStore.getState().addLabel('personal-secret');
    await storePendingAttachment(
      'att-secret',
      new Blob(['secret-bytes'], { type: 'image/png' }),
      'image/png',
      'secret-note-1',
      'user-revoked-123',
    );

    expect(useNotesStore.getState().notes.length).toBe(1);
    expect(Object.values(useLabelRegistryStore.getState().labels).map((l) => l.name)).toContain('personal-secret');
    expect(peekPendingAttachment('att-secret', 'user-revoked-123')).toBeDefined();

    // 3. Simulate external session revocation (Supabase token expired / revoked in cloud)
    authCallback!(null);

    // 4. In-memory data must be completely wiped
    expect(useAuthStore.getState().user).toBeNull();
    expect(useNotesStore.getState().notes).toEqual([]);
    expect(useLabelRegistryStore.getState().labels).toEqual({});
    expect(peekPendingAttachment('att-secret', 'user-revoked-123')).toBeUndefined();
    expect(hadSessionLastLoad()).toBe(false);
  });
});
