import { BoldIcon, BulletListIcon, ChecklistIcon, ImageIcon, ItalicIcon, LinkIcon } from '@/components/icons/Icons';
import type { ReactNode, SyntheticEvent } from 'react';

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

  // The action runs on click, which a mouse, a tap, Enter, Space and a screen reader all fire.
  // It used to run on pointerdown alone, which made every button here dead to the keyboard.
  // Pointerdown and mousedown are still cancelled so a click or a tap does not take focus from the
  // note body, which keeps the caret and the selection where they were, and keeps marker-only
  // content (e.g. ****) from flipping the editor into markdown preview, where an empty bold span
  // is invisible.
  const keepBodyFocus = (event: SyntheticEvent) => event.preventDefault();

  const formatButton = (label: string, icon: ReactNode, action: () => void, shortcut?: string) => (
    <button
      type="button"
      className={buttonClass}
      onPointerDown={keepBodyFocus}
      onMouseDown={keepBodyFocus}
      onClick={action}
      aria-label={label}
      aria-keyshortcuts={shortcut}
      title={shortcut ? `${label} (${shortcut.replace('Control', 'Ctrl')})` : label}
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
      {formatButton('Bold', <BoldIcon size={18} />, onBold, 'Control+B')}
      {formatButton('Italic', <ItalicIcon size={18} />, onItalic, 'Control+I')}
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
