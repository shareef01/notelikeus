import 'fake-indexeddb/auto';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const saved: Note[] = [];
vi.mock('@/lib/notes/noteActions', () => ({
  saveNote: vi.fn(async (note: Note) => {
    saved.push(note);
    const { useNotesStore } = await import('@/store/notesStore');
    useNotesStore.getState().upsertLocalNote(note);
  }),
  removeNote: vi.fn(),
}));
vi.mock('@/lib/attachments/attachmentConfig', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/attachments/attachmentConfig')>()),
  isR2AttachmentsEnabled: () => true,
}));

import { useNoteEditor } from '@/hooks/useNoteEditor';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { clearPendingAttachmentsForTests } from '@/lib/attachments/pendingAttachmentStore';
import { getPendingAttachment } from '@/lib/local/pendingAttachmentRepository';
import { useAuthStore } from '@/store/authStore';
import { useNotesStore } from '@/store/notesStore';
import { createEmptyNote } from '@/types/note';

const USER = 'user-editor-copy';

type Editor = ReturnType<typeof useNoteEditor>;

function opened(): Note {
  return createEmptyNote({ id: '1', localId: 1, title: 'Trip', content: 'tickets', timestamp: 900, serverUpdatedAt: 1000 });
}

function mount(noteId: string): { current: () => Editor; unmount: () => void } {
  let latest: Editor | null = null;
  function Probe() {
    latest = useNoteEditor(noteId);
    return null;
  }
  const root = createRoot(document.createElement('div'));
  act(() => root.render(createElement(Probe)));
  return {
    current: () => latest as unknown as Editor,
    unmount: () => act(() => root.unmount()),
  };
}

/** AU-13: an image added in the editor exists only on this device until it uploads. */
describe('editor conflicted copy and images not uploaded yet', () => {
  beforeEach(() => {
    saved.length = 0;
    clearPendingAttachmentsForTests();
    useAuthStore.getState().reset();
    useAuthStore.getState().setUser({ uid: USER, email: null, displayName: null });
    useNotesStore.getState().reset();
    useNotesStore.getState().setNotes([opened()]);
  });

  it('carries the image into the copy under a new id, with its bytes staged for the copy', async () => {
    const editor = mount('1');
    const photo = new File(['photo bytes'], 'photo.png', { type: 'image/png' });
    await act(async () => {
      await editor.current().addAttachment(photo);
    });
    const [added] = editor.current().state.attachments;
    expect(added).toBeDefined();
    act(() =>
      useNotesStore
        .getState()
        .upsertLocalNote({ ...opened(), content: 'tickets, passport (from phone)', timestamp: 1900, serverUpdatedAt: 2000 }),
    );

    await act(async () => {
      await editor.current().flushSave();
    });

    const copy = saved.at(-1)!;
    expect(copy.id).not.toBe('1');
    expect(copy.attachments).toHaveLength(1);
    const [carried] = copy.attachments;
    expect(carried.id).not.toBe(added.id);
    expect(carried.storagePath).toBe(pendingStoragePath(carried.id));
    expect((await getPendingAttachment(USER, copy.id, carried.id))?.sizeBytes).toBe(photo.size);
    expect(editor.current().state.attachments.map((attachment) => attachment.id)).toEqual([carried.id]);
    // The phone's version never had the image, so the draft's own staging is released.
    expect(await getPendingAttachment(USER, '1', added.id)).toBeNull();
    editor.unmount();
  });
});
