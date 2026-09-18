import { describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { NoteCard } from '@/components/notes/NoteCard';
import type { Note } from '@/types/note';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function createTestNote(overrides: Partial<Note> = {}): Note {
  return {
    id: 'test-note-1',
    localId: 1,
    title: 'Test Note Title',
    content: 'This is the note body text',
    color: 0,
    timestamp: 1_700_000_000_000,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    position: 0,
    reminderTimestamp: null,
    serverUpdatedAt: null,
    labels: [],
    attachments: [],
    checklist: [],
    ...overrides,
  };
}

function renderCard(note: Note, props: Partial<Parameters<typeof NoteCard>[0]> = {}) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  const onClick = props.onClick ?? vi.fn();

  act(() => {
    root.render(
      createElement(NoteCard, {
        note,
        onClick,
        density: props.density ?? 'grid',
        ...props,
      }),
    );
  });

  return {
    container,
    onClick,
    cleanup: () => {
      act(() => {
        root.unmount();
      });
      container.remove();
    },
  };
}

describe('NoteCard', () => {
  it('renders note title and content in grid view', () => {
    const note = createTestNote({ title: 'Grocery Run', content: 'Buy apples and oranges' });
    const { container, cleanup } = renderCard(note);

    expect(container.textContent).toContain('Grocery Run');
    expect(container.textContent).toContain('Buy apples and oranges');
    cleanup();
  });

  it('renders checklist items in grid view and applies line-through to completed items', () => {
    const note = createTestNote({
      checklist: [
        { id: '1', text: 'First task', isChecked: true, position: 0 },
        { id: '2', text: 'Second task', isChecked: false, position: 1 },
      ],
    });
    const { container, cleanup } = renderCard(note, { density: 'grid' });

    expect(container.textContent).toContain('First task');
    expect(container.textContent).toContain('Second task');

    const firstTaskSpan = Array.from(container.querySelectorAll('span')).find((el) =>
      el.textContent?.includes('First task'),
    );
    const secondTaskSpan = Array.from(container.querySelectorAll('span')).find((el) =>
      el.textContent?.includes('Second task'),
    );

    expect(firstTaskSpan?.className).toContain('line-through');
    expect(firstTaskSpan?.className).toContain('opacity-50');
    expect(secondTaskSpan?.className).not.toContain('line-through');
    expect(secondTaskSpan?.className).toContain('opacity-80');
    cleanup();
  });

  it('renders +N more when checklist has more than 3 items in grid view', () => {
    const note = createTestNote({
      checklist: [
        { id: '1', text: 'Task 1', isChecked: false, position: 0 },
        { id: '2', text: 'Task 2', isChecked: false, position: 1 },
        { id: '3', text: 'Task 3', isChecked: false, position: 2 },
        { id: '4', text: 'Task 4', isChecked: false, position: 3 },
        { id: '5', text: 'Task 5', isChecked: false, position: 4 },
      ],
    });
    const { container, cleanup } = renderCard(note, { density: 'grid' });

    expect(container.textContent).toContain('Task 1');
    expect(container.textContent).toContain('Task 2');
    expect(container.textContent).toContain('Task 3');
    expect(container.textContent).not.toContain('Task 4');
    expect(container.textContent).toContain('+2 more');
    cleanup();
  });

  it('renders checked count in list view', () => {
    const note = createTestNote({
      checklist: [
        { id: '1', text: 'Task 1', isChecked: true, position: 0 },
        { id: '2', text: 'Task 2', isChecked: false, position: 1 },
      ],
    });
    const { container, cleanup } = renderCard(note, { density: 'list' });

    expect(container.textContent).toContain('1/2 checked');
    cleanup();
  });

  it('triggers onClick when clicked', () => {
    const note = createTestNote();
    const onClick = vi.fn();
    const { container, cleanup } = renderCard(note, { onClick });

    const button = container.querySelector('button');
    expect(button).not.toBeNull();
    act(() => {
      button?.click();
    });

    expect(onClick).toHaveBeenCalledTimes(1);
    cleanup();
  });

  it('triggers onToggleSelect when the selection checkbox is activated without calling onClick (UX-B)', () => {
    const note = createTestNote();
    const onClick = vi.fn();
    const onToggleSelect = vi.fn();
    const { container, cleanup } = renderCard(note, { onClick, onToggleSelect });

    const selectCheckbox = container.querySelector('button[role="checkbox"]');
    expect(selectCheckbox).not.toBeNull();
    act(() => {
      selectCheckbox?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });

    expect(onToggleSelect).toHaveBeenCalledTimes(1);
    expect(onClick).not.toHaveBeenCalled();
    cleanup();
  });

  it('renders label chips with minimum 24px touch/click target class (C5 / UX-08)', () => {
    const note = createTestNote({
      labels: [{ id: 'l1', name: 'Personal' }],
    });
    const onLabelClick = vi.fn();
    const { container, cleanup } = renderCard(note, { onLabelClick });

    const labelChip = Array.from(container.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Personal'),
    );
    expect(labelChip).toBeDefined();
    expect(labelChip?.className).toContain('min-h-[24px]');
    cleanup();
  });

  it('renders attachment indicator with count when attachments are present (Phase 6)', () => {
    const note = createTestNote({
      attachments: [
        { id: 'att-1', noteId: 1, storagePath: 'path1', type: 'image', mimeType: 'image/png', sizeBytes: 1000 },
        { id: 'att-2', noteId: 1, storagePath: 'path2', type: 'image', mimeType: 'image/jpeg', sizeBytes: 2000 },
      ],
    });
    const { container, cleanup } = renderCard(note);

    expect(container.textContent).toContain('2');
    cleanup();
  });

  it('provides desktop quick actions (archive, trash, pin) that do not trigger card onClick (UX-05)', () => {
    const note = createTestNote();
    const onClick = vi.fn();
    const onArchive = vi.fn();
    const onTrash = vi.fn();
    const onPinToggle = vi.fn();
    const { container, cleanup } = renderCard(note, { onClick, onArchive, onTrash, onPinToggle });

    const archiveBtn = container.querySelector('button[aria-label="Archive note"]');
    expect(archiveBtn).not.toBeNull();
    act(() => {
      archiveBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onArchive).toHaveBeenCalledTimes(1);
    expect(onClick).not.toHaveBeenCalled();

    const trashBtn = container.querySelector('button[aria-label="Delete note"]');
    expect(trashBtn).not.toBeNull();
    act(() => {
      trashBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onTrash).toHaveBeenCalledTimes(1);
    expect(onClick).not.toHaveBeenCalled();

    const pinBtn = container.querySelector('button[aria-label="Pin note"]');
    expect(pinBtn).not.toBeNull();
    act(() => {
      pinBtn?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onPinToggle).toHaveBeenCalledTimes(1);
    expect(onClick).not.toHaveBeenCalled();

    cleanup();
  });
});
