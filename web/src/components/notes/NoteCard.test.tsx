import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NoteCard } from '@/components/notes/NoteCard';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import { clearAttachmentPreviewCacheForTests } from '@/lib/attachments/attachmentPreviewCache';
import {
  clearPendingAttachmentsForTests,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';
import { createEmptyNote } from '@/types/note';

afterEach(() => {
  clearPendingAttachmentsForTests();
  clearAttachmentPreviewCacheForTests();
});

describe('NoteCard thumbnails', () => {
  it('renders a list thumbnail for a pending image and keeps Has image in the label', async () => {
    storePendingAttachment(
      'att-1',
      new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' }),
      'image/png',
    );
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(
        createElement(NoteCard, {
          note: createEmptyNote({
            id: '7',
            localId: 7,
            title: 'Photo note',
            attachments: [
              {
                id: 'att-1',
                noteId: 7,
                storagePath: pendingStoragePath('att-1'),
                type: 'image',
                mimeType: 'image/png',
              },
            ],
          }),
          onClick: vi.fn(),
          density: 'list',
        }),
      );
    });
    await act(async () => {
      await Promise.resolve();
    });

    const open = container.querySelector('button[aria-label]');
    expect(open?.getAttribute('aria-label')).toContain('Has image');
    const thumb = container.querySelector('img');
    expect(thumb).not.toBeNull();
    expect(thumb?.getAttribute('src')).toMatch(/^blob:/);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
