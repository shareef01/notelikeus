import { useEffect, useRef, useState } from 'react';
import {
  peekAttachmentThumbnailUrl,
  resolveAttachmentThumbnailUrl,
} from '@/lib/attachments/attachmentThumbnailCache';
import type { Attachment } from '@/types/attachment';

export type NoteCardThumbnailLayout = 'banner' | 'square';

interface NoteCardThumbnailProps {
  noteId: string;
  attachment: Attachment;
  /** `banner` runs across the top of a grid card; `square` sits beside a list row's text. */
  layout: NoteCardThumbnailLayout;
  /** Negative margins that pull a banner out to the card's edges, which depend on the card's padding. */
  bleedClassName?: string;
}

/** Start loading a little before the card scrolls into view, so the picture is usually there when it arrives. */
const LOAD_AHEAD_MARGIN = '240px';

/**
 * The first image of a note, on its card.
 *
 * Decorative: the card's own label already says the note has an image, so repeating it per card
 * would only make a screen reader announce every picture twice. The space is reserved up front so
 * the grid does not jump as pictures arrive, and a picture that cannot be loaded leaves nothing
 * behind rather than an empty box.
 */
export function NoteCardThumbnail({
  noteId,
  attachment,
  layout,
  bleedClassName = '',
}: NoteCardThumbnailProps) {
  const frameRef = useRef<HTMLDivElement>(null);
  const [url, setUrl] = useState<string | null>(() =>
    peekAttachmentThumbnailUrl(noteId, attachment.id),
  );
  const [near, setNear] = useState(
    () => url !== null || typeof IntersectionObserver === 'undefined',
  );
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (near) return undefined;
    const frame = frameRef.current;
    if (!frame) return undefined;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setNear(true);
          observer.disconnect();
        }
      },
      { rootMargin: LOAD_AHEAD_MARGIN },
    );
    observer.observe(frame);
    return () => observer.disconnect();
  }, [near]);

  useEffect(() => {
    if (!near) return undefined;
    let active = true;
    setFailed(false);
    void resolveAttachmentThumbnailUrl(noteId, attachment).then((resolved) => {
      if (!active) return;
      setUrl(resolved);
      setFailed(resolved === null);
    });
    return () => {
      active = false;
    };
  }, [near, noteId, attachment]);

  if (failed) return null;

  const frameClassName =
    layout === 'banner'
      ? `${bleedClassName} aspect-[4/3] w-auto overflow-hidden bg-black/10`
      : 'size-16 shrink-0 self-start overflow-hidden rounded-xl bg-black/10';

  return (
    <div ref={frameRef} className={frameClassName} data-testid="note-card-thumbnail">
      {url ? (
        <img
          src={url}
          alt=""
          decoding="async"
          draggable={false}
          className="size-full object-cover"
        />
      ) : null}
    </div>
  );
}
