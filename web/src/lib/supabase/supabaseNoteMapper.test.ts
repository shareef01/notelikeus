import { describe, expect, it } from 'vitest';
import {
  noteToSupabaseRpcArgs,
  supabaseNoteToNote,
} from '@/lib/supabase/supabaseNoteMapper';
import { createEmptyNote } from '@/types/note';

describe('supabaseNoteMapper', () => {
  it('maps a Supabase note payload into the canonical Note model', () => {
    const note = supabaseNoteToNote({
      note_id: '42',
      local_id: 42,
      revision: 10001,
      title: 'Hello',
      content: 'Body',
      client_timestamp: 1000,
      color: -14474606,
      is_pinned: true,
      is_archived: false,
      is_trashed: false,
      position: 3,
      reminder_timestamp: null,
      labels: [{ name: 'work' }],
      checklist: [{ text: 'task', isChecked: true, position: 0 }],
      server_updated_at: 5000,
    });

    expect(note.id).toBe('42');
    expect(note.localId).toBe(42);
    expect(note.title).toBe('Hello');
    expect(note.serverUpdatedAt).toBe(5000);
    expect(note.labels[0]?.name).toBe('work');
    expect(note.checklist[0]?.text).toBe('task');
    expect(note.checklist[0]?.isChecked).toBe(true);
  });

  it('builds apply_note_change RPC args with null base revision for creates', () => {
    const note = createEmptyNote({ id: '7', localId: 7, title: 'T' });
    const args = noteToSupabaseRpcArgs(note, null);
    expect(args.p_note_id).toBe('7');
    expect(args.p_base_revision).toBeNull();
    expect(args.p_labels).toEqual([]);
  });
});
