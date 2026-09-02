import { describe, expect, it } from 'vitest';
import {
  SUPABASE_PULL_DEBOUNCE_MS,
  SUPABASE_PULL_FALLBACK_MS,
} from '@/lib/supabase/constants';

describe('supabase realtime constants', () => {
  it('uses a short debounce and a slow fallback interval', () => {
    expect(SUPABASE_PULL_DEBOUNCE_MS).toBeLessThan(SUPABASE_PULL_FALLBACK_MS);
    expect(SUPABASE_PULL_FALLBACK_MS).toBeGreaterThanOrEqual(30_000);
  });
});
