import { afterEach, describe, expect, it } from 'vitest';
import {
  getRemoteNotesDataSource,
  resetRemoteNotesDataSourceForTests,
  setRemoteNotesDataSourceForTests,
} from '@/lib/remote/remoteNotesDataSourceRegistry';
import { supabaseRemoteNotesDataSource } from '@/lib/supabase/supabaseRemoteNotesDataSource';

describe('remoteNotesDataSourceRegistry', () => {
  afterEach(() => {
    resetRemoteNotesDataSourceForTests();
  });

  it('uses the Supabase remote notes source', () => {
    expect(getRemoteNotesDataSource()).toBe(supabaseRemoteNotesDataSource);
  });

  it('honours test overrides', () => {
    const stub = { ...supabaseRemoteNotesDataSource };
    setRemoteNotesDataSourceForTests(stub);
    expect(getRemoteNotesDataSource()).toBe(stub);
  });
});
