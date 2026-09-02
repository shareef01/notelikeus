import { describe, expect, it } from 'vitest';
import {
  accountsMatch,
  isLikelyFirebaseUid,
  isSupabaseUuid,
} from '@/lib/migration/accountIdentity';

describe('accountIdentity', () => {
  it('detects Supabase UUIDs', () => {
    expect(isSupabaseUuid('11111111-2222-4333-8444-555555555555')).toBe(true);
    expect(isSupabaseUuid('firebaseUid28charsabcdefghij')).toBe(false);
  });

  it('treats linked firebase uid and supabase uuid as the same account', () => {
    const firebaseUid = 'firebaseUid28charsabcdefghij';
    const supabaseUid = '11111111-2222-4333-8444-555555555555';
    expect(isLikelyFirebaseUid(firebaseUid)).toBe(true);
    expect(accountsMatch(firebaseUid, supabaseUid, firebaseUid)).toBe(true);
    expect(accountsMatch(supabaseUid, firebaseUid, firebaseUid)).toBe(true);
    expect(accountsMatch('other-user', supabaseUid, firebaseUid)).toBe(false);
  });
});
