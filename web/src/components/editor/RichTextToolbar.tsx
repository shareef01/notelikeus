import { BoldIcon, BulletListIcon, ChecklistIcon, ImageIcon, ItalicIcon, LinkIcon } from '@/components/icons/Icons';
import type { PointerEventHandler, ReactNode } from 'react';

interface RichTextToolbarProps {
  contentColor: string;
  onBold: () => void;
  onItalic: () => void;
  onBullet: () => void;
  onChecklist: () => void;
  onLink: () => void;
  onAddImage?: () => void;
}

export function RichTextToolbar({
  contentColor,
  onBold,
  onItalic,
  onBullet,
  onChecklist,
  onLink,
  onAddImage,
}: RichTextToolbarProps) {
  const buttonClass =
    'flex size-9 items-center justify-center rounded-full transition-colors hover:bg-[color-mix(in_srgb,currentColor_12%,transparent)] active:bg-[color-mix(in_srgb,currentColor_18%,transparent)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current';

  // Run on pointerdown with preventDefault so mobile taps do not blur the textarea
  // before formatting runs — and so marker-only content (e.g. ****) does not flip the
  // editor into markdown preview, where an empty bold span is invisible.
  const onFormatPointerDown =
    (action: () => void): PointerEventHandler<HTMLButtonElement> =>
    (event) => {
      event.preventDefault();
      action();
    };

  const formatButton = (label: string, icon: ReactNode, action: () => void) => (
    <button
      type="button"
      className={buttonClass}
      onPointerDown={onFormatPointerDown(action)}
      aria-label={label}
      title={label}
    >
      {icon}
    </button>
  );

  return (
    <div
      className="sticky top-0 z-10 mt-5 inline-flex max-w-full flex-wrap items-center gap-0.5 py-0.5"
      style={{ color: contentColor }}
      role="toolbar"
      aria-label="Text formatting"
    >
      {formatButton('Bold', <BoldIcon size={18} />, onBold)}
      {formatButton('Italic', <ItalicIcon size={18} />, onItalic)}
      {formatButton('Link', <LinkIcon size={18} />, onLink)}
      <span className="mx-1 h-4 w-px shrink-0 bg-current opacity-20" aria-hidden />
      {formatButton('Bullet list', <BulletListIcon size={18} />, onBullet)}
      {formatButton('Checklist', <ChecklistIcon size={18} />, onChecklist)}
      {onAddImage ? (
        <>
          <span className="mx-1 h-4 w-px shrink-0 bg-current opacity-20" aria-hidden />
          {formatButton('Add image', <ImageIcon size={18} />, onAddImage)}
        </>
      ) : null}
    </div>
  );
}
