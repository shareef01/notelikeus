import { describe, expect, it } from 'vitest';
import { isSupabaseBackendEnabled } from '@/lib/supabase/client';

describe('isSupabaseBackendEnabled', () => {
  it('is true in unit tests when the default local demo project is configured', () => {
    expect(isSupabaseBackendEnabled()).toBe(true);
  });
});
