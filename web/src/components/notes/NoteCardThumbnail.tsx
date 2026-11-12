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
/** Banner frame proportions (width / height): reserved before load, and the tallest frame a portrait gets. */
const DEFAULT_BANNER_ASPECT = 4 / 3;
const MIN_BANNER_ASPECT = 4 / 5;

/**
 * The first image of a note, on its card.
 *
 * Decorative: the card's own label already says the note has an image, so repeating it per card
 * would only make a screen reader announce every picture twice. Reserve a bounded frame until
 * the image loads, then keep its measured proportions even when it scrolls out of view.
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
  const [aspectRatio, setAspectRatio] = useState<number>();
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

  const isBanner = layout === 'banner';
  // A banner spans the card's full width and never crops: the whole picture sits centred at its own
  // proportions, and a blurred copy of it fills whatever the picture leaves beside it (a portrait
  // photo in a wide card), so the frame reads as one edge-to-edge image rather than a floating tile.
  const bannerAspect =
    aspectRatio === undefined
      ? DEFAULT_BANNER_ASPECT
      : aspectRatio >= 1
        ? aspectRatio
        : Math.max(MIN_BANNER_ASPECT, aspectRatio);
  const frameClassName =
    isBanner
      ? `${bleedClassName} relative max-h-64 overflow-hidden ${url ? 'bg-black/20' : 'bg-black/10'}`
      : 'size-16 shrink-0 self-start overflow-hidden rounded-xl bg-black/10';

  return (
    <div
      ref={frameRef}
      className={frameClassName}
      style={isBanner ? { aspectRatio: bannerAspect } : undefined}
      data-testid="note-card-thumbnail"
    >
      {isBanner && url ? (
        <div
          aria-hidden
          className="absolute -inset-6 scale-110 bg-cover bg-center blur-2xl brightness-[0.55] saturate-150"
          style={{ backgroundImage: `url("${url}")` }}
        />
      ) : null}
      {url ? (
        <img
          src={url}
          alt=""
          decoding="async"
          draggable={false}
          className={
            isBanner
              ? 'relative mx-auto block h-full w-auto max-w-full object-contain'
              : 'size-full object-contain'
          }
          onLoad={({ currentTarget }) => {
            if (isBanner && currentTarget.naturalWidth && currentTarget.naturalHeight) {
              setAspectRatio(currentTarget.naturalWidth / currentTarget.naturalHeight);
            }
          }}
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
