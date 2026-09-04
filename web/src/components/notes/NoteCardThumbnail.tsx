import { useAttachmentPreviewUrl } from '@/hooks/useAttachmentPreviewUrl';
import type { Attachment } from '@/types/attachment';

interface NoteCardThumbnailProps {
  noteId: string;
  attachment: Attachment;
  density: 'list' | 'grid' | 'dense';
}

export function NoteCardThumbnail({ noteId, attachment, density }: NoteCardThumbnailProps) {
  const url = useAttachmentPreviewUrl(noteId, attachment);
  if (!url) return null;

  const sizeClass =
    density === 'list'
      ? 'h-14 w-14'
      : density === 'dense'
        ? 'h-24 w-full'
        : 'h-32 w-full';

  return (
    <img
      src={url}
      alt=""
      className={`${sizeClass} shrink-0 rounded-xl object-cover`}
    />
  );
}
