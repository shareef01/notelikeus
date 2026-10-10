import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import type { Note } from '@/types/note';
import type { Attachment } from '@/types/attachment';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const resolveThumbnail = vi.fn();
vi.mock('@/lib/attachments/attachmentThumbnailCache', () => ({
  peekAttachmentThumbnailUrl: () => null,
  resolveAttachmentThumbnailUrl: (...args: unknown[]) => resolveThumbnail(...args),
}));

import { NoteCard } from '@/components/notes/NoteCard';

/** Controllable IntersectionObserver: nothing is on screen until a test says so. */
const observers: Array<{ callback: IntersectionObserverCallback; disconnected: boolean }> = [];
class FakeIntersectionObserver {
  private readonly record: { callback: IntersectionObserverCallback; disconnected: boolean };
  constructor(callback: IntersectionObserverCallback) {
    this.record = { callback, disconnected: false };
    observers.push(this.record);
  }
  observe() {}
  unobserve() {}
  disconnect() {
    this.record.disconnected = true;
  }
  takeRecords() {
    return [];
  }
}

function scrollIntoView() {
  for (const record of observers.filter((o) => !o.disconnected)) {
    record.callback(
      [{ isIntersecting: true } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    );
  }
}

const attachment = (overrides: Partial<Attachment> = {}): Attachment => ({
  id: 'att-1',
  noteId: 1,
  storagePath: 'r2:owners/o/notes/n/att-1',
  type: 'image',
  mimeType: 'image/png',
  ...overrides,
});

function note(attachments: Attachment[]): Note {
  return {
    id: 'note-1',
    localId: 1,
    title: 'Receipts',
    content: '',
    color: 0,
    timestamp: 1_700_000_000_000,
    isPinned: false,
    isArchived: false,
    isTrashed: false,
    position: 0,
    reminderTimestamp: null,
    serverUpdatedAt: null,
    labels: [],
    attachments,
    checklist: [],
  };
}

async function renderCard(
  target: Note,
  density: 'grid' | 'dense' | 'list' = 'grid',
  { onScreen = true }: { onScreen?: boolean } = {},
) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  await act(async () => {
    root.render(createElement(NoteCard, { note: target, onClick: vi.fn(), density }));
  });
  if (onScreen) {
    await act(async () => {
      scrollIntoView();
    });
  }
  // Let the thumbnail's promise settle.
  await act(async () => {
    await Promise.resolve();
  });
  return {
    container,
    cleanup: () => {
      act(() => root.unmount());
      container.remove();
    },
  };
}

describe('NoteCard thumbnail', () => {
  beforeEach(() => {
    observers.length = 0;
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
  });

  afterEach(() => {
    resolveThumbnail.mockReset();
    vi.unstubAllGlobals();
  });

  it('does not load the picture until the card is near the viewport', async () => {
    resolveThumbnail.mockResolvedValue('blob:thumb');
    const { container, cleanup } = await renderCard(note([attachment()]), 'grid', { onScreen: false });

    // Space is reserved so the grid does not jump, but nothing has been fetched.
    expect(container.querySelector('[data-testid="note-card-thumbnail"]')).not.toBeNull();
    expect(resolveThumbnail).not.toHaveBeenCalled();

    await act(async () => {
      scrollIntoView();
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(resolveThumbnail).toHaveBeenCalledTimes(1);
    expect(container.querySelector('[data-testid="note-card-thumbnail"] img')?.getAttribute('src')).toBe('blob:thumb');
    cleanup();
  });

  it('shows the first image of a note as a decorative picture on a grid card', async () => {
    resolveThumbnail.mockResolvedValue('blob:thumb');
    const { container, cleanup } = await renderCard(note([attachment()]));

    const image = container.querySelector('[data-testid="note-card-thumbnail"] img');
    expect(image?.getAttribute('src')).toBe('blob:thumb');
    // The card's own label already says it has an image; the picture must not be announced as well.
    expect(image?.getAttribute('alt')).toBe('');
    expect(resolveThumbnail).toHaveBeenCalledWith('note-1', expect.objectContaining({ id: 'att-1' }));
    cleanup();
  });

  it('uses a banner on grid and dense cards and a square beside the text on a list row', async () => {
    resolveThumbnail.mockResolvedValue('blob:thumb');

    const grid = await renderCard(note([attachment()]), 'grid');
    expect(grid.container.querySelector('[data-testid="note-card-thumbnail"]')?.className).toContain('aspect-[4/3]');
    grid.cleanup();

    const dense = await renderCard(note([attachment()]), 'dense');
    expect(dense.container.querySelector('[data-testid="note-card-thumbnail"]')?.className).toContain('aspect-[4/3]');
    dense.cleanup();

    const list = await renderCard(note([attachment()]), 'list');
    expect(list.container.querySelector('[data-testid="note-card-thumbnail"]')?.className).toContain('size-16');
    list.cleanup();
  });

  it('has no thumbnail for a note without attachments', async () => {
    const { container, cleanup } = await renderCard(note([]));

    expect(container.querySelector('[data-testid="note-card-thumbnail"]')).toBeNull();
    expect(resolveThumbnail).not.toHaveBeenCalled();
    cleanup();
  });

  it('skips attachments that are not images', async () => {
    const { container, cleanup } = await renderCard(
      note([attachment({ id: 'pdf', type: 'file', mimeType: 'application/pdf' })]),
    );

    expect(container.querySelector('[data-testid="note-card-thumbnail"]')).toBeNull();
    expect(resolveThumbnail).not.toHaveBeenCalled();
    cleanup();
  });

  it('picks the first image even when a non-image comes before it', async () => {
    resolveThumbnail.mockResolvedValue('blob:thumb');
    const { cleanup } = await renderCard(
      note([attachment({ id: 'pdf', mimeType: 'application/pdf' }), attachment({ id: 'photo' })]),
    );

    expect(resolveThumbnail).toHaveBeenCalledWith('note-1', expect.objectContaining({ id: 'photo' }));
    cleanup();
  });

  it('leaves no empty box behind when the picture cannot be loaded', async () => {
    resolveThumbnail.mockResolvedValue(null);
    const { container, cleanup } = await renderCard(note([attachment()]));

    expect(container.querySelector('[data-testid="note-card-thumbnail"]')).toBeNull();
    // The card itself, and its "has image" status, are unaffected.
    expect(container.textContent).toContain('Receipts');
    cleanup();
  });
});
