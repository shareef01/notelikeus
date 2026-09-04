import { useEffect, useState } from 'react';
import { resolveAttachmentPreviewUrl } from '@/lib/attachments/attachmentPreviewCache';
import type { Attachment } from '@/types/attachment';

/**
 * Resolves a preview URL for list/editor thumbnails.
 *
 * The shared cache keeps object URLs alive across virtualized list unmounts so scrolling
 * does not re-download. Callers must not revoke on unmount.
 */
export function useAttachmentPreviewUrl(
  noteId: string,
  attachment: Attachment | undefined,
): string | null {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!attachment) {
      setUrl(null);
      return;
    }
    let active = true;
    void resolveAttachmentPreviewUrl(noteId, attachment).then((next) => {
      if (active) setUrl(next);
    });
    return () => {
      active = false;
    };
  }, [noteId, attachment]);

  return url;
}
