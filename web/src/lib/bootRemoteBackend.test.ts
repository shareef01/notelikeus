import { beforeEach, describe, expect, it, vi } from 'vitest';

const { state, initFirebaseMock } = vi.hoisted(() => ({
  state: {
    supabaseEnabled: false,
    firebaseConfigured: true,
    supabaseUrl: 'https://abcd.supabase.co',
    supabaseAnonKey: 'eyJhbGciOiJIUzI1NiJ9.payload.signature',
  },
  initFirebaseMock: vi.fn(),
}));

vi.mock('@/lib/local/notesLocalRepository', () => ({ clearOwner: vi.fn() }));
vi.mock('@/lib/config', () => ({ isFirebaseConfigured: () => state.firebaseConfigured }));
vi.mock('@/lib/firebase', () => ({ initFirebase: initFirebaseMock }));
vi.mock('@/lib/reminders/reminderSync', () => ({ ensureReminderSync: vi.fn() }));
vi.mock('@/lib/supabase/client', () => ({
  isSupabaseBackendEnabled: () => state.supabaseEnabled,
  loadSupabaseUrl: () => state.supabaseUrl,
  loadSupabaseAnonKey: () => state.supabaseAnonKey,
}));

import { BootFailure, bootstrapApp } from '@/lib/bootstrap';

/**
 * Boot required full Firebase web config unconditionally — including for a build that had already
 * selected Supabase, which is a cutover blocker and meant Pages staging was never exercising a
 * Firebase-free client. Boot now requires the backend the build actually selected.
 *
 * Firebase remains the production default, so the production expectations below are the ones that
 * must not move: `isSupabaseBackendEnabled()` is false in production unless an owner-authorised
 * cutover build sets the allow flag.
 */
describe('boot requires the selected remote backend', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.supabaseEnabled = false;
    state.firebaseConfigured = true;
    state.supabaseUrl = 'https://abcd.supabase.co';
    state.supabaseAnonKey = 'eyJhbGciOiJIUzI1NiJ9.payload.signature';
  });

  it('still initialises Firebase when Firebase is the selected backend', async () => {
    await bootstrapApp();
    expect(initFirebaseMock).toHaveBeenCalled();
  });

  it('still fails a Firebase build with no Firebase config', async () => {
    state.firebaseConfigured = false;
    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'firebase-config' });
  });

  it('boots a Supabase build with no Firebase config at all', async () => {
    state.supabaseEnabled = true;
    state.firebaseConfigured = false;

    await expect(bootstrapApp()).resolves.toBeUndefined();
    expect(initFirebaseMock).not.toHaveBeenCalled();
  });

  it('keeps Firebase initialised on a Supabase build that still carries the config', async () => {
    // The Phase 6 bridge reads the live Firebase session to prove ownership of a legacy uid.
    state.supabaseEnabled = true;
    state.firebaseConfigured = true;

    await bootstrapApp();

    expect(initFirebaseMock).toHaveBeenCalled();
  });

  it('does not fail a Supabase build when Firebase init throws', async () => {
    state.supabaseEnabled = true;
    initFirebaseMock.mockImplementationOnce(() => {
      throw new Error('firebase down');
    });

    await expect(bootstrapApp()).resolves.toBeUndefined();
  });

  it('refuses a Supabase build whose anon key is a secret API key', async () => {
    state.supabaseEnabled = true;
    state.supabaseAnonKey = 'sb_secret_not_a_real_key';

    await expect(bootstrapApp()).rejects.toBeInstanceOf(BootFailure);
    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'supabase-config' });
  });

  it('refuses a Supabase build with no anon key', async () => {
    state.supabaseEnabled = true;
    state.supabaseAnonKey = '';

    await expect(bootstrapApp()).rejects.toMatchObject({ code: 'supabase-config' });
  });
});
