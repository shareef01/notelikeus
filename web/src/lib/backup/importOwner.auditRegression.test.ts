import { expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ write: vi.fn(), read: vi.fn() }));
vi.mock('@/lib/local/notesLocalRepository', () => ({
  putNotes: mocks.write, putLocalEdits: mocks.write, listPendingPushes: vi.fn(async () => ({})),
  listStoredNotes: mocks.read,
}));
vi.mock('@/lib/notes/notesSyncService', () => ({ pauseRealtimeSnapshots: vi.fn(), resumeRealtimeSnapshots: vi.fn() }));
vi.mock('@/lib/remote/remoteNotesDataSourceRegistry', () => ({ getRemoteNotesDataSource: () => ({}) }));
import { commitImportedNotes } from '@/lib/backup/commitImportedNotes';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';
import { loadLocalNotesIntoStore } from '@/lib/local/hydrateFromRemote';

// Regression for account ownership across asynchronous operations.
it('AUD-E06 keeps A imports out of B active library', async () => {
  useAuthStore.getState().setUser({ uid: 'A', email: null, displayName: null });
  let finish!: () => void;
  mocks.write.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; }));
  const imported = createEmptyNote({ id: '11', localId: 11, content: 'A imported secret' });
  const importing = commitImportedNotes([imported], 1, 'A');
  useAuthStore.getState().setUser({ uid: 'B', email: null, displayName: null });
  useNotesStore.getState().setNotes([createEmptyNote({ id: '22', localId: 22, content: 'B note' })]);
  finish(); await importing;
  expect(useNotesStore.getState().notes.map(n => n.content)).toEqual(['B note']);
});

it('AUD-E10 refuses publication from an inactive local load', async () => {
  useAuthStore.getState().setUser({ uid: 'A', email: null, displayName: null });
  const a = createEmptyNote({ id: '11', localId: 11, content: 'A private text' });
  let finish!: (value: { notes: typeof a[]; unreadable: [] }) => void;
  mocks.read.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const loading = loadLocalNotesIntoStore('A');
  useAuthStore.getState().setUser({ uid: 'B', email: null, displayName: null });
  useNotesStore.getState().setNotes([createEmptyNote({ id: '22', localId: 22, content: 'B text' })]);
  finish({ notes: [a], unreadable: [] }); await loading;
  expect(useNotesStore.getState().notes[0]?.content).toBe('B text');
  expect(useAuthStore.getState().user?.uid).toBe('B');
});
