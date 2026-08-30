import { describe, expect, it, beforeEach } from 'vitest';
import {
  buildNoteFromEditor,
  createBlankEditorState,
  editorStateFromNote,
} from '@/store/editorTypes';
import { useNotesStore } from '@/store/notesStore';
import { useUiStore } from '@/store/uiStore';
import { createEmptyNote } from '@/types/note';

describe('Editor State & Note Opening Logic', () => {
  beforeEach(() => {
    useNotesStore.getState().reset();
    useUiStore.getState().closeEditor();
  });

  it('maps an existing note accurately into EditorState on open', () => {
    const note = createEmptyNote({
      id: 'note-123',
      localId: 123,
      title: 'Audited Note Title',
      content: 'Important body text content',
      color: 0xff112233 | 0,
      isPinned: true,
      position: 5,
      timestamp: 1725000000,
    });

    const editorState = editorStateFromNote(note);

    expect(editorState.id).toBe('note-123');
    expect(editorState.localId).toBe(123);
    expect(editorState.title).toBe('Audited Note Title');
    expect(editorState.content).toBe('Important body text content');
    expect(editorState.color).toBe(0xff112233 | 0);
    expect(editorState.isPinned).toBe(true);
    expect(editorState.position).toBe(5);
  });

  it('creates blank editor state for new note route', () => {
    const blank = createBlankEditorState();

    expect(blank.id).toBeNull();
    expect(blank.localId).toBeNull();
    expect(blank.title).toBe('');
    expect(blank.content).toBe('');
    expect(blank.isLoaded).toBe(true);
  });

  it('round-trips EditorState back to Note without losing fields', () => {
    const note = createEmptyNote({
      id: 'note-456',
      localId: 456,
      title: 'Trip Plan',
      content: 'Flight at 8am',
      color: 0xff445566 | 0,
      timestamp: 1725000000,
    });

    const state = editorStateFromNote(note);
    const rebuilt = buildNoteFromEditor(state);

    expect(rebuilt).not.toBeNull();
    expect(rebuilt?.id).toBe(note.id);
    expect(rebuilt?.title).toBe(note.title);
    expect(rebuilt?.content).toBe(note.content);
    expect(rebuilt?.color).toBe(note.color);
  });

  it('opens correct note route when openNote is called', () => {
    const note = createEmptyNote({
      id: 'note-999',
      localId: 999,
      title: 'Target Note',
      content: 'Target Content',
    });
    useNotesStore.getState().setNotes([note]);

    useUiStore.getState().openNote('note-999');

    const route = useUiStore.getState().editorRoute;
    expect(route).toEqual({ mode: 'edit', noteId: 'note-999' });

    // Store lookup returns the target note
    const found = useNotesStore.getState().notes.find((n) => n.id === (route as { noteId: string }).noteId);
    expect(found).toBeDefined();
    expect(found?.title).toBe('Target Note');
  });

  it('opens new note route when openNewNote is called', () => {
    useUiStore.getState().openNewNote();

    const route = useUiStore.getState().editorRoute;
    expect(route).toEqual({ mode: 'new' });
  });
});
