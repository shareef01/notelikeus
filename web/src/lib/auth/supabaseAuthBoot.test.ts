import { describe, expect, it } from 'vitest';
import { shouldStartSupabaseAuthOnBoot } from '@/lib/auth/supabaseAuthBoot';

describe('shouldStartSupabaseAuthOnBoot', () => {
  it('skips supabase-js for a first-time visitor', () => {
    expect(shouldStartSupabaseAuthOnBoot(false, '')).toBe(false);
    expect(shouldStartSupabaseAuthOnBoot(false, '?new=1')).toBe(false);
  });

  it('loads supabase-js when the last visit was signed in', () => {
    expect(shouldStartSupabaseAuthOnBoot(true, '')).toBe(true);
  });

  it('loads supabase-js to finish a Google OAuth redirect', () => {
    expect(shouldStartSupabaseAuthOnBoot(false, '?code=pkce-grant')).toBe(true);
  });
});
