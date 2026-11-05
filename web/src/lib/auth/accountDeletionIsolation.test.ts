import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const auth = vi.hoisted(() => ({ session: vi.fn(), signOut: vi.fn(), rpc: vi.fn(), headers: [] as string[] }));
vi.mock('@/lib/supabase/client', () => ({ getSupabaseClient: () => ({
  auth: { getSession: auth.session, signOut: auth.signOut },
  rpc: (name: string) => ({ setHeader: (_name: string, value: string) => { auth.headers.push(value); return auth.rpc(name); } }),
}) }));
import { deleteAccount } from '@/lib/auth/accountAuth';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { resetNotesDatabaseForTests } from '@/lib/local/idb';
import { listNotes, putNotes } from '@/lib/local/notesLocalRepository';
import { createEmptyNote } from '@/types/note';
import { ReauthenticationRequiredError } from '@/lib/auth/reauthentication';

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const session = (id: string) => ({ data: { session: { user: { id }, access_token: `token-${id}` } }, error: null });
const noteA = createEmptyNote({ id: 'a-note', localId: 1, title: 'A only' });
const noteB = createEmptyNote({ id: 'b-note', localId: 2, title: 'B only' });
function switchToB() {
  useAuthStore.getState().setUser({ uid: B, email: null, displayName: null });
  useNotesStore.getState().setNotes([noteB]);
  auth.session.mockResolvedValue(session(B));
}
beforeEach(async () => {
  vi.resetAllMocks();
  auth.headers.length = 0;
  vi.stubEnv('VITE_ATTACHMENTS_WORKER_URL', 'https://worker.test');
  await resetNotesDatabaseForTests();
  await putNotes(A, [noteA]);
  await putNotes(B, [noteB]);
  auth.session.mockResolvedValue(session(A));
  auth.signOut.mockResolvedValue({ error: null });
  useAuthStore.getState().setUser({ uid: A, email: null, displayName: null });
  useNotesStore.getState().setNotes([noteA]);
});
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it('refuses a delayed session result from A once the UI belongs to B', async () => {
  let release!: (value: ReturnType<typeof session>) => void;
  auth.session.mockImplementationOnce(() => new Promise((resolve) => { release = resolve; }));
  const send = vi.fn();
  vi.stubGlobal('fetch', send);
  const deleting = deleteAccount();
  const rejected = expect(deleting).rejects.toThrow('Account changed');
  switchToB();
  release(session(A));
  await rejected;
  expect(send).not.toHaveBeenCalled();
  expect(await listNotes(A)).toEqual([noteA]);
  expect(await listNotes(B)).toEqual([noteB]);
  expect(auth.signOut).not.toHaveBeenCalled();
});

it('clears only A after a successful response received during B session', async () => {
  const send = vi.fn(async (_url: string, init: RequestInit) => {
    expect(init.headers).toEqual({ Authorization: `Bearer token-${A}` });
    switchToB();
    return new Response('{}', { status: 202 });
  });
  vi.stubGlobal('fetch', send);
  await deleteAccount();
  expect(await listNotes(A)).toEqual([]);
  expect(await listNotes(B)).toEqual([noteB]);
  expect(useNotesStore.getState().notes).toEqual([noteB]);
  expect(useAuthStore.getState().user?.uid).toBe(B);
  expect(auth.signOut).not.toHaveBeenCalled();
});

it('checks an ambiguous outcome with A captured bearer and preserves B session', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { switchToB(); throw new TypeError('reply lost'); }));
  auth.rpc.mockResolvedValue({ data: null, error: { code: 'NL003' } });
  await deleteAccount();
  expect(auth.headers).toEqual([`Bearer token-${A}`]);
  expect(await listNotes(A)).toEqual([]);
  expect(await listNotes(B)).toEqual([noteB]);
  expect(useNotesStore.getState().notes).toEqual([noteB]);
  expect(auth.signOut).not.toHaveBeenCalled();
});

it('refuses an old account explicit intent before looking up a new account session', async () => {
  switchToB();
  await expect(deleteAccount(A)).rejects.toThrow('Account changed');
  expect(auth.session).not.toHaveBeenCalled();
  expect(await listNotes(A)).toEqual([noteA]);
});

it('does not transfer a late reauthentication refusal into B deletion intent', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => {
    switchToB();
    return new Response('{"error":"reauthentication_required"}', { status: 403 });
  }));
  const error = await deleteAccount().catch((failure: unknown) => failure);
  expect(error).toBeInstanceOf(Error);
  expect(error).not.toBeInstanceOf(ReauthenticationRequiredError);
  expect(String(error)).toContain('Account changed');
  expect(await listNotes(A)).toEqual([noteA]);
  expect(auth.signOut).not.toHaveBeenCalled();
});
