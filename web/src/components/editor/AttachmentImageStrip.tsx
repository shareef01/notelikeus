import { useEffect, useState } from 'react';
import {
  peekAttachmentPreviewUrl,
  releaseAttachmentPreview,
  resolveAttachmentPreviewUrl,
  retainAttachmentPreview,
} from '@/lib/attachments/attachmentPreviewCache';
import {
  peekAttachmentThumbnailUrl,
  releaseAttachmentThumbnail,
  retainAttachmentThumbnail,
} from '@/lib/attachments/attachmentThumbnailCache';
import type { Attachment } from '@/types/attachment';

interface AttachmentImageStripProps {
  noteId: string;
  attachments: Attachment[];
  contentColor: string;
  onRemove: (attachmentId: string) => void;
}

export function AttachmentImageStrip({
  noteId,
  attachments,
  contentColor,
  onRemove,
}: AttachmentImageStripProps) {
  if (attachments.length === 0) return null;

  return (
    <div className="mb-4 flex flex-col gap-3">
      {attachments.map((attachment) => (
        <AttachmentPreview
          // Keyed by note too, so the state below starts over for a different note's picture.
          key={`${noteId}:${attachment.id}`}
          noteId={noteId}
          attachment={attachment}
          contentColor={contentColor}
          onRemove={() => onRemove(attachment.id)}
        />
      ))}
    </div>
  );
}

function AttachmentPreview({
  noteId,
  attachment,
  contentColor,
  onRemove,
}: {
  noteId: string;
  attachment: Attachment;
  contentColor: string;
  onRemove: () => void;
}) {
  // Opened before: the full picture is still cached, and nothing is fetched again.
  const [previewUrl, setPreviewUrl] = useState(() => peekAttachmentPreviewUrl(noteId, attachment.id));
  // Otherwise the note's card has usually made its thumbnail already. It stands in at once, instead
  // of a loading message, until the full picture is ready.
  const [placeholderUrl] = useState(() =>
    previewUrl ? null : peekAttachmentThumbnailUrl(noteId, attachment.id),
  );
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    retainAttachmentPreview(noteId, attachment.id);
    if (placeholderUrl) retainAttachmentThumbnail(noteId, attachment.id);
    void resolveAttachmentPreviewUrl(noteId, attachment).then(async (url) => {
      if (!active) return;
      // A thumbnail already showing is better than the failure message.
      if (url == null) {
        if (!placeholderUrl) setFailed(true);
        return;
      }
      // Decode before swapping, so the full picture replaces the placeholder in one frame rather
      // than painting in over it.
      if (placeholderUrl) {
        const image = new Image();
        image.src = url;
        await image.decode().catch(() => {});
      }
      if (active) setPreviewUrl(url);
    });
    return () => {
      active = false;
      releaseAttachmentPreview(noteId, attachment.id);
      if (placeholderUrl) releaseAttachmentThumbnail(noteId, attachment.id);
    };
  }, [attachment, noteId, placeholderUrl]);

  const shownUrl = previewUrl ?? placeholderUrl;

  return (
    <div className="relative overflow-hidden rounded-2xl border border-[color-mix(in_srgb,currentColor_12%,transparent)]">
      {shownUrl ? (
        <img
          src={shownUrl}
          alt="Note attachment"
          decoding="async"
          className="max-h-72 w-full object-cover"
        />
      ) : (
        <div
          className="flex min-h-28 items-center justify-center px-4 text-sm opacity-80"
          style={{ color: contentColor }}
        >
          {failed ? 'Could not load image' : 'Loading image…'}
        </div>
      )}
      <button
        type="button"
        onClick={onRemove}
        className="absolute right-2 top-2 flex size-9 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white"
        aria-label="Remove image"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
          <path d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7A1 1 0 1 0 5.7 7.11L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z" />
        </svg>
      </button>
    </div>
  );
}
