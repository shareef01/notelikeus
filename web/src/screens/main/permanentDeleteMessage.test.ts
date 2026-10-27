import { describe, expect, it } from 'vitest';
import { permanentDeleteMessage } from '@/screens/main/useNoteActions';

describe('permanentDeleteMessage', () => {
  it('leaves the message alone when every delete reached the cloud', () => {
    expect(permanentDeleteMessage('Note deleted permanently', 1, 0)).toBe('Note deleted permanently');
  });

  it('says a single note stays on other devices', () => {
    expect(permanentDeleteMessage('Note deleted permanently', 1, 1)).toBe(
      'Note deleted permanently. It stays on your other devices until the next sync.',
    );
  });

  it('counts the notes that stay in a batch', () => {
    expect(permanentDeleteMessage('Deleted 3 notes permanently', 3, 1)).toBe(
      'Deleted 3 notes permanently. 1 stays on your other devices until the next sync.',
    );
    expect(permanentDeleteMessage('Deleted 3 notes permanently', 3, 2)).toBe(
      'Deleted 3 notes permanently. 2 stay on your other devices until the next sync.',
    );
  });
});
