import { act, createElement, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { useAttachmentPreviewUrl } from '@/hooks/useAttachmentPreviewUrl';
import { pendingStoragePath } from '@/lib/attachments/attachmentPaths';
import {
  clearAttachmentPreviewCacheForTests,
} from '@/lib/attachments/attachmentPreviewCache';
import {
  clearPendingAttachmentsForTests,
  storePendingAttachment,
} from '@/lib/attachments/pendingAttachmentStore';

afterEach(() => {
  clearPendingAttachmentsForTests();
  clearAttachmentPreviewCacheForTests();
});

describe('useAttachmentPreviewUrl', () => {
  it('resolves a pending image into an object URL', async () => {
    storePendingAttachment('att-1', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' }), 'image/png');
    const container = document.createElement('div');
    document.body.appendChild(container);
    let seen: string | null = null;

    function Host() {
      const url = useAttachmentPreviewUrl('note-1', {
        id: 'att-1',
        noteId: 1,
        storagePath: pendingStoragePath('att-1'),
        type: 'image',
        mimeType: 'image/png',
      });
      useEffect(() => {
        seen = url;
      }, [url]);
      return null;
    }

    const root = createRoot(container);
    await act(async () => {
      root.render(createElement(Host));
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(seen).toMatch(/^blob:/);

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
