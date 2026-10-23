import { useEffect, useRef, useState } from 'react';
import {
  peekAttachmentThumbnailUrl,
  releaseAttachmentThumbnail,
  resolveAttachmentThumbnailUrl,
  retainAttachmentThumbnail,
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
const LOAD_AHEAD_MARGIN = '600px';
/** Deactivate only when the card has scrolled well outside the viewport to prevent thrashing. */
const EXIT_MARGIN = '1200px';

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
  const [isActive, setIsActive] = useState(
    () => typeof IntersectionObserver === 'undefined',
  );
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const isMountedRef = useRef(true);
  const currentKeyRef = useRef(`${noteId}:${attachment.id}`);
  currentKeyRef.current = `${noteId}:${attachment.id}`;

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // Viewport-aware activation and deactivation with hysteresis:
  // - Enters active state when within LOAD_AHEAD_MARGIN (600px) of viewport
  // - Exits active state only when moved beyond EXIT_MARGIN (1200px) from viewport
  useEffect(() => {
    const frame = frameRef.current;
    if (!frame || typeof IntersectionObserver === 'undefined') return undefined;

    if (!isActive) {
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            setIsActive(true);
          }
        },
        { rootMargin: LOAD_AHEAD_MARGIN },
      );
      observer.observe(frame);
      return () => observer.disconnect();
    } else {
      const observer = new IntersectionObserver(
        (entries) => {
          const entry = entries[0];
          if (entry && !entry.isIntersecting) {
            setIsActive(false);
          }
        },
        { rootMargin: EXIT_MARGIN },
      );
      observer.observe(frame);
      return () => observer.disconnect();
    }
  }, [isActive]);

  useEffect(() => {
    if (!isActive) {
      setUrl(null);
      return undefined;
    }

    let active = true;
    const currentNoteId = noteId;
    const currentAttachmentId = attachment.id;
    const currentKey = `${currentNoteId}:${currentAttachmentId}`;

    // Retain active consumer ref so cache won't evict/revoke our URL while active
    retainAttachmentThumbnail(currentNoteId, currentAttachmentId);
    setFailed(false);

    // Fast-path: peek cached URL immediately to avoid any blink if already in memory
    const cached = peekAttachmentThumbnailUrl(currentNoteId, currentAttachmentId);
    if (cached) {
      setUrl(cached);
    }

    void resolveAttachmentThumbnailUrl(currentNoteId, attachment).then((resolved) => {
      if (!active || !isMountedRef.current || currentKeyRef.current !== currentKey) return;
      setUrl(resolved);
      setFailed(resolved === null);
    });

    return () => {
      active = false;
      releaseAttachmentThumbnail(currentNoteId, currentAttachmentId);
    };
  }, [isActive, noteId, attachment.id, attachment]);

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
          onError={() => {
            const errorKey = `${noteId}:${attachment.id}`;
            void resolveAttachmentThumbnailUrl(noteId, attachment).then((resolved) => {
              if (!isMountedRef.current || currentKeyRef.current !== errorKey) return;
              if (resolved && resolved !== url) setUrl(resolved);
              else setFailed(true);
            });
          }}
        />
      ) : null}
    </div>
  );
}
