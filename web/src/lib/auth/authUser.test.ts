import { describe, expect, it } from 'vitest';
import { authUserFromSupabase } from '@/lib/auth/authUser';

describe('authUserFromSupabase', () => {
  it('maps Supabase user id to uid', () => {
    const user = authUserFromSupabase({
      id: '11111111-2222-3333-4444-555555555555',
      email: 'dev@notelikeus.test',
      user_metadata: { full_name: 'Dev User' },
      app_metadata: {},
      aud: 'authenticated',
      created_at: '2025-01-01T00:00:00Z',
    });

    expect(user.uid).toBe('11111111-2222-3333-4444-555555555555');
    expect(user.email).toBe('dev@notelikeus.test');
    expect(user.displayName).toBe('Dev User');
  });
});
