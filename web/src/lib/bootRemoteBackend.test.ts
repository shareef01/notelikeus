import { beforeEach, describe, expect, it, vi } from 'vitest';

const { state } = vi.hoisted(() => ({
  state: {
    supabaseEnabled: true,
    supabaseUrl: 'https://abcd.supabase.co',
    supabaseAnonKey: 'eyJhbGciOiJIUzI1NiJ9.payload.signature',
  },
}));

vi.mock('@/lib/local/notesLocalRepository', () => ({ clearOwner: vi.fn() }));
vi.mock('@/lib/reminders/reminderSync', () => ({ ensureReminderSync: vi.fn() }));
vi.mock('@/lib/supabase/client', () => ({
  isSupabaseBackendEnabled: () => state.supabaseEnabled,
  loadSupabaseUrl: () => state.supabaseUrl,
  loadSupabaseAnonKey: () => state.supabaseAnonKey,
}));

import { BootFailure, bootstrapApp } from '@/lib/bootstrap';

describe('boot requires a configured Supabase backend', () => {
  beforeEach(() => {
    state.supabaseEnabled = true;
    state.supabaseUrl = 'https://abcd.supabase.co';
    state.supabaseAnonKey = 'eyJhbGciOiJIUzI1NiJ9.payload.signature';
  });

  it('boots when Supabase is configured', async () => {
    await expect(bootstrapApp()).resolves.toBeUndefined();
  });

  it('refuses a build whose anon key is a secret API key', async () => {
    state.supabaseEnabled = true;
    state.supabaseAnonKey = 'sb_secret_not_a_real_key';

    await expect(bootstrapApp()).rejects.toBeInstanceOf(BootFailure);
    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'supabase-config' });
  });

  it('refuses a build with no anon key', async () => {
    state.supabaseEnabled = true;
    state.supabaseAnonKey = '';

    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'supabase-config' });
  });

  it('refuses a production-style build that is not enabled', async () => {
    state.supabaseEnabled = false;

    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'supabase-config' });
  });
});
