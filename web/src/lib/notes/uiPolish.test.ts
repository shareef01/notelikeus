import { describe, expect, it } from 'vitest';
import { isConflictedCopyTitle } from '@/lib/notes/conflictResolution';
import { stripMarkdownForPreview } from '@/lib/text/markdown';
import { DEFAULT_THEME } from '@/store/settingsStore';

describe('card preview text', () => {
  it('shows task-list syntax as checkboxes and keeps the lines', () => {
    expect(stripMarkdownForPreview('- [ ] Oat milk\n- [x] Eggs\n  * [X] nested')).toBe('☐ Oat milk\n☑ Eggs\n  ☑ nested');
  });

  it('leaves brackets elsewhere alone', () => {
    expect(stripMarkdownForPreview('see [ ] later - [ ] not at start')).toBe('see [ ] later - [ ] not at start');
  });
});

describe('conflicted copy marker', () => {
  it('recognises the titles conflict resolution writes', () => {
    expect(isConflictedCopyTitle('Groceries (conflicted copy)')).toBe(true);
    expect(isConflictedCopyTitle('Conflicted copy')).toBe(true);
    expect(isConflictedCopyTitle('Notes on conflicted copy semantics')).toBe(false);
  });
});

describe('default theme', () => {
  it('follows the device, as Android and Windows do', () => {
    expect(DEFAULT_THEME).toEqual({ base: 'system', accent: 'neutral', amoled: false });
  });
});
