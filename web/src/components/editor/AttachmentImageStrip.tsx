import { useEffect, useState } from 'react';
import { resolveAttachmentPreviewUrl, revokeAttachmentPreviewUrl } from '@/lib/attachments/attachmentPreviewCache';
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
          key={attachment.id}
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
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    void resolveAttachmentPreviewUrl(noteId, attachment).then((url) => {
      if (!active) return;
      setPreviewUrl(url);
      setFailed(url == null);
    });
    return () => {
      active = false;
      revokeAttachmentPreviewUrl(noteId, attachment.id);
    };
  }, [attachment, noteId]);

  return (
    <div className="relative overflow-hidden rounded-2xl border border-[color-mix(in_srgb,currentColor_12%,transparent)]">
      {previewUrl && !failed ? (
        <img
          src={previewUrl}
          alt="Note attachment"
          className="max-h-72 w-full object-cover"
        />
      ) : (
        <div
          className="flex min-h-28 items-center justify-center px-4 text-sm opacity-70"
          style={{ color: contentColor }}
        >
          {failed ? 'Could not load image' : 'Loading image…'}
        </div>
      )}
      <button
        type="button"
        onClick={onRemove}
        className="absolute right-2 top-2 flex size-9 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm"
        aria-label="Remove image"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
          <path d="M18.3 5.71a1 1 0 0 0-1.41 0L12 10.59 7.11 5.7A1 1 0 1 0 5.7 7.11L10.59 12l-4.89 4.89a1 1 0 1 0 1.41 1.41L12 13.41l4.89 4.89a1 1 0 0 0 1.41-1.41L13.41 12l4.89-4.89a1 1 0 0 0 0-1.4z" />
        </svg>
      </button>
    </div>
  );
}
