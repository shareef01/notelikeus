import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { ConfirmDialog } from '@/components/ui/ModalDialog';
import { AttachmentImageViewer } from '@/components/editor/AttachmentImageViewer';
import { CHROME_FOCUS } from '@/lib/ui/focusStyles';
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
  inlineSlots: ReadonlyMap<string, HTMLElement>;
  onPlace?: (attachmentId: string) => void;
  onUnplace?: (attachmentId: string) => void;
}

export function AttachmentImageStrip({
  noteId,
  attachments,
  contentColor,
  onRemove,
  inlineSlots,
  onPlace,
  onUnplace,
}: AttachmentImageStripProps) {
  const [viewerId, setViewerId] = useState<string | null>(null);
  if (attachments.length === 0) return null;

  return (
    <div className="mt-5 flex flex-col gap-3 empty:hidden">
      {attachments.map((attachment, index) => {
        const slot = inlineSlots.get(attachment.id);
        // Keyed by note too, so the state below starts over for a different note's picture.
        const key = `${noteId}:${attachment.id}`;
        const preview = <AttachmentPreview
          key={key}
          noteId={noteId}
          attachment={attachment}
          contentColor={contentColor}
          onRemove={() => onRemove(attachment.id)}
          onPlace={slot || !onPlace ? undefined : () => onPlace(attachment.id)}
          onUnplace={slot && onUnplace ? () => onUnplace(attachment.id) : undefined}
          viewing={viewerId === attachment.id}
          openViewer={() => setViewerId(attachment.id)}
          closeViewer={() => setViewerId(null)}
          position={attachments.length > 1 ? `${index + 1} of ${attachments.length}` : undefined}
          previous={index > 0 ? () => setViewerId(attachments[index - 1]!.id) : undefined}
          next={index < attachments.length - 1 ? () => setViewerId(attachments[index + 1]!.id) : undefined}
        />;
        return slot ? createPortal(preview, slot, key) : preview;
      })}
    </div>
  );
}

function AttachmentPreview({
  noteId,
  attachment,
  contentColor,
  onRemove,
  onPlace,
  onUnplace,
  viewing,
  openViewer,
  closeViewer,
  position,
  previous,
  next,
}: {
  noteId: string;
  attachment: Attachment;
  contentColor: string;
  onRemove: () => void;
  onPlace?: () => void;
  onUnplace?: () => void;
  viewing: boolean;
  openViewer: () => void;
  closeViewer: () => void;
  position?: string;
  previous?: () => void;
  next?: () => void;
}) {
  // Opened before: the full picture is still cached, and nothing is fetched again.
  const [previewUrl, setPreviewUrl] = useState(() => peekAttachmentPreviewUrl(noteId, attachment.id));
  // Otherwise the note's card has usually made its thumbnail already. It stands in at once, instead
  // of a loading message, until the full picture is ready.
  const [placeholderUrl] = useState(() =>
    previewUrl ? null : peekAttachmentThumbnailUrl(noteId, attachment.id),
  );
  const [failed, setFailed] = useState(false);
  const [confirmRemoval, setConfirmRemoval] = useState(false);
  const [invalidUrls, setInvalidUrls] = useState<string[]>([]);

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

  const shownUrl = [previewUrl, placeholderUrl].find((url) => url && !invalidUrls.includes(url));
  const loadFailed = failed || invalidUrls.length > 0;
  const markDecodeFailure = (url: string) => {
    setInvalidUrls((urls) => urls.includes(url) ? urls : [...urls, url]);
  };

  return (
    <figure className="max-w-full self-start">
      {shownUrl ? (
        <button
          type="button"
          aria-label="Open image"
          onClick={(event) => {
            event.currentTarget.focus({ preventScroll: true });
            openViewer();
          }}
          className={`block min-h-11 min-w-11 max-w-full cursor-zoom-in rounded-note ${CHROME_FOCUS}`}
        >
          <img
            src={shownUrl}
            alt="Note attachment"
            decoding="async"
            onError={() => markDecodeFailure(shownUrl)}
            className="mx-auto block h-auto max-h-[60dvh] w-auto max-w-full rounded-note object-contain"
          />
        </button>
      ) : (
        <div
          role="status"
          className="flex min-h-28 items-center justify-center rounded-note bg-true-surface-variant px-4 text-sm"
          style={{ color: contentColor }}
        >
          {loadFailed ? 'Could not load image' : 'Loading image…'}
        </div>
      )}
      {onPlace || onUnplace ? (
        <button
          type="button"
          className={`tap-target rounded-note px-2 text-sm font-medium opacity-80 hover:opacity-100 ${CHROME_FOCUS}`}
          onClick={onUnplace ?? onPlace}
        >
          {onUnplace ? 'Move to attachment strip' : 'Place after cursor paragraph'}
        </button>
      ) : null}
      {loadFailed && !shownUrl ? (
        <button
          type="button"
          className={`tap-target px-3 text-sm text-brand-muted ${CHROME_FOCUS}`}
          onClick={() => setConfirmRemoval(true)}
        >
          Remove image
        </button>
      ) : null}
      {viewing ? (
        <AttachmentImageViewer
          url={shownUrl ?? undefined}
          failed={loadFailed}
          onDecodeFailure={markDecodeFailure}
          onClose={closeViewer}
          onRemove={() => setConfirmRemoval(true)}
          position={position}
          previous={previous}
          next={next}
        />
      ) : null}
      {confirmRemoval ? createPortal(
        <ConfirmDialog
          open
          title="Remove this image?"
          description="This removes the image from this note."
          confirmLabel="Remove image"
          onCancel={() => setConfirmRemoval(false)}
          onConfirm={() => {
            setConfirmRemoval(false);
            closeViewer();
            onRemove();
          }}
        />,
        document.body,
      ) : null}
    </figure>
  );
}
