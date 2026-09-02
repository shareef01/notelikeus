import { afterEach, describe, expect, it, vi } from 'vitest';
import { firebaseRemoteNotesDataSource } from '@/lib/remote/firebaseRemoteNotesDataSource';
import {
  getRemoteNotesDataSource,
  resetRemoteNotesDataSourceForTests,
  setRemoteNotesDataSourceForTests,
} from '@/lib/remote/remoteNotesDataSourceRegistry';
import * as supabaseClient from '@/lib/supabase/client';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';

describe('remoteNotesDataSourceRegistry', () => {
  afterEach(() => {
    resetRemoteNotesDataSourceForTests();
    vi.restoreAllMocks();
  });

  it('defaults to Firebase when the Supabase flag is off', () => {
    vi.spyOn(supabaseClient, 'isSupabaseBackendEnabled').mockReturnValue(false);
    expect(getRemoteNotesDataSource()).toBe(firebaseRemoteNotesDataSource);
  });

  it('selects Supabase when the dev flag is on', () => {
    vi.spyOn(supabaseClient, 'isSupabaseBackendEnabled').mockReturnValue(true);
    expect(getRemoteNotesDataSource()).toBe(supabaseRemoteNotesDataSource);
  });

  it('honours test overrides', () => {
    const stub = { ...firebaseRemoteNotesDataSource };
    setRemoteNotesDataSourceForTests(stub);
    expect(getRemoteNotesDataSource()).toBe(stub);
  });
});
