import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';

import { LabelsScreen } from '@/screens/LabelsScreen';
import { labelNoteCounts } from '@/lib/labels/labelNoteCounts';
import { useNotesStore } from '@/store/notesStore';
import { useLabelRegistryStore } from '@/store/labelRegistryStore';
import { createEmptyNote, type Note } from '@/types/note';

const work = { id: 'label-work', name: 'Work' };
const idle = { id: 'label-idle', name: 'Idle' };

function note(id: number, partial: Partial<Note>): Note {
  return createEmptyNote({ id: `n${id}`, localId: id, title: `Note ${id}`, timestamp: 1, ...partial });
}

describe('labelNoteCounts', () => {
  it('counts active and archived notes once each, and leaves the trash out', () => {
    const counts = labelNoteCounts([
      note(1, { labels: [work, work] }),
      note(2, { labels: [work], isArchived: true }),
      note(3, { labels: [work], isTrashed: true }),
    ]);
    expect(counts.get('label-work')).toBe(2);
    expect(counts.get('label-idle')).toBeUndefined();
  });
});

/** The Edit labels page says how many notes use each label, and has a visible Rename control. */
describe('LabelsScreen', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useNotesStore.setState({ notes: [note(1, { labels: [work] }), note(2, { labels: [work] })] });
    useLabelRegistryStore.setState({ labels: { [idle.id]: idle } });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
    useNotesStore.setState({ notes: [] });
    useLabelRegistryStore.setState({ labels: {} });
  });

  async function render() {
    await act(async () => {
      root.render(createElement(LabelsScreen, { onClose: () => {} }));
    });
  }

  const button = (name: string) =>
    [...document.querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === name)!;

  it('shows each label’s note count', async () => {
    await render();
    const text = document.body.textContent ?? '';
    expect(text).toContain('Work2 notes');
    expect(text).toContain('IdleNo notes');
  });

  it('renames from a visible button', async () => {
    await render();
    await act(async () => button('Rename Work').click());
    expect(document.querySelector<HTMLInputElement>('input[aria-label="Edit label name"]')?.value).toBe('Work');
  });

  it('says how many notes lose the label when deleting it', async () => {
    await render();
    await act(async () => button('Delete Work').click());
    expect(document.body.textContent).toContain('will be removed from 2 notes. The notes themselves are kept.');
  });
});
