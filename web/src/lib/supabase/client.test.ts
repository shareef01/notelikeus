import { describe, expect, it } from 'vitest';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';

describe('isSupabaseBackendEnabled', () => {
  it('returns false unless VITE_REMOTE_BACKEND=supabase in dev', () => {
    expect(isSupabaseBackendEnabled()).toBe(import.meta.env.VITE_REMOTE_BACKEND === 'supabase');
  });
});
