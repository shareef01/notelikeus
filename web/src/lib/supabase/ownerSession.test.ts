import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { createClient, type Session } from '@supabase/supabase-js';
import { setSupabaseClientForTests } from '@/lib/supabase/client';
import { applyNoteChange } from '@/lib/supabase/supabaseSyncEngine';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { useTombstoneStore } from '@/store/tombstoneStore';
import { useAuthStore } from '@/store/authStore';
import { createEmptyNote } from '@/types/note';
import { createR2AttachmentBlobStore } from '@/lib/attachments/r2AttachmentBlobStore';

const OWNER_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const OWNER_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const note = createEmptyNote({ id: '11111111-1111-4111-8111-111111111111', localId: 1, title: 'Only A may receive this' });
const session = (owner: string) => ({ data: { session: { user: { id: owner }, access_token: `token-${owner}` } as Session }, error: null });

beforeEach(async () => {
  await resetNotesDatabaseForTests();
  useTombstoneStore.getState().reset();
  useAuthStore.getState().setUser({ uid: OWNER_A, email: null, displayName: null });
});
afterEach(() => {
  setSupabaseClientForTests(null);
  vi.restoreAllMocks();
});

it('refuses a stale owner before sending any note content', async () => {
  const send = vi.fn<typeof fetch>();
  const client = createClient('http://localhost:54321', 'anon', { auth: { persistSession: false, autoRefreshToken: false }, global: { fetch: send } });
  vi.spyOn(client.auth, 'getSession').mockResolvedValue(session(OWNER_B));
  setSupabaseClientForTests(client);
  await expect(applyNoteChange(OWNER_A, note, null)).rejects.toThrow('Account changed');
  expect(send).not.toHaveBeenCalled();
});

it('refuses a session result captured before the UI switched accounts', async () => {
  const send = vi.fn<typeof fetch>();
  const client = createClient('http://localhost:54321', 'anon', { auth: { persistSession: false, autoRefreshToken: false }, global: { fetch: send } });
  let release!: (value: ReturnType<typeof session>) => void;
  const getSession = vi.spyOn(client.auth, 'getSession').mockImplementation(() => new Promise((resolve) => { release = resolve; }));
  setSupabaseClientForTests(client);
  const push = applyNoteChange(OWNER_A, note, null);
  const rejected = expect(push).rejects.toThrow('Account changed');
  await vi.waitFor(() => expect(getSession).toHaveBeenCalled());
  useAuthStore.getState().setUser({ uid: OWNER_B, email: null, displayName: null });
  release(session(OWNER_A));
  await rejected;
  expect(send).not.toHaveBeenCalled();
});

it('pins the Authorization header even when the SDK resolves a new session before sending', async () => {
  const headers: string[] = [];
  const send = vi.fn<typeof fetch>(async (_url, init) => {
    headers.push(new Headers(init?.headers).get('Authorization')!);
    return new Response(JSON.stringify({ status: 'applied', revision: 1 }), { status: 200 });
  });
  const client = createClient('http://localhost:54321', 'anon', { auth: { persistSession: false, autoRefreshToken: false }, global: { fetch: send } });
  // Capture A, then let fetchWithAuth consult B. The explicit header must still name A.
  vi.spyOn(client.auth, 'getSession').mockResolvedValue(session(OWNER_B)).mockResolvedValueOnce(session(OWNER_A));
  setSupabaseClientForTests(client);
  await expect(applyNoteChange(OWNER_A, note, null)).rejects.toThrow('Account changed');
  expect(headers).toEqual([`Bearer token-${OWNER_A}`]);
});

it('does not apply a late old-owner deletion conflict to the new session tombstones', async () => {
  const send = vi.fn<typeof fetch>(async () => new Response(JSON.stringify({ status: 'conflict', error: 'note_deleted' }), { status: 200 }));
  const client = createClient('http://localhost:54321', 'anon', { auth: { persistSession: false, autoRefreshToken: false }, global: { fetch: send } });
  vi.spyOn(client.auth, 'getSession').mockResolvedValue(session(OWNER_B)).mockResolvedValueOnce(session(OWNER_A));
  setSupabaseClientForTests(client);
  await expect(applyNoteChange(OWNER_A, note, null)).rejects.toThrow('Account changed');
  expect(useTombstoneStore.getState().isDeleted(note.id)).toBe(false);
});

it('refuses to upload old-owner image bytes using a new account session', async () => {
  const send = vi.fn<typeof fetch>();
  const client = createClient('http://localhost:54321', 'anon', { auth: { persistSession: false, autoRefreshToken: false }, global: { fetch: send } });
  vi.spyOn(client.auth, 'getSession').mockResolvedValue(session(OWNER_B));
  setSupabaseClientForTests(client);
  // Worker requests use global fetch, whereas PostgREST uses the client's fetch wrapper.
  vi.stubGlobal('fetch', send);
  try {
    await expect(createR2AttachmentBlobStore('https://worker.test').upload(note.id, 'photo', new Blob(['A private image']), 'image/png', OWNER_A))
      .rejects.toThrow('Account changed');
    expect(send).not.toHaveBeenCalled();
  } finally {
    vi.unstubAllGlobals();
  }
});
